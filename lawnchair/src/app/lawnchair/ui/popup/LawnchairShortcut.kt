package app.lawnchair.ui.popup

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.AppGlobals
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.SuspendDialogInfo
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import android.os.UserHandle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.LawnchairLauncher
import app.lawnchair.data.category.service.CategoryService
import app.lawnchair.override.CustomizeAppDialog
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.views.ComposeBottomSheet
import kotlinx.coroutines.launch
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_TASK
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.graphics.ThemeManager
import com.android.launcher3.icons.LauncherIcons
import com.android.launcher3.model.data.AppInfo as ModelAppInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.popup.SystemShortcut
import com.android.launcher3.util.ApplicationInfoWrapper
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.PackageManagerHelper
import com.android.launcher3.views.ActivityContext
import java.net.URISyntaxException

class LawnchairShortcut {

    companion object {

        val CUSTOMIZE =
            SystemShortcut.Factory { activity: LawnchairLauncher, itemInfo, originalView ->
                val prefs2 = PreferenceManager2.getInstance(activity)
                if (prefs2.lockHomeScreen.firstCached()) {
                    null
                } else {
                    getAppInfo(activity, itemInfo)?.let { Customize(activity, it, itemInfo, originalView) }
                }
            }

        val EDIT_CATEGORY =
            SystemShortcut.Factory { activity: LawnchairLauncher, itemInfo, originalView ->
                val prefs2 = PreferenceManager2.getInstance(activity)
                if (prefs2.lockHomeScreen.firstCached()) {
                    null
                } else {
                    getAppInfo(activity, itemInfo)?.let { EditCategory(activity, it, itemInfo, originalView) }
                }
            }

        val MULTI_SELECT =
            SystemShortcut.Factory { activity: LawnchairLauncher, itemInfo, originalView ->
                val prefs2 = PreferenceManager2.getInstance(activity)
                if (prefs2.lockHomeScreen.firstCached()) {
                    null
                } else {
                    getAppInfo(activity, itemInfo)?.let { MultiSelect(activity, it, itemInfo, originalView) }
                }
            }

        val ADD_TO_HOMESCREEN =
            SystemShortcut.Factory { activity: LawnchairLauncher, itemInfo, originalView ->
                val prefs2 = PreferenceManager2.getInstance(activity)
                if (prefs2.lockHomeScreen.firstCached()) {
                    null
                } else {
                    getAppInfo(activity, itemInfo)?.let { AddToHomescreen(activity, it, itemInfo, originalView) }
                }
            }

        private fun getAppInfo(launcher: LawnchairLauncher, itemInfo: ItemInfo): ModelAppInfo? {
            if (itemInfo is ModelAppInfo) return itemInfo
            if (itemInfo.itemType != ITEM_TYPE_APPLICATION) return null
            val key = ComponentKey(itemInfo.targetComponent, itemInfo.user)
            return launcher.appsView.appsStore.getApp(key)
        }

        val UNINSTALL =
            SystemShortcut.Factory { activity: ActivityContext, itemInfo: ItemInfo, view: View ->
                val prefs2 = PreferenceManager2.INSTANCE.get(activity.asContext())
                if (prefs2.lockHomeScreen.firstCached()) {
                    return@Factory null
                }
                if (itemInfo.targetComponent == null) {
                    return@Factory null
                }
                if (ApplicationInfoWrapper(
                        activity.asContext(),
                        itemInfo.targetComponent!!.packageName,
                        itemInfo.user,
                    ).isSystem()
                ) {
                    return@Factory null
                }
                UnInstall(activity, itemInfo, view)
            }

        private val SUPPORTED_STORES = setOf(
            "com.android.vending",
            "com.aurora.store",
            "org.fdroid.fdroid",
            "org.gdroid.gdroid",
            "com.looker.droidify",
            "com.github.librecaptcha.apps.fdroidclient",
        )

        val OPEN_IN_STORE =
            SystemShortcut.Factory { activity: ActivityContext, itemInfo: ItemInfo, originalView: View ->
                if (itemInfo.itemType != ITEM_TYPE_APPLICATION) return@Factory null
                val packageName = itemInfo.targetComponent?.packageName ?: return@Factory null
                val context = activity.asContext()
                val installer = PackageManagerHelper.INSTANCE.get(context)
                    .getAppInstallerPackage(packageName) ?: return@Factory null
                if (installer !in SUPPORTED_STORES) return@Factory null
                OpenInStore(activity, itemInfo, originalView, packageName, installer)
            }

        val PAUSE_APPS = SystemShortcut.Factory { activity: LawnchairLauncher, itemInfo: ItemInfo, originalView: View ->
            val targetCmp = itemInfo.targetComponent
            val packageName = targetCmp?.packageName ?: return@Factory null

            if (ApplicationInfoWrapper(
                    activity.asContext(),
                    packageName,
                    itemInfo.user,
                ).isSuspended()
            ) {
                return@Factory null
            }

            PauseApps(activity, itemInfo, originalView)
        }
    }

    class Customize(
        private val launcher: LawnchairLauncher,
        private val appInfo: ModelAppInfo,
        itemInfo: ItemInfo,
        originalView: View,
    ) : SystemShortcut<LawnchairLauncher>(R.drawable.ic_edit, R.string.action_customize, launcher, itemInfo, originalView) {

        override fun onClick(v: View) {
            val outObj = Array<Any?>(1) { null }
            var icon = Utilities.loadFullDrawableWithoutTheme(launcher, appInfo, 0, 0, outObj)
            if (mItemInfo.screenId != NO_ID && Utilities.ATLEAST_T) {
                val adaptiveIcon = icon as? AdaptiveIconDrawable
                    ?: LauncherIcons.obtain(launcher).use { it.wrapToAdaptiveIcon(icon) }
                if (adaptiveIcon != null) {
                    val themeController = ThemeManager.INSTANCE.get(launcher).themeController
                    themeController?.createThemedAdaptiveIcon(
                        launcher,
                        adaptiveIcon,
                        appInfo.bitmap,
                    )?.let {
                        icon = it
                    }
                }
            }
            val launcherActivityInfo = outObj[0] as LauncherActivityInfo?
            if (launcherActivityInfo != null) {
                val defaultTitle = launcherActivityInfo.label.toString()

                AbstractFloatingView.closeAllOpenViews(launcher)
                ComposeBottomSheet.show(
                    context = launcher,
                    contentPaddings = PaddingValues(bottom = 64.dp),
                ) {
                    CustomizeAppDialog(
                        icon = icon,
                        defaultTitle = defaultTitle,
                        componentKey = appInfo.toComponentKey(),
                    ) { close(true) }
                }
            } else {
                Toast.makeText(launcher, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
                AbstractFloatingView.closeAllOpenViews(launcher)
            }
        }
    }

    class EditCategory(
        private val launcher: LawnchairLauncher,
        private val appInfo: ModelAppInfo,
        itemInfo: ItemInfo,
        originalView: View,
    ) : SystemShortcut<LawnchairLauncher>(R.drawable.ic_setting, R.string.edit_category, launcher, itemInfo, originalView) {

        override fun onClick(v: View) {
            AbstractFloatingView.closeAllOpenViews(launcher)
            val componentKeyString = appInfo.toComponentKey().toString()
            val appName = appInfo.title?.toString() ?: ""

            ComposeBottomSheet.show(
                context = launcher,
                contentPaddings = PaddingValues(bottom = 32.dp),
            ) {
                SelectCategorySheet(
                    componentKeyString = componentKeyString,
                    appName = appName,
                    onClose = { close(true) },
                )
            }
        }
    }

    class MultiSelect(
        private val launcher: LawnchairLauncher,
        private val appInfo: ModelAppInfo,
        itemInfo: ItemInfo,
        originalView: View,
    ) : SystemShortcut<LawnchairLauncher>(R.drawable.ic_touch, R.string.multi_select, launcher, itemInfo, originalView) {

        override fun onClick(v: View) {
            AbstractFloatingView.closeAllOpenViews(launcher)
            val componentKeyString = appInfo.toComponentKey().toString()
            app.lawnchair.allapps.MultiSelectManager.startMultiSelect(componentKeyString)
            launcher.appsView?.getActiveRecyclerView()?.invalidate()
        }
    }

    class AddToHomescreen(
        private val launcher: LawnchairLauncher,
        private val appInfo: ModelAppInfo,
        itemInfo: ItemInfo,
        originalView: View,
    ) : SystemShortcut<LawnchairLauncher>(R.drawable.ic_home, R.string.add_to_home_screen, launcher, itemInfo, originalView) {

        override fun onClick(v: View) {
            AbstractFloatingView.closeAllOpenViews(launcher)
            addAppsToHomescreen(launcher, listOf(appInfo))
        }
    }

    class PauseApps(
        target: LawnchairLauncher,
        itemInfo: ItemInfo,
        originalView: View,
    ) : SystemShortcut<LawnchairLauncher>(
        R.drawable.ic_hourglass_top,
        R.string.paused_apps_drop_target_label,
        target,
        itemInfo,
        originalView,
    ) {
        @SuppressLint("NewApi")
        override fun onClick(view: View) {
            val context = view.context
            val appLabel = ApplicationInfoWrapper(
                context,
                mItemInfo.targetComponent?.packageName ?: "",
                mItemInfo.user,
            ).toString()
            AlertDialog.Builder(context)
                .setIcon(R.drawable.ic_hourglass_top)
                .setTitle(context.getString(R.string.pause_apps_dialog_title, appLabel))
                .setMessage(context.getString(R.string.pause_apps_dialog_message, appLabel))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.pause) { _, _ ->
                    try {
                        AppGlobals.getPackageManager().setPackagesSuspendedAsUser(
                            arrayOf(mItemInfo.targetComponent?.packageName ?: ""),
                            true, null, null,
                            SuspendDialogInfo.Builder()
                                .setIcon(R.drawable.ic_hourglass_top)
                                .setTitle(R.string.paused_apps_dialog_title)
                                .setMessage(R.string.paused_apps_dialog_message)
                                .setNeutralButtonAction(SuspendDialogInfo.BUTTON_ACTION_UNSUSPEND)
                                .build(),
                            0,
                            context.opPackageName,
                            context.userId,
                            mItemInfo.user.identifier,
                        )
                    } catch (e: Throwable) {
                        Log.e("LawnchairShortcut", "Failed to pause app", e)
                    }
                }
                .show()
            AbstractFloatingView.closeAllOpenViews(mTarget)
        }
    }

    class UnInstall(private var target: ActivityContext?, private var itemInfo: ItemInfo?, originalView: View?) :
        SystemShortcut<ActivityContext>(
            R.drawable.ic_uninstall_no_shadow,
            R.string.uninstall_drop_target_label,
            target,
            itemInfo,
            originalView,
        ) {

        /**
         * @return the component name that should be uninstalled or null.
         */
        private fun getUninstallTarget(item: ItemInfo?, context: Context): ComponentName? {
            var intent: Intent? = null
            var user: UserHandle? = null
            if (item != null &&
                (item.itemType == ITEM_TYPE_APPLICATION || item.itemType == ITEM_TYPE_TASK)
            ) {
                intent = item.intent
                user = item.user
            }
            if (intent != null) {
                val info: LauncherActivityInfo? =
                    context.getSystemService(LauncherApps::class.java)
                        ?.resolveActivity(intent, user)
                if (info != null && (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                    return info.componentName
                }
            }
            return null
        }

        override fun onClick(view: View) {
            val cn = getUninstallTarget(itemInfo, view.context)
            if (cn == null) {
                // System applications cannot be installed. For now, show a toast explaining that.
                // We may give them the option of disabling apps this way.
                Toast.makeText(
                    view.context,
                    R.string.uninstall_system_app_text,
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            try {
                val intent = Intent.parseUri(
                    view.context.getString(R.string.delete_package_intent),
                    0,
                )
                    .setData(
                        Uri.fromParts(
                            "package",
                            itemInfo?.targetComponent?.packageName,
                            itemInfo?.targetComponent?.className,
                        ),
                    )
                    .putExtra(Intent.EXTRA_USER, itemInfo?.user)
                target?.startActivitySafely(view, intent, itemInfo)
                AbstractFloatingView.closeAllOpenViews(target)
            } catch (e: URISyntaxException) {
                // Do nothing.
            }
        }
    }

    class OpenInStore(
        target: ActivityContext,
        itemInfo: ItemInfo,
        originalView: View,
        private val packageName: String,
        private val installerPackage: String,
    ) : SystemShortcut<ActivityContext>(
        R.drawable.ic_open_in_store,
        R.string.open_in_store_drop_target_label,
        target,
        itemInfo,
        originalView,
    ) {
        override fun onClick(v: View) {
            dismissTaskMenuView()
            val intent = buildIntent() ?: return
            mTarget.startActivitySafely(v, intent, mItemInfo)
        }

        private fun buildIntent(): Intent? {
            val uri = when (installerPackage) {
                "com.android.vending",
                "org.gdroid.gdroid",
                "com.aurora.store",
                -> "market://details?id=$packageName"

                "org.fdroid.fdroid" -> "https://f-droid.org/packages/$packageName/"

                "com.github.librecaptcha.apps.fdroidclient",
                "com.looker.droidify",
                -> "droidify://details?id=$packageName"

                else -> return null
            }
            return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(installerPackage)
        }
    }
}

@Composable
fun SelectCategorySheet(
    componentKeyString: String,
    appName: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val categoryService = remember { CategoryService.INSTANCE.get(context) }
    val categories by categoryService.getCategoriesFlow().collectAsStateWithLifecycle(initialValue = emptyList())

    val currentCategory = remember(categories, componentKeyString) {
        categories.find { it.itemComponentKeys.contains(componentKeyString) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(id = R.string.select_category_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (appName.isNotBlank()) {
            Text(
                text = appName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        LazyColumn {
            item {
                val isUnassigned = currentCategory == null
                ClickablePreference(
                    label = "No Category",
                    subtitle = if (isUnassigned) "Current selection" else null,
                    onClick = {
                        scope.launch {
                            categoryService.moveAppToCategory(componentKeyString, 0)
                            Toast.makeText(context, "Moved to No Category", Toast.LENGTH_SHORT).show()
                            onClose()
                        }
                    },
                )
            }
            items(categories) { category ->
                val isSelected = currentCategory?.id == category.id
                val count = category.itemComponentKeys.size
                ClickablePreference(
                    label = category.title,
                    subtitle = if (isSelected) "Current category ($count apps)" else "$count apps",
                    onClick = {
                        scope.launch {
                            categoryService.moveAppToCategory(componentKeyString, category.id)
                            Toast.makeText(context, "Moved to ${category.title}", Toast.LENGTH_SHORT).show()
                            onClose()
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun BatchSelectCategorySheet(
    selectedKeys: List<String>,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val categoryService = remember { CategoryService.INSTANCE.get(context) }
    val categories by categoryService.getCategoriesFlow().collectAsStateWithLifecycle(initialValue = emptyList())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(id = R.string.select_category_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = "Moving ${selectedKeys.size} apps",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        LazyColumn {
            item {
                ClickablePreference(
                    label = "No Category",
                    subtitle = "Remove from categories",
                    onClick = {
                        scope.launch {
                            categoryService.moveAppsToCategory(selectedKeys, 0)
                            Toast.makeText(context, "Moved ${selectedKeys.size} apps to No Category", Toast.LENGTH_SHORT).show()
                            onClose()
                        }
                    },
                )
            }
            items(categories) { category ->
                val count = category.itemComponentKeys.size
                ClickablePreference(
                    label = category.title,
                    subtitle = "$count apps currently",
                    onClick = {
                        scope.launch {
                            categoryService.moveAppsToCategory(selectedKeys, category.id)
                            Toast.makeText(context, "Moved ${selectedKeys.size} apps to ${category.title}", Toast.LENGTH_SHORT).show()
                            onClose()
                        }
                    },
                )
            }
        }
    }
}

fun addAppsToHomescreen(launcher: LawnchairLauncher, appInfos: List<com.android.launcher3.model.data.AppInfo>) {
    if (appInfos.isEmpty()) return

    val dataModel = launcher.model.bgDataModel
    val workspaceScreens = synchronized(dataModel) {
        dataModel.itemsIdMap.collectWorkspaceScreens(launcher)
    }
    val addedWorkspaceScreens = com.android.launcher3.util.IntArray()
    val addedItems = ArrayList<ItemInfo>()
    val spaceFinder = com.android.launcher3.model.WorkspaceItemSpaceFinder(
        dataModel,
        launcher.deviceProfile.inv,
        launcher.model,
    )

    appInfos.forEach { appInfo ->
        val item = appInfo.makeWorkspaceItem(launcher)
        val coords = spaceFinder.findSpaceForItem(
            workspaceScreens,
            addedWorkspaceScreens,
            addedItems,
            item.spanX,
            item.spanY,
            launcher,
        )
        val screenId = coords[0]
        if (screenId != -1) {
            item.container = com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP
            item.screenId = screenId
            item.cellX = coords[1]
            item.cellY = coords[2]
            launcher.modelWriter.addItemToDatabase(
                item,
                com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP,
                screenId,
                coords[1],
                coords[2],
            )
            addedItems.add(item)
        }
    }

    if (addedItems.isNotEmpty()) {
        launcher.bindItems(addedItems, true)
        launcher.stateManager.goToState(com.android.launcher3.LauncherState.NORMAL)
        Toast.makeText(
            launcher,
            if (addedItems.size == 1) launcher.getString(R.string.item_added_to_workspace)
            else "Added ${addedItems.size} app(s) to home screen",
            Toast.LENGTH_SHORT,
        ).show()
    } else {
        Toast.makeText(launcher, R.string.out_of_space, Toast.LENGTH_SHORT).show()
    }
}
