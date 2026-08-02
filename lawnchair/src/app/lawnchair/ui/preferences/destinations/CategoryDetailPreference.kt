package app.lawnchair.ui.preferences.destinations

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lawnchair.data.category.CategoryEntry
import app.lawnchair.data.category.model.CategoryViewModel
import app.lawnchair.ui.preferences.components.AppItem
import app.lawnchair.ui.preferences.components.layout.LoadingScreen
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.util.App
import app.lawnchair.util.appsState
import com.android.launcher3.R

@Composable
fun CategoryDetailPreference(
    categoryInfoId: Int?,
    modifier: Modifier = Modifier,
    viewModel: CategoryViewModel = viewModel(),
) {
    if (categoryInfoId == null) {
        val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        backDispatcher?.onBackPressed()
        return
    }

    val categoryEntry by viewModel.getCategoryFlowForId(categoryInfoId).collectAsStateWithLifecycle(null)
    val allCategories by viewModel.categories.collectAsStateWithLifecycle()
    val apps by appsState()

    CategoryDetailPreference(
        categoryEntry = categoryEntry,
        allCategories = allCategories,
        apps = apps,
        onUpdateCategoryItems = { title, componentKeys ->
            viewModel.updateCategoryItems(categoryInfoId, title, componentKeys)
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CategoryDetailPreference(
    categoryEntry: CategoryEntry?,
    allCategories: List<CategoryEntry>?,
    apps: List<App>,
    onUpdateCategoryItems: (title: String, componentKeys: List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = categoryEntry?.title ?: stringResource(id = R.string.categories_label)
    var showAppPickerModal by remember { mutableStateOf(false) }

    val assignedApps = remember(categoryEntry, apps) {
        if (categoryEntry == null) emptyList()
        else {
            val assignedKeys = categoryEntry.itemComponentKeys.toSet()
            apps.filter { app -> assignedKeys.contains(app.key.toString()) }
        }
    }

    val availableAppsForCategory = remember(apps, allCategories, categoryEntry) {
        val currentCategoryId = categoryEntry?.id
        val otherCategoryKeys = allCategories?.filter { it.id != currentCategoryId }
            ?.flatMap { it.itemComponentKeys }
            ?.toSet() ?: emptySet()
        apps.filter { app -> !otherCategoryKeys.contains(app.key.toString()) }
    }

    if (showAppPickerModal && categoryEntry != null) {
        CategoryAppSelectionDialog(
            categoryEntry = categoryEntry,
            availableApps = availableAppsForCategory,
            onSave = { updatedKeys ->
                onUpdateCategoryItems(categoryEntry.title, updatedKeys)
                showAppPickerModal = false
            },
            onDismiss = { showAppPickerModal = false },
        )
    }

    LoadingScreen(
        isLoading = categoryEntry == null,
        modifier = modifier.fillMaxSize(),
    ) {
        Scaffold(
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showAppPickerModal = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Add Apps",
                    )
                }
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                PreferenceLayout(
                    label = title,
                    backArrowVisible = true,
                ) {
                    if (assignedApps.isEmpty()) {
                        PreferenceGroup {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp, horizontal = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = "No apps in this category yet.",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Tap the '+' button below to pick apps for this category.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else {
                        PreferenceGroup(
                            heading = "Apps in Category (${assignedApps.size})",
                        ) {
                            assignedApps.forEach { app ->
                                AppItem(
                                    app = app,
                                    onClick = {},
                                    endWidget = {
                                        IconButton(
                                            onClick = {
                                                val updatedKeys = categoryEntry.itemComponentKeys
                                                    .filter { it != app.key.toString() }
                                                onUpdateCategoryItems(categoryEntry.title, updatedKeys)
                                            },
                                            shapes = IconButtonDefaults.shapes(),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Delete,
                                                contentDescription = "Remove App",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
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
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CategoryAppSelectionDialog(
    categoryEntry: CategoryEntry,
    availableApps: List<App>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedKeys by remember(categoryEntry) {
        mutableStateOf(categoryEntry.itemComponentKeys.toSet())
    }

    val filteredApps = remember(availableApps, searchQuery) {
        if (searchQuery.isBlank()) {
            availableApps
        } else {
            availableApps.filter { it.label.contains(searchQuery, ignoreCase = true) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "Select Apps",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search apps...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search",
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Clear,
                                    contentDescription = "Clear search",
                                )
                            }
                        }
                    },
                    singleLine = true,
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
            ) {
                items(
                    items = filteredApps,
                    key = { it.key.toString() },
                ) { app ->
                    val appKeyStr = app.key.toString()
                    val isChecked = selectedKeys.contains(appKeyStr)

                    AppItem(
                        app = app,
                        onClick = {
                            selectedKeys = if (isChecked) {
                                selectedKeys - appKeyStr
                            } else {
                                selectedKeys + appKeyStr
                            }
                        },
                        endWidget = {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    selectedKeys = if (checked) {
                                        selectedKeys + appKeyStr
                                    } else {
                                        selectedKeys - appKeyStr
                                    }
                                },
                            )
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedKeys.toList()) },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("Done")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
