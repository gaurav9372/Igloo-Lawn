package app.lawnchair.ui.preferences.destinations

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lawnchair.data.category.CategoryEntry
import app.lawnchair.data.category.model.CategoryViewModel
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
) {
    val navController = LocalNavController.current
    val categories by viewModel.categories.collectAsStateWithLifecycle()

    AppDrawerCategoriesPreference(
        modifier = modifier,
        categories = categories,
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
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppDrawerCategoriesPreference(
    categories: List<CategoryEntry>?,
    onCreateCategory: (String) -> Unit,
    onOpenCategoryDetail: (Int) -> Unit,
    onRenameCategory: (Int, String) -> Unit,
    onDeleteCategory: (CategoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottomSheetHandler = bottomSheetHandler
    val displayList = categories ?: emptyList()

    LoadingScreen(
        isLoading = categories == null,
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
                onOrderChange = { _ -> },
            ) { categoryEntry, _, _ ->
                val interactionSource = remember { MutableInteractionSource() }
                CategoryItem(
                    categoryEntry = categoryEntry,
                    onItemClick = {
                        onOpenCategoryDetail(categoryEntry.id)
                    },
                    onItemDelete = { categoryToDelete ->
                        onDeleteCategory(categoryToDelete)
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
    onItemDelete: (CategoryEntry) -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    dragIndicator: @Composable () -> Unit = {},
) {
    val resources = LocalResources.current
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
                    categoryEntry.itemComponentKeys.size,
                    categoryEntry.itemComponentKeys.size,
                ),
            )
        },
        startWidget = {
            dragIndicator()
        },
        endWidget = {
            Row {
                IconButton(
                    onClick = {
                        onItemDelete(categoryEntry)
                    },
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        onClick = {
            onItemClick(categoryEntry)
        },
        interactionSource = interactionSource,
    )
}
