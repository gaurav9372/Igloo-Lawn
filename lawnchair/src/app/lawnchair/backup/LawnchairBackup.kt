package app.lawnchair.backup

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
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

    suspend fun restore(selectedContents: Int) {
        val handlers = mutableMapOf<String, suspend (InputStream) -> Unit>()
        val contents = selectedContents and info.contents
        if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
            handlers.putAll(
                getFiles(context, forRestore = true).mapValues { entry ->
                    {
                        val file = entry.value
                        file.parentFile?.mkdirs()
                        it.copyTo(file.outputStream())
                    }
                },
            )
        }
        if (contents.hasFlag(INCLUDE_WALLPAPER)) {
            handlers[WALLPAPER_FILE_NAME] = {
                val wallpaperManager = WallpaperManager.getInstance(context)
                wallpaperManager.setBitmap(BitmapFactory.decodeStream(it))
            }
        }
        if (contents.hasFlag(INCLUDE_CATEGORIES)) {
            handlers[CATEGORIES_FILE_NAME] = { inputStream ->
                val jsonStr = inputStream.bufferedReader().readText()
                restoreCategoriesFromJson(context, jsonStr)
            }
        }
        context.getDatabasePath(LAUNCHER_DB_FILE_NAME).parentFile?.deleteRecursively()
        DeviceGridState(info.gridState).writeToPrefs(context, true)
        readZip(handlers)

        var dbController = ModelDbController(context)
        RestoreDbTask.performRestore(context, dbController)
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

        const val INCLUDE_LAYOUT_AND_SETTINGS = 1 shl 0
        const val INCLUDE_WALLPAPER = 1 shl 1
        const val INCLUDE_CATEGORIES = 1 shl 2

        const val MIME_TYPE = "application/zip"
        val EXTRA_MIME_TYPES = arrayOf(MIME_TYPE, "application/x-zip", "application/octet-stream")

        val contentOptions = listOf(
            INCLUDE_LAYOUT_AND_SETTINGS to R.string.backup_content_layout_and_settings,
            INCLUDE_WALLPAPER to R.string.backup_content_wallpaper,
            INCLUDE_CATEGORIES to R.string.backup_content_categories,
        )

        fun generateBackupFileName(): String {
            val fileName = "Lawnchair_Backup ${SimpleDateFormat.getDateTimeInstance().format(Date())}"
            return "$fileName.lawnchairbackup"
        }

        fun getFiles(context: Context, forRestore: Boolean): Map<String, File> {
            return mapOf(
                LAUNCHER_DB_FILE_NAME to launcherDbFile(context, forRestore),
                PREFS_FILE_NAME to prefsFile(context),
                PREFS_DB_FILE_NAME to prefsDbFile(context),
                PREFS_DATASTORE_FILE_NAME to prefsDataStoreFile(context),
            )
        }

        /**
         * Serializes all categories (with their ordered app component keys) to JSON.
         * Format:
         * [
         *   { "rank": 0, "title": "Games", "hide": false, "apps": ["pkg/cls", ...] },
         *   ...
         * ]
         */
        suspend fun buildCategoriesJson(context: Context): String = withContext(Dispatchers.IO) {
            val db = AppDatabase.INSTANCE.get(context)
            db.checkpoint()
            val categoriesWithItems = db.categoryDao().getAllCategoriesWithItems()
            // Read the first (current) snapshot from the flow
            val categoryList = categoriesWithItems.first()
            val arr = JSONArray()
            categoryList.sortedBy { it.category.rank }.forEach { categoryWithItems ->
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
            arr.toString()
        }

        /**
         * Restores categories from a JSON string into the AppDatabase.
         * Clears all existing categories first, then inserts the backed-up ones.
         */
        suspend fun restoreCategoriesFromJson(context: Context, json: String) = withContext(Dispatchers.IO) {
            val db = AppDatabase.INSTANCE.get(context)
            val dao = db.categoryDao()

            // Clear existing categories (cascade deletes items)
            val existingCategories = dao.getAllCategoriesWithItems().first()
            for (existing in existingCategories) {
                dao.deleteCategory(existing.category.id)
            }

            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val rank = obj.optInt("rank", i)
                val title = obj.optString("title", "Category ${i + 1}")
                val hide = obj.optBoolean("hide", false)
                val appsArr = obj.optJSONArray("apps") ?: JSONArray()

                // Insert category and get the new auto-generated ID
                val newCategoryId = dao.insertCategory(
                    CategoryInfoEntity(
                        title = title,
                        hide = hide,
                        rank = rank,
                    ),
                ).toInt()

                // Insert all category items in order
                val items = (0 until appsArr.length()).map { j ->
                    CategoryItemEntity(
                        categoryId = newCategoryId,
                        rank = j,
                        componentKey = appsArr.getString(j),
                    )
                }
                if (items.isNotEmpty()) {
                    dao.insertCategoryItems(items)
                }
            }
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

            // Pre-serialize categories before opening the output stream
            val categoriesJson = if (contents.hasFlag(INCLUDE_CATEGORIES)) {
                buildCategoriesJson(context)
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

                        getFiles(context, forRestore = false).entries.forEach {
                            if (!it.value.exists()) return@forEach
                            out.putNextEntry(ZipEntry(it.key))
                            it.value.inputStream().copyTo(out)
                        }

                        if (categoriesJson != null) {
                            out.putNextEntry(ZipEntry(CATEGORIES_FILE_NAME))
                            out.write(categoriesJson.toByteArray(Charsets.UTF_8))
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
