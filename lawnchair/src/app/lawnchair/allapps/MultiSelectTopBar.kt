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
import com.android.launcher3.util.ApplicationInfoWrapper
import com.android.launcher3.util.ComponentKey

@Composable
fun MultiSelectTopBar(
    launcher: LawnchairLauncher,
    modifier: Modifier = Modifier,
) {
    val selectedKeys by MultiSelectManager.selectedComponentKeys.collectAsStateWithLifecycle()
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Edit Category Action
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
                        tint = if (selectedKeys.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }

                // Uninstall Action
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
                        tint = if (selectedKeys.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
                    )
                }
            }
        }
    }
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
