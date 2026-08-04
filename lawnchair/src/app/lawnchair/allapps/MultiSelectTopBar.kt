package app.lawnchair.allapps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.LawnchairLauncher
import app.lawnchair.ui.popup.BatchSelectCategorySheet
import app.lawnchair.views.ComposeBottomSheet
import com.android.launcher3.R
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.util.ApplicationInfoWrapper
import com.android.launcher3.util.ComponentKey

@Composable
fun MultiSelectTopBar(
    launcher: LawnchairLauncher,
    modifier: Modifier = Modifier,
) {
    val selectedKeys by MultiSelectManager.selectedComponentKeys.collectAsStateWithLifecycle()
    val isHomescreenMode by MultiSelectManager.isHomescreenMode.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: close + count
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { MultiSelectManager.exitMultiSelect() },
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${selectedKeys.size} selected",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Right: actions — depend on context
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isHomescreenMode) {
                    // ── Homescreen mode: only Remove icon ──
                    IconButton(
                        onClick = {
                            if (selectedKeys.isEmpty()) {
                                Toast.makeText(context, "No apps selected", Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            removeSelectedFromHomescreen(launcher, selectedKeys)
                        },
                        enabled = selectedKeys.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "Remove from homescreen",
                            tint = if (selectedKeys.isNotEmpty())
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
                        )
                    }
                } else {
                    // ── App drawer mode: Add to Homescreen, Edit Category, Uninstall ──

                    // Add to Homescreen
                    IconButton(
                        onClick = {
                            if (selectedKeys.isEmpty()) {
                                Toast.makeText(context, "No apps selected", Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            addSelectedAppsToHomescreen(launcher, selectedKeys)
                        },
                        enabled = selectedKeys.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Rounded.Home,
                            contentDescription = "Add to Homescreen",
                            tint = if (selectedKeys.isNotEmpty())
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }

                    // Edit Category
                    IconButton(
                        onClick = {
                            if (selectedKeys.isEmpty()) {
                                Toast.makeText(context, "No apps selected", Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            ComposeBottomSheet.show(
                                context = launcher,
                                contentPaddings = PaddingValues(bottom = 32.dp),
                            ) {
                                BatchSelectCategorySheet(
                                    selectedKeys = selectedKeys.toList(),
                                    onClose = {
                                        close(true)
                                        MultiSelectManager.exitMultiSelect()
                                    },
                                )
                            }
                        },
                        enabled = selectedKeys.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = "Edit Category",
                            tint = if (selectedKeys.isNotEmpty())
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }

                    // Uninstall
                    IconButton(
                        onClick = {
                            if (selectedKeys.isEmpty()) {
                                Toast.makeText(context, "No apps selected", Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            uninstallSelectedApps(context, selectedKeys)
                        },
                        enabled = selectedKeys.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "Uninstall",
                            tint = if (selectedKeys.isNotEmpty())
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Removes the selected workspace icons from the homescreen (does not uninstall).
 * Uses modelWriter.deleteItemFromDatabase for each selected item id.
 */
private fun removeSelectedFromHomescreen(launcher: LawnchairLauncher, selectedKeys: Set<String>) {
    val itemIds = MultiSelectManager.selectedItemIds.value
    var removed = 0
    launcher.modelWriter.prepareToUndoDelete()
    itemIds.forEach { (_, itemId) ->
        val view = launcher.workspace.getViewByItemId(itemId)
        val itemInfo = view?.tag as? com.android.launcher3.model.data.ItemInfo
        if (itemInfo != null) {
            launcher.workspace.removeWorkspaceItem(view)
            launcher.modelWriter.deleteItemFromDatabase(itemInfo, "user removed via multi-select")
            removed++
        }
    }
    if (removed > 0) {
        launcher.modelWriter.commitDelete()
        Toast.makeText(
            launcher,
            if (removed == 1) launcher.getString(R.string.item_removed) else "Removed $removed icons",
            Toast.LENGTH_SHORT,
        ).show()
    }
    MultiSelectManager.exitMultiSelect()
}

private fun addSelectedAppsToHomescreen(launcher: LawnchairLauncher, selectedKeys: Set<String>) {
    val appInfos = selectedKeys.mapNotNull { keyStr ->
        val componentKey = ComponentKey.fromString(keyStr) ?: return@mapNotNull null
        launcher.appsView?.appsStore?.getApp(componentKey)
    }
    if (appInfos.isEmpty()) {
        Toast.makeText(launcher, "No apps selected", Toast.LENGTH_SHORT).show()
        return
    }
    app.lawnchair.ui.popup.addAppsToHomescreen(launcher, appInfos)
    MultiSelectManager.exitMultiSelect()
}

private fun uninstallSelectedApps(context: Context, selectedKeys: Set<String>) {
    val uninstalledPackages = mutableSetOf<String>()
    selectedKeys.forEach { keyStr ->
        val componentKey = ComponentKey.fromString(keyStr) ?: return@forEach
        val packageName = componentKey.componentName.packageName
        if (!uninstalledPackages.contains(packageName) && !ApplicationInfoWrapper(context, packageName, componentKey.user).isSystem()) {
            uninstalledPackages.add(packageName)
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
    MultiSelectManager.exitMultiSelect()
}
