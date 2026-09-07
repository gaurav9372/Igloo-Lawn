package app.lawnchair.ui.preferences.destinations

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lawnchair.data.category.CategoryAppCountHelper
import app.lawnchair.data.category.CategoryEntry
import app.lawnchair.data.category.model.CategoryViewModel
import app.lawnchair.data.folder.FolderEntry
import app.lawnchair.data.folder.model.FolderViewModel
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.util.appsState
import app.lawnchair.ui.ModalBottomSheetContent
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.LoadingScreen
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.components.reorderable.ReorderableDragHandle
import app.lawnchair.ui.preferences.components.reorderable.ReorderablePreferenceGroup
import app.lawnchair.ui.preferences.navigation.AppDrawerAppListToCategory
import app.lawnchair.ui.preferences.navigation.AppDrawerCategoryDetail
import app.lawnchair.ui.util.bottomSheetHandler
import com.android.launcher3.R

@Composable
fun AppDrawerCategoriesPreference(
    modifier: Modifier = Modifier,
    viewModel: CategoryViewModel = viewModel(),
    folderViewModel: FolderViewModel = viewModel(),
) {
    val navController = LocalNavController.current
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val folders by folderViewModel.folders.collectAsStateWithLifecycle()

    AppDrawerCategoriesPreference(
        modifier = modifier,
        categories = categories,
        folders = folders,
        onCreateCategory = { label ->
            viewModel.createCategory(label)
        },
        onOpenCategoryDetail = { categoryId ->
            navController.navigate(AppDrawerCategoryDetail(categoryId))
        },
        onRenameCategory = { categoryId, newTitle ->
            viewModel.renameCategory(categoryId, newTitle)
        },
        onDeleteCategory = {
            viewModel.deleteCategory(it.id)
        },
        onReorderCategories = { orderedIds ->
            viewModel.reorderCategories(orderedIds)
        },
    )
}

const val NO_CATEGORY_ID = CategoryAppCountHelper.NO_CATEGORY_ID

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppDrawerCategoriesPreference(
    categories: List<CategoryEntry>?,
    folders: List<FolderEntry>? = null,
    onCreateCategory: (String) -> Unit,
    onOpenCategoryDetail: (Int) -> Unit,
    onRenameCategory: (Int, String) -> Unit,
    onDeleteCategory: (CategoryEntry) -> Unit,
    onReorderCategories: (List<Int>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs2 = remember { PreferenceManager2.getInstance(context) }
    val apps by appsState()
    val hiddenApps by prefs2.hiddenApps.getAdapter().state
    val orderString by prefs2.categoryOrder.getAdapter().state
    val bottomSheetHandler = bottomSheetHandler

    val mainProfileApps = remember(apps) {
        apps.filter { it.key.user == android.os.Process.myUserHandle() }
    }
    val eligibleAppKeys = remember(mainProfileApps, hiddenApps) {
        CategoryAppCountHelper.getEligibleAppKeys(
            mainProfileApps.map { it.key.toString() },
            hiddenApps,
        )
    }
    val allUserClaimedKeys = remember(categories, folders) {
        CategoryAppCountHelper.getAllUserClaimedKeys(categories, folders)
    }
    val unassignedKeys = remember(eligibleAppKeys, allUserClaimedKeys) {
        CategoryAppCountHelper.getUnassignedKeys(eligibleAppKeys, allUserClaimedKeys)
    }
    val noCategoryEntry = remember(unassignedKeys) {
        CategoryEntry(id = NO_CATEGORY_ID, title = "No Category", itemComponentKeys = unassignedKeys)
    }

    val categoryCounts = remember(categories, folders, eligibleAppKeys, unassignedKeys) {
        CategoryAppCountHelper.computeCategoryCounts(categories, folders, eligibleAppKeys, unassignedKeys)
    }

    val displayList = remember(categories, noCategoryEntry, orderString) {
        if (categories == null) emptyList()
        else {
            val allList = categories + noCategoryEntry
            if (orderString.isBlank()) {
                allList
            } else {
                val orderList = orderString.split(",").mapNotNull { it.toIntOrNull() }
                allList.sortedBy { entry ->
                    val idx = orderList.indexOf(entry.id)
                    if (idx != -1) idx else Int.MAX_VALUE
                }
            }
        }
    }
    var categoryToDeletePending by remember { mutableStateOf<CategoryEntry?>(null) }

    LoadingScreen(
        isLoading = categories == null || folders == null,
        modifier = modifier.fillMaxWidth(),
    ) {
        PreferenceLayout(
            label = stringResource(id = R.string.categories_label),
            backArrowVisible = true,
        ) {
            PreferenceGroup(heading = stringResource(R.string.categories_label)) {
                PreferenceTemplate(
                    title = {
                        Text(
                            text = stringResource(R.string.create_category),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    startWidget = {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                    },
                    onClick = {
                        bottomSheetHandler.show {
                            CategoryEditSheet(
                                categoryId = 0,
                                initialTitle = "",
                                itemCount = 0,
                                onRename = { _, title -> onCreateCategory(title) },
                                onNavigate = {},
                                onDismiss = {
                                    bottomSheetHandler.hide()
                                },
                                hideAppPicker = true,
                            )
                        }
                    },
                )
            }
            ReorderablePreferenceGroup(
                label = null,
                items = displayList,
                defaultList = displayList,
                onOrderChange = { reordered ->
                    onReorderCategories(reordered.map { it.id })
                },
            ) { categoryEntry, _, _ ->
                val interactionSource = remember { MutableInteractionSource() }
                CategoryItem(
                    categoryEntry = categoryEntry,
                    appCount = categoryCounts[categoryEntry.id] ?: 0,
                    onItemClick = {
                        onOpenCategoryDetail(categoryEntry.id)
                    },
                    onItemEdit = { categoryToEdit ->
                        bottomSheetHandler.show {
                            CategoryEditSheet(
                                categoryId = categoryToEdit.id,
                                initialTitle = categoryToEdit.title,
                                itemCount = categoryCounts[categoryToEdit.id] ?: categoryToEdit.itemComponentKeys.size,
                                onRename = { id, title -> onRenameCategory(id, title) },
                                onNavigate = {},
                                onDismiss = {
                                    bottomSheetHandler.hide()
                                },
                                hideAppPicker = true,
                            )
                        }
                    },
                    onItemDelete = { categoryToDelete ->
                        if (categoryToDelete.itemComponentKeys.isNotEmpty()) {
                            categoryToDeletePending = categoryToDelete
                        } else {
                            onDeleteCategory(categoryToDelete)
                        }
                    },
                    dragIndicator = {
                        ReorderableDragHandle(
                            interactionSource = interactionSource,
                            scope = this,
                        )
                    },
                    interactionSource = interactionSource,
                )
            }
        }
    }

    categoryToDeletePending?.let { category ->
        val deleteCount = categoryCounts[category.id] ?: category.itemComponentKeys.size
        AlertDialog(
            onDismissRequest = { categoryToDeletePending = null },
            title = {
                Text(text = stringResource(id = R.string.delete_category_title))
            },
            text = {
                Text(
                    text = stringResource(
                        id = R.string.delete_category_confirmation,
                        category.title,
                        deleteCount,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteCategory(category)
                        categoryToDeletePending = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(id = R.string.action_delete))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { categoryToDeletePending = null },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(id = android.R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CategoryEditSheet(
    categoryId: Int,
    initialTitle: String,
    itemCount: Int,
    onRename: (Int, String) -> Unit,
    onNavigate: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hideAppPicker: Boolean = false,
) {
    val resources = LocalResources.current
    var textFieldValue by remember { mutableStateOf(TextFieldValue(initialTitle)) }

    ModalBottomSheetContent(
        buttons = {
            OutlinedButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.cancel))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (textFieldValue.text.isNotBlank()) {
                        onRename(categoryId, textFieldValue.text)
                        onDismiss()
                    }
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        modifier = modifier,
    ) {
        Column {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = {
                    textFieldValue = it
                },
                label = { Text(text = stringResource(id = R.string.label)) },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                singleLine = true,
                isError = textFieldValue.text.isEmpty(),
            )
            if (!hideAppPicker) {
                ClickablePreference(
                    label = "Manage apps",
                    subtitle = resources.getQuantityString(
                        R.plurals.apps_count,
                        itemCount,
                        itemCount,
                    ),
                    modifier = Modifier
                        .padding(horizontal = 8.dp),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) {
                    onNavigate(categoryId)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CategoryItem(
    categoryEntry: CategoryEntry,
    onItemClick: (CategoryEntry) -> Unit,
    onItemEdit: (CategoryEntry) -> Unit,
    onItemDelete: (CategoryEntry) -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    dragIndicator: @Composable () -> Unit = {},
    appCount: Int = categoryEntry.itemComponentKeys.size,
) {
    val resources = LocalResources.current
    var showMenu by remember { mutableStateOf(false) }

    PreferenceTemplate(
        title = {
            Text(
                text = categoryEntry.title,
            )
        },
        modifier = modifier,
        description = {
            Text(
                text = resources.getQuantityString(
                    R.plurals.apps_count,
                    appCount,
                    appCount,
                ),
            )
        },
        startWidget = {
            dragIndicator()
        },
        endWidget = {
            if (categoryEntry.id != NO_CATEGORY_ID) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(id = R.string.action_edit)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Edit,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                showMenu = false
                                onItemEdit(categoryEntry)
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(id = R.string.action_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                showMenu = false
                                onItemDelete(categoryEntry)
                            },
                        )
                    }
                }
            }
        },
        onClick = {
            onItemClick(categoryEntry)
        },
        interactionSource = interactionSource,
    )
}
