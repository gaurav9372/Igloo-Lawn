package app.lawnchair.deck

import android.content.Context
import app.lawnchair.LawnchairLauncher
import app.lawnchair.backup.BackupState
import app.lawnchair.flowerpot.Flowerpot
import app.lawnchair.gestures.config.GestureHandlerConfig
import app.lawnchair.launcher
import app.lawnchair.launcherNullable
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.util.categorizeAppsWithSystemAndGoogle
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings
import com.android.launcher3.model.ItemInstallQueue
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.util.ApplicationInfoWrapper
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.PackageManagerHelper
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class LawndeckManager(private val context: Context) {

    private val launcher get() = context.launcherNullable ?: LawnchairLauncher.instance

    suspend fun enableLawndeck(
        onProgress: ((String) -> Unit)? = null,
    ) = BackupState.mutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            val previousMode = readMode()
            if (previousMode.enabled) return@withContext
            // The return layout belongs to this toggle, not the first-ever Lawndeck session.
            createBackup("bk")
            try {
                if (backupExists("lawndeck")) {
                    onProgress?.invoke("Restoring previous layout...")
                    restoreBackup("lawndeck")
                } else {
                    val completed = CompletableDeferred<Unit>()
                    onProgress?.invoke("Categorizing apps...")
                    addAllAppsToWorkspace(onProgress) { completed.complete(Unit) }
                    withTimeout(30_000) { completed.await() }
                }
                applyMode(ModeState(true, GestureHandlerConfig.NoOp, true))
            } catch (failure: Throwable) {
                rollback("bk", previousMode, failure)
                throw failure
            }
        }
    }

    suspend fun disableLawndeck() = BackupState.mutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            val previousMode = readMode()
            if (!previousMode.enabled) return@withContext
            check(backupExists("bk")) { "The original workspace snapshot is missing" }
            createBackup("lawndeck")
            try {
                restoreBackup("bk")
                applyMode(previousMode.copy(enabled = false, gesture = GestureHandlerConfig.OpenAppDrawer))
            } catch (failure: Throwable) {
                rollback("lawndeck", previousMode, failure)
                throw failure
            }
        }
    }

    private data class ModeState(val enabled: Boolean, val gesture: GestureHandlerConfig, val addIcons: Boolean)

    private suspend fun readMode(): ModeState {
        val prefs2 = PreferenceManager2.getInstance(context)
        return ModeState(
            prefs2.deckLayout.get().first(),
            prefs2.swipeUpGestureHandler.get().first(),
            PreferenceManager.getInstance(context).addIconToHome.get(),
        )
    }

    private suspend fun applyMode(state: ModeState) = withContext(Dispatchers.Main) {
        val prefs2 = PreferenceManager2.getInstance(context)
        prefs2.swipeUpGestureHandler.set(state.gesture)
        PreferenceManager.getInstance(context).addIconToHome.set(state.addIcons)
        prefs2.deckLayout.set(state.enabled)
    }

    private suspend fun rollback(suffix: String, mode: ModeState, failure: Throwable) {
        try {
            restoreBackup(suffix)
        } catch (rollbackFailure: Throwable) {
            failure.addSuppressed(rollbackFailure)
        }
        try {
            applyMode(mode)
        } catch (rollbackFailure: Throwable) {
            failure.addSuppressed(rollbackFailure)
        }
    }

    private suspend fun createBackup(suffix: String) {
        val model = LauncherAppState.getInstance(context).model
        BackupState.onModelThread {
            model.modelDbController.writeSnapshot(backupFile(suffix))
        }
    }

    private suspend fun restoreBackup(suffix: String) {
        val model = LauncherAppState.getInstance(context).model
        BackupState.onModelThread {
            val snapshot = ModelDbController.openSnapshot(context, backupFile(suffix))
            try {
                // This is a same-device layout switch: preserve profiles and widget bindings.
                model.modelDbController.replaceFavoritesFrom(snapshot)
            } finally {
                snapshot.closeSnapshot()
            }
        }
        withContext(Dispatchers.Main) { model.forceReload() }
    }

    private fun backupFile(suffix: String): File {
        val idp = InvariantDeviceProfile.INSTANCE.get(context)
        val dbFile = context.getDatabasePath(idp.dbFile)
        return File(dbFile.parent, "${suffix}_${idp.dbFile}")
    }

    private fun backupExists(suffix: String): Boolean = backupFile(suffix).exists()

    private fun addAllAppsToWorkspace(
        onProgress: ((String) -> Unit)?,
        onComplete: (() -> Unit)?,
    ) {
        val apps = checkNotNull(launcher?.mAppsView?.appsStore?.apps) { "Launcher is not ready" }
        if (apps.isEmpty()) {
            onComplete?.invoke()
            return
        }

        onProgress?.invoke("Categorizing apps...")

        val validApps = apps.mapNotNull { it as? AppInfo }
        val finalCategorizedApps = categorizeAppsWithSystemAndGoogle(validApps, context)

        onProgress?.invoke("Adding apps to workspace...")

        val launcher = checkNotNull(this.launcher) { "Launcher is not ready" }
        val model = launcher.model

        // Collect folders to add and count single apps
        val foldersToAdd = mutableListOf<FolderInfo>()
        val singleApps = mutableListOf<WorkspaceItemInfo>()

        // Process each category
        finalCategorizedApps.forEach { (category, categoryApps) ->
            if (categoryApps.isEmpty()) return@forEach

            if (categoryApps.size == 1) {
                // Single app - add directly to workspace
                val app = categoryApps.first()
                app.makeWorkspaceItem(context)?.let(singleApps::add)
            } else {
                // Multiple apps - create folder
                onProgress?.invoke("Creating folder: $category...")
                val folderInfo = createFolderInfo(category, categoryApps)
                if (folderInfo != null) {
                    foldersToAdd.add(folderInfo)
                }
            }
        }

        model.enqueueModelUpdateTask(AddFoldersWithItemsTask(foldersToAdd, singleApps, onComplete))
    }

    /**
     * Adds a newly installed app to the workspace with proper categorization.
     * This is called when a new app is installed and deck layout is enabled.
     *
     * @param packageName The package name of the newly installed app
     * @param user The user handle for the app
     * @param modelWriter The ModelWriter to use for database operations (must be called from model thread)
     * @param dataModel The BgDataModel to search for existing folders
     */
    fun addNewlyInstalledApp(
        packageName: String,
        user: android.os.UserHandle,
        modelWriter: com.android.launcher3.model.ModelWriter,
        dataModel: com.android.launcher3.model.BgDataModel,
    ) {
        // Get app info from LauncherApps directly (app might not be in all apps list yet)
        val launcherApps = context.getSystemService(android.content.pm.LauncherApps::class.java)
            ?: return
        val activities = launcherApps.getActivityList(packageName, user)
        if (activities.isEmpty()) return

        val activityInfo = activities[0]
        val appInfo = AppInfo(context, activityInfo, user)

        val intent = appInfo.intent

        // Determine category: Google Apps > System Apps > Flowerpot categories
        val category = when {
            packageName.startsWith("com.google.") -> "Google Apps"

            intent != null && ApplicationInfoWrapper(context, intent).isSystem() -> "System Apps"

            else -> {
                // Use flowerpot to categorize the app
                val potsManager = Flowerpot.Manager.getInstance(context)
                val categorizedApps = potsManager.categorizeApps(listOf(appInfo))

                if (categorizedApps.isEmpty()) {
                    // No category found, add directly to workspace
                    ItemInstallQueue.INSTANCE.get(context).queueItem(packageName, user)
                    return
                }

                // Get the category from flowerpot
                categorizedApps.entries.firstOrNull()?.key ?: run {
                    ItemInstallQueue.INSTANCE.get(context).queueItem(packageName, user)
                    return
                }
            }
        }

        // Check if there's already a folder for this category on workspace
        val existingFolder = findFolderByCategory(category, dataModel)

        if (existingFolder != null) {
            // Add app to existing folder
            val workspaceItem = appInfo.makeWorkspaceItem(context) ?: return
            existingFolder.add(workspaceItem)
            // Update folder in database
            modelWriter.addOrMoveItemInDatabase(
                workspaceItem,
                existingFolder.id,
                0,
                existingFolder.getContents().size % 4,
                existingFolder.getContents().size / 4,
            )
        } else {
            // Single app in category, add directly to workspace
            // The app will be categorized properly when added
            ItemInstallQueue.INSTANCE.get(context).queueItem(packageName, user)
        }
    }

    private fun findFolderByCategory(category: String, dataModel: com.android.launcher3.model.BgDataModel): FolderInfo? {
        // Search through workspace items to find folder with matching category name
        synchronized(dataModel) {
            dataModel.itemsIdMap.forEach { item ->
                if (item is FolderInfo && item.title?.toString() == category) {
                    return item
                }
            }
        }
        return null
    }

    private fun createFolderInfo(categoryName: String, apps: List<AppInfo>): FolderInfo? {
        if (apps.isEmpty()) return null

        val folderInfo = FolderInfo().apply {
            title = categoryName
        }

        // Create workspace items for each app and add to folder
        apps.forEach { app ->
            val workspaceItem = app.makeWorkspaceItem(context) ?: return@forEach
            folderInfo.add(workspaceItem)
        }

        // Only return folder if it has items
        return if (folderInfo.getContents().isNotEmpty()) folderInfo else null
    }
}
