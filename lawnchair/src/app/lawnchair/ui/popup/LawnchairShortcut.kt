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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
                contentPaddings = PaddingValues(bottom = 0.dp),
            ) {
                SelectCategoryDialog(
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
            val isInDrawer = mItemInfo.container == com.android.launcher3.LauncherSettings.Favorites.CONTAINER_ALL_APPS ||
                mItemInfo.container == com.android.launcher3.LauncherSettings.Favorites.CONTAINER_ALL_APPS_PREDICTION
            if (isInDrawer) {
                app.lawnchair.allapps.MultiSelectManager.startMultiSelect(componentKeyString)
            } else {
                // Homescreen / hotseat icon — store workspace item id for removal
                app.lawnchair.allapps.MultiSelectManager.startHomescreenMultiSelect(
                    initialComponentKey = componentKeyString,
                    itemId = mItemInfo.id,
                )
            }
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
fun SelectCategoryDialog(
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

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
            Column(
                modifier = Modifier.padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(id = R.string.select_category_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (appName.isNotBlank()) {
                            Text(
                                text = appName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(id = android.R.string.cancel),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item {
                        val isUnassigned = currentCategory == null
                        CategoryRowItem(
                            label = "No Category",
                            subtitle = if (isUnassigned) "Current" else null,
                            isSelected = isUnassigned,
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
                        CategoryRowItem(
                            label = category.title,
                            subtitle = if (isSelected) "Current ($count apps)" else "$count apps",
                            isSelected = isSelected,
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
    }
}
}

@Composable
fun SelectCategorySheet(
    componentKeyString: String,
    appName: String,
    onClose: () -> Unit,
) {
    SelectCategoryDialog(
        componentKeyString = componentKeyString,
        appName = appName,
        onClose = onClose,
    )
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

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
            Column(
                modifier = Modifier.padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(id = R.string.select_category_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Moving ${selectedKeys.size} apps",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(id = android.R.string.cancel),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item {
                        CategoryRowItem(
                            label = "No Category",
                            subtitle = "Remove",
                            isSelected = false,
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
                        CategoryRowItem(
                            label = category.title,
                            subtitle = "$count apps",
                            isSelected = false,
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
    }
}
}

@Composable
private fun CategoryRowItem(
    label: String,
    subtitle: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val subtitleColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = subtitleColor,
                maxLines = 1,
            )
        }
    }
}

fun addAppsToHomescreen(launcher: LawnchairLauncher, appInfos: List<com.android.launcher3.model.data.AppInfo>) {
    if (appInfos.isEmpty()) return

    launcher.model.enqueueModelUpdateTask { taskController, dataModel, _ ->
        val context = taskController.context
        val idp = com.android.launcher3.InvariantDeviceProfile.INSTANCE.get(context)
        val spaceFinder = com.android.launcher3.model.WorkspaceItemSpaceFinder(dataModel, idp, launcher.model)

        val addedWorkspaceScreens = com.android.launcher3.util.IntArray()
        val addedItems = ArrayList<ItemInfo>()

        synchronized(dataModel) {
            val workspaceScreens = dataModel.itemsIdMap.collectWorkspaceScreens(context)
            val modelWriter = taskController.getModelWriter()

            appInfos.forEach { appInfo ->
                val item = appInfo.makeWorkspaceItem(context)
                val coords = spaceFinder.findSpaceForItem(
                    workspaceScreens,
                    addedWorkspaceScreens,
                    addedItems,
                    item.spanX,
                    item.spanY,
                    context,
                )
                val screenId = coords[0]
                if (screenId != -1) {
                    item.container = com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP
                    item.screenId = screenId
                    item.cellX = coords[1]
                    item.cellY = coords[2]
                    modelWriter.addItemToDatabase(
                        item,
                        com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP,
                        screenId,
                        coords[1],
                        coords[2],
                    )
                    addedItems.add(item)
                }
            }
        }

        if (addedItems.isNotEmpty()) {
            taskController.scheduleCallbackTask { callbacks ->
                callbacks.bindItemsAdded(addedItems)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                launcher.stateManager.goToState(com.android.launcher3.LauncherState.NORMAL)
                Toast.makeText(
                    launcher,
                    if (addedItems.size == 1) launcher.getString(R.string.item_added_to_workspace)
                    else "Added ${addedItems.size} app(s) to home screen",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(launcher, R.string.out_of_space, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
