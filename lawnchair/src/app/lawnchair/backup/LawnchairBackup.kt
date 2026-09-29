package app.lawnchair.backup

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import androidx.room.withTransaction
import app.lawnchair.LawnchairProto.BackupInfo
import app.lawnchair.data.AppDatabase
import app.lawnchair.data.category.CategoryInfoEntity
import app.lawnchair.data.category.CategoryItemEntity
import app.lawnchair.util.hasFlag
import app.lawnchair.util.scaleDownTo
import app.lawnchair.util.scaleDownToDisplaySize
import app.lawnchair.wallpaper.WallpaperColorsCompat
import app.lawnchair.wallpaper.WallpaperManagerCompat
import com.android.launcher3.BuildConfig
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherFiles
import com.android.launcher3.R
import com.android.launcher3.model.DeviceGridState
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.provider.RestoreDbTask
import com.google.protobuf.Timestamp
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

import app.lawnchair.data.folder.FolderInfoEntity
import app.lawnchair.data.folder.FolderItemEntity
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached

class LawnchairBackup(
    private val context: Context,
    private val uri: Uri,
) {
    lateinit var info: BackupInfo
    var screenshot: Bitmap? = null
    var wallpaper: Bitmap? = null

    suspend fun readInfoAndPreview() {
        var tmpScreenshot: Bitmap? = null
        var tmpWallpaper: Bitmap? = null
        readZip(
            mapOf(
                INFO_FILE_NAME to { info = BackupInfo.newBuilder().mergeFrom(it).build() },
                SCREENSHOT_FILE_NAME to { tmpScreenshot = BitmapFactory.decodeStream(it) },
                WALLPAPER_FILE_NAME to { tmpWallpaper = BitmapFactory.decodeStream(it) },
            ),
        )
        val size = max(info.previewWidth, info.previewHeight).coerceAtMost(4000)
        screenshot = tmpScreenshot?.scaleDownTo(size)
        wallpaper = tmpWallpaper?.scaleDownToDisplaySize(context)
    }

    suspend fun restore(selectedContents: Int) = withContext(Dispatchers.IO) {
        val contents = selectedContents and info.contents
        val stagedRestore = stageRestoreArchive(contents)
        val replacements = mutableListOf<FileReplacement>()
        val previousGridState = DeviceGridState(context)
        try {
            val categoriesJson = stagedRestore.files[CATEGORIES_FILE_NAME]?.readText()
            val foldersJson = stagedRestore.files[FOLDERS_FILE_NAME]?.readText()

            // Parse every structured payload before replacing or deleting any live data.
            categoriesJson?.let(::parseCategoriesJson)
            foldersJson?.let(::parseFoldersJson)
            stagedRestore.files[WALLPAPER_FILE_NAME]?.let(::validateWallpaperFile)

            if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
                AppDatabase.INSTANCE.get(context).checkpoint()
                AppDatabase.reset(context)
                installStagedLayoutFiles(stagedRestore, replacements)
            }

            if (contents.hasFlag(INCLUDE_CATEGORIES) && categoriesJson != null) {
                restoreCategoriesFromJson(context, categoriesJson)
            }
            if (
                (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS) || contents.hasFlag(INCLUDE_CATEGORIES)) &&
                foldersJson != null
            ) {
                restoreFoldersFromJson(context, foldersJson)
            }

            if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
                DeviceGridState(info.gridState).writeToPrefs(context, true)
            }
            val dbController = ModelDbController(context)
            RestoreDbTask.performRestore(context, dbController)
            replacements.forEach { it.backup?.delete() }
            if (contents.hasFlag(INCLUDE_WALLPAPER)) {
                runCatching {
                    stagedRestore.files[WALLPAPER_FILE_NAME]?.inputStream()?.use { input ->
                        WallpaperManager.getInstance(context).setStream(input)
                    }
                }
            }
        } catch (t: Throwable) {
            AppDatabase.reset(context)
            rollbackFileReplacements(replacements)
            if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
                previousGridState.writeToPrefs(context, true)
            }
            throw t
        } finally {
            stagedRestore.directory.deleteRecursively()
        }
    }

    private data class StagedRestore(val directory: File, val files: Map<String, File>)

    private data class FileReplacement(val target: File, val backup: File?)

    private suspend fun stageRestoreArchive(contents: Int): StagedRestore = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "restore-${System.nanoTime()}")
        check(directory.mkdirs()) { "Unable to create restore staging directory" }
        val selectedNames = buildSet {
            if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
                addAll(getFiles(context, forRestore = false).keys)
            }
            if (contents.hasFlag(INCLUDE_WALLPAPER)) add(WALLPAPER_FILE_NAME)
            if (contents.hasFlag(INCLUDE_CATEGORIES)) add(CATEGORIES_FILE_NAME)
            if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS) || contents.hasFlag(INCLUDE_CATEGORIES)) {
                add(FOLDERS_FILE_NAME)
            }
        }
        val stagedFiles = mutableMapOf<String, File>()
        try {
            readZip(
                selectedNames.associateWith { name ->
                    { input: InputStream ->
                        val target = File(directory, name)
                        target.outputStream().use { output ->
                            copyWithLimit(input, output, maxRestoreEntrySize(name))
                        }
                        stagedFiles[name] = target
                    }
                },
            )
            if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS) && LAUNCHER_DB_FILE_NAME !in stagedFiles) {
                throw IOException("Backup does not contain a launcher database")
            }
            if (contents.hasFlag(INCLUDE_CATEGORIES) && CATEGORIES_FILE_NAME !in stagedFiles) {
                throw IOException("Backup does not contain categories")
            }
            StagedRestore(directory, stagedFiles)
        } catch (t: Throwable) {
            directory.deleteRecursively()
            throw t
        }
    }

    private fun copyWithLimit(input: InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("Backup entry exceeds the allowed size")
            output.write(buffer, 0, count)
        }
    }

    private fun maxRestoreEntrySize(name: String): Long = when (name) {
        LAUNCHER_DB_FILE_NAME, PREFS_DB_FILE_NAME -> 128L * 1024 * 1024
        WALLPAPER_FILE_NAME -> 64L * 1024 * 1024
        CATEGORIES_FILE_NAME, FOLDERS_FILE_NAME -> 16L * 1024 * 1024
        else -> 16L * 1024 * 1024
    }

    private fun installStagedLayoutFiles(
        stagedRestore: StagedRestore,
        replacements: MutableList<FileReplacement>,
    ) {
        val restoreTargets = getFiles(context, forRestore = true)
        stagedRestore.files.forEach { (name, stagedFile) ->
            val target = restoreTargets[name] ?: return@forEach
            target.parentFile?.mkdirs()
            replaceFile(stagedFile, target, stagedRestore.directory, replacements)
            if (name == LAUNCHER_DB_FILE_NAME || name == PREFS_DB_FILE_NAME) {
                archiveFile(File(target.path + "-wal"), stagedRestore.directory, replacements)
                archiveFile(File(target.path + "-shm"), stagedRestore.directory, replacements)
            }
        }
    }

    private fun replaceFile(
        source: File,
        target: File,
        stagingDirectory: File,
        replacements: MutableList<FileReplacement>,
    ) {
        val pending = File(target.parentFile, "${target.name}.restore-pending")
        pending.delete()
        source.copyTo(pending, overwrite = true)
        archiveFile(target, stagingDirectory, replacements)
        if (!pending.renameTo(target)) {
            pending.delete()
            throw IOException("Unable to install ${target.name}")
        }
    }

    private fun archiveFile(
        target: File,
        stagingDirectory: File,
        replacements: MutableList<FileReplacement>,
    ) {
        val backup = if (target.exists()) {
            File(stagingDirectory, "rollback-${replacements.size}-${target.name}").also { rollback ->
                if (!target.renameTo(rollback)) throw IOException("Unable to stage ${target.name} for rollback")
            }
        } else {
            null
        }
        replacements += FileReplacement(target, backup)
    }

    private fun rollbackFileReplacements(replacements: List<FileReplacement>) {
        replacements.asReversed().forEach { replacement ->
            replacement.target.delete()
            replacement.backup?.renameTo(replacement.target)
        }
    }

    private fun validateWallpaperFile(file: File) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw IOException("Backup contains an invalid wallpaper")
        }
    }

    private suspend fun readZip(handlers: Map<String, suspend (InputStream) -> Unit>) {
        withContext(Dispatchers.IO) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")!!
            pfd.use {
                FileInputStream(it.fileDescriptor).use { inStream ->
                    ZipInputStream(inStream).use { zipIs ->
                        var entry: ZipEntry?
                        while (true) {
                            entry = zipIs.nextEntry
                            if (entry == null) break
                            handlers[entry.name]?.invoke(zipIs)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val BACKUP_VERSION = 1
        private const val PREFS_FILE_NAME = "${LauncherFiles.SHARED_PREFERENCES_KEY}.xml"
        private const val PREFS_DB_FILE_NAME = "preferences"
        private const val PREFS_DATASTORE_FILE_NAME = "preferences.preferences_pb"

        const val INFO_FILE_NAME = "info.pb"
        const val WALLPAPER_FILE_NAME = "wallpaper.png"
        const val SCREENSHOT_FILE_NAME = "screenshot.png"
        const val LAUNCHER_DB_FILE_NAME = "launcher.db"
        const val RESTORED_DB_FILE_NAME = "restored.db"
        const val CATEGORIES_FILE_NAME = "categories.json"
        const val FOLDERS_FILE_NAME = "folders.json"

        const val INCLUDE_LAYOUT_AND_SETTINGS = 1 shl 0
        const val INCLUDE_WALLPAPER = 1 shl 1
        const val INCLUDE_CATEGORIES = 1 shl 2

        const val MIME_TYPE = "application/zip"
        val EXTRA_MIME_TYPES = arrayOf(MIME_TYPE, "application/x-zip", "application/octet-stream")

        val contentOptions = listOf(
            INCLUDE_LAYOUT_AND_SETTINGS to R.string.backup_content_layout_and_settings,
            INCLUDE_CATEGORIES to R.string.backup_content_categories,
        )

        fun generateBackupFileName(): String {
            val fileName = "Lawnchair_Backup ${SimpleDateFormat.getDateTimeInstance().format(Date())}"
            return "$fileName.lawnchairbackup"
        }

        fun getFiles(context: Context, forRestore: Boolean): Map<String, File> {
            val filesMap = mutableMapOf(
                LAUNCHER_DB_FILE_NAME to launcherDbFile(context, forRestore),
                PREFS_FILE_NAME to prefsFile(context),
                PREFS_DB_FILE_NAME to prefsDbFile(context),
                PREFS_DATASTORE_FILE_NAME to prefsDataStoreFile(context),
            )
            if (forRestore) {
                // Ensure target package shared prefs is also written on restore across debug/release variants
                filesMap["active_package_prefs"] = File(context.cacheDir.parent, "shared_prefs/${context.packageName}_preferences.xml")
            }
            return filesMap
        }

        /**
         * Serializes all categories and their custom drag order (categoryOrder) to JSON.
         */
        suspend fun buildCategoriesJson(context: Context): String = withContext(Dispatchers.IO) {
            val db = AppDatabase.INSTANCE.get(context)
            db.checkpoint()
            val categoriesWithItems = db.categoryDao().getAllCategoriesWithItems().first()
            val categoryOrderStr = PreferenceManager2.getInstance(context).categoryOrder.firstCached()
            val rootObj = JSONObject()
            rootObj.put("categoryOrder", categoryOrderStr)
            val arr = JSONArray()
            categoriesWithItems.sortedBy { it.category.rank }.forEach { categoryWithItems ->
                val obj = JSONObject()
                obj.put("rank", categoryWithItems.category.rank)
                obj.put("title", categoryWithItems.category.title)
                obj.put("hide", categoryWithItems.category.hide)
                val appsArr = JSONArray()
                categoryWithItems.items
                    .sortedBy { it.rank }
                    .mapNotNull { it.componentKey }
                    .forEach { appsArr.put(it) }
                obj.put("apps", appsArr)
                arr.put(obj)
            }
            rootObj.put("categories", arr)
            rootObj.toString()
        }

        /**
         * Restores categories and categoryOrder preference from JSON string.
         */
        private data class ParsedCategory(
            val rank: Int,
            val title: String,
            val hide: Boolean,
            val apps: List<String>,
        )

        private data class ParsedCategories(
            val categoryOrder: String?,
            val categories: List<ParsedCategory>,
        )

        private fun parseCategoriesJson(json: String): ParsedCategories {
            val trimmed = json.trim()
            val rootObj = if (trimmed.startsWith("{")) JSONObject(trimmed) else null
            if (rootObj != null && !rootObj.has("categories")) {
                throw IOException("Backup categories payload is incomplete")
            }
            val arr = rootObj?.optJSONArray("categories") ?: JSONArray(trimmed.takeUnless { rootObj != null } ?: "[]")
            val categories = (0 until arr.length()).map { index ->
                val obj = arr.getJSONObject(index)
                val appsArray = obj.optJSONArray("apps") ?: JSONArray()
                ParsedCategory(
                    rank = obj.optInt("rank", index),
                    title = obj.optString("title", "Category ${index + 1}"),
                    hide = obj.optBoolean("hide", false),
                    apps = (0 until appsArray.length()).map(appsArray::getString),
                )
            }
            return ParsedCategories(
                categoryOrder = rootObj?.optString("categoryOrder", "")?.takeIf(String::isNotBlank),
                categories = categories,
            )
        }

        suspend fun restoreCategoriesFromJson(context: Context, json: String) = withContext(Dispatchers.IO) {
            val parsed = parseCategoriesJson(json)
            val db = AppDatabase.INSTANCE.get(context)
            val dao = db.categoryDao()

            db.withTransaction {
                val existingCategories = dao.getAllCategoriesWithItems().first()
                for (existing in existingCategories) {
                    dao.deleteCategory(existing.category.id)
                }

                parsed.categories.forEach { category ->
                    val newCategoryId = dao.insertCategory(
                        CategoryInfoEntity(
                            title = category.title,
                            hide = category.hide,
                            rank = category.rank,
                        ),
                    ).toInt()
                    val items = category.apps.mapIndexed { rank, componentKey ->
                        CategoryItemEntity(
                            categoryId = newCategoryId,
                            rank = rank,
                            componentKey = componentKey,
                        )
                    }
                    if (items.isNotEmpty()) dao.insertCategoryItems(items)
                }
            }
            parsed.categoryOrder?.let { PreferenceManager2.getInstance(context).categoryOrder.set(it) }
        }

        /**
         * Serializes all App Drawer folders and folderOrder preference to JSON.
         */
        suspend fun buildFoldersJson(context: Context): String = withContext(Dispatchers.IO) {
            val db = AppDatabase.INSTANCE.get(context)
            db.checkpoint()
            val foldersWithItems = db.folderDao().getAllFoldersWithItems().first()
            val folderOrderStr = PreferenceManager.getInstance(context).drawerListOrder.get()
            val rootObj = JSONObject()
            rootObj.put("folderOrder", folderOrderStr)
            val arr = JSONArray()
            foldersWithItems.sortedBy { it.folder.rank }.forEach { folderWithItems ->
                val obj = JSONObject()
                obj.put("id", folderWithItems.folder.id)
                obj.put("rank", folderWithItems.folder.rank)
                obj.put("title", folderWithItems.folder.title)
                obj.put("hide", folderWithItems.folder.hide)
                val appsArr = JSONArray()
                folderWithItems.items
                    .sortedBy { it.rank }
                    .mapNotNull { it.componentKey }
                    .forEach { appsArr.put(it) }
                obj.put("apps", appsArr)
                arr.put(obj)
            }
            rootObj.put("folders", arr)
            rootObj.toString()
        }

        /**
         * Restores App Drawer folders and folderOrder preference from JSON string.
         */
        private data class ParsedFolder(
            val rank: Int,
            val title: String,
            val hide: Boolean,
            val apps: List<String>,
        )

        private data class ParsedFolders(
            val folderOrder: String?,
            val folders: List<ParsedFolder>,
        )

        private fun parseFoldersJson(json: String): ParsedFolders {
            val trimmed = json.trim()
            val rootObj = if (trimmed.startsWith("{")) JSONObject(trimmed) else null
            if (rootObj != null && !rootObj.has("folders")) {
                throw IOException("Backup folders payload is incomplete")
            }
            val arr = rootObj?.optJSONArray("folders") ?: JSONArray(trimmed.takeUnless { rootObj != null } ?: "[]")
            val folders = (0 until arr.length()).map { index ->
                val obj = arr.getJSONObject(index)
                val appsArray = obj.optJSONArray("apps") ?: JSONArray()
                ParsedFolder(
                    rank = obj.optInt("rank", index),
                    title = obj.optString("title", "Folder ${index + 1}"),
                    hide = obj.optBoolean("hide", false),
                    apps = (0 until appsArray.length()).map(appsArray::getString),
                )
            }
            return ParsedFolders(
                folderOrder = rootObj?.optString("folderOrder", "")?.takeIf(String::isNotBlank),
                folders = folders,
            )
        }

        suspend fun restoreFoldersFromJson(context: Context, json: String) = withContext(Dispatchers.IO) {
            val parsed = parseFoldersJson(json)
            val db = AppDatabase.INSTANCE.get(context)
            val dao = db.folderDao()

            db.withTransaction {
                val existingFolders = dao.getAllFoldersWithItems().first()
                for (existing in existingFolders) {
                    dao.deleteFolder(existing.folder.id)
                }

                parsed.folders.forEach { folder ->
                    val newFolderId = dao.insertFolder(
                        FolderInfoEntity(
                            title = folder.title,
                            hide = folder.hide,
                            rank = folder.rank,
                        ),
                    ).toInt()
                    val items = folder.apps.mapIndexed { rank, componentKey ->
                        FolderItemEntity(
                            folderId = newFolderId,
                            rank = rank,
                            componentKey = componentKey,
                        )
                    }
                    if (items.isNotEmpty()) dao.insertFolderItems(items)
                }
            }
            parsed.folderOrder?.let { PreferenceManager.getInstance(context).drawerListOrder.set(it) }
        }

        @SuppressLint("MissingPermission")
        suspend fun create(context: Context, contents: Int, screenshotBitmap: Bitmap, fileUri: Uri) {
            val idp = LauncherAppState.getIDP(context)
            val createdAt = Timestamp.newBuilder()
                .setSeconds(System.currentTimeMillis() / 1000)
            val colorHints = WallpaperManagerCompat.INSTANCE.get(context).wallpaperColors?.colorHints ?: 0
            val wallpaperSupportsDarkText = (colorHints and WallpaperColorsCompat.HINT_SUPPORTS_DARK_TEXT) != 0
            val info = BackupInfo.newBuilder()
                .setLawnchairVersion(BuildConfig.VERSION_CODE)
                .setBackupVersion(BACKUP_VERSION)
                .setCreatedAt(createdAt)
                .setContents(contents)
                .setGridState(DeviceGridState(idp).toProtoMessage())
                .setPreviewWidth(screenshotBitmap.width)
                .setPreviewHeight(screenshotBitmap.height)
                .setPreviewDarkText(wallpaperSupportsDarkText)
                .build()

            val categoriesJson = if (contents.hasFlag(INCLUDE_CATEGORIES)) {
                buildCategoriesJson(context)
            } else null

            val foldersJson = if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
                buildFoldersJson(context)
            } else null

            val pfd = context.contentResolver.openFileDescriptor(fileUri, "w")!!
            withContext(Dispatchers.IO) {
                pfd.use {
                    ZipOutputStream(FileOutputStream(pfd.fileDescriptor).buffered()).use { out ->
                        out.putNextEntry(ZipEntry(INFO_FILE_NAME))
                        info.writeTo(out)

                        if (contents.hasFlag(INCLUDE_WALLPAPER)) {
                            val wallpaperManager = WallpaperManager.getInstance(context)
                            val wallpaperBitmap = wallpaperManager.drawable?.toBitmap()
                            if (wallpaperBitmap != null) {
                                out.putNextEntry(ZipEntry(WALLPAPER_FILE_NAME))
                                wallpaperBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                        }
                        if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
                            out.putNextEntry(ZipEntry(SCREENSHOT_FILE_NAME))
                            screenshotBitmap.compress(Bitmap.CompressFormat.PNG, 85, out)
                        }

                        if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
                            getFiles(context, forRestore = false).entries.forEach {
                                if (!it.value.exists()) return@forEach
                                out.putNextEntry(ZipEntry(it.key))
                                it.value.inputStream().copyTo(out)
                            }
                        }

                        if (categoriesJson != null) {
                            out.putNextEntry(ZipEntry(CATEGORIES_FILE_NAME))
                            out.write(categoriesJson.toByteArray(Charsets.UTF_8))
                        }

                        if (foldersJson != null) {
                            out.putNextEntry(ZipEntry(FOLDERS_FILE_NAME))
                            out.write(foldersJson.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            }
        }

        private fun launcherDbFile(context: Context, forRestore: Boolean): File {
            val dbName = if (forRestore) RESTORED_DB_FILE_NAME else LauncherAppState.getIDP(context).dbFile
            return context.getDatabasePath(dbName)
        }

        private fun prefsFile(context: Context): File {
            val dir = context.cacheDir.parent
            return File(dir, "shared_prefs/$PREFS_FILE_NAME")
        }

        private fun prefsDbFile(context: Context): File {
            return context.getDatabasePath(PREFS_DB_FILE_NAME)
        }

        private fun prefsDataStoreFile(context: Context): File {
            return File(context.filesDir, "datastore/${PREFS_DATASTORE_FILE_NAME}")
        }
    }
}
