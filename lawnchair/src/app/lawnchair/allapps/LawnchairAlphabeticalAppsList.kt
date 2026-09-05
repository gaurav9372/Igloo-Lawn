package app.lawnchair.allapps

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.lawnchair.data.category.CategoryEntry
import app.lawnchair.data.category.model.CategoryViewModel
import app.lawnchair.data.folder.FolderEntry
import app.lawnchair.data.folder.model.FolderOrderUtils
import app.lawnchair.data.folder.model.FolderViewModel
import app.lawnchair.launcher
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.util.categorizeAppsWithSystemAndGoogle
import com.android.launcher3.InvariantDeviceProfile.OnIDPChangeListener
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.allapps.AlphabeticalAppsList
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem
import com.android.launcher3.allapps.PrivateProfileManager
import com.android.launcher3.allapps.WorkProfileManager
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.views.ActivityContext
import com.patrykmichalik.opto.core.onEach
import java.util.function.Predicate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private const val NO_CATEGORY_ID = "-100"

@Suppress("SYNTHETIC_PROPERTY_WITHOUT_JAVA_ORIGIN")
class LawnchairAlphabeticalAppsList<T>(
    private val context: T,
    private val appsStore: AllAppsStore<T>,
    workProfileManager: WorkProfileManager?,
    privateProfileManager: PrivateProfileManager?,
) : AlphabeticalAppsList<T>(context, appsStore, workProfileManager, privateProfileManager),
    OnIDPChangeListener,
    DefaultLifecycleObserver
    where T : Context, T : ActivityContext {

    private var hiddenApps: Set<String> = setOf()
    private var categoriesAsAccordions: Boolean = false
    private val prefs2 = PreferenceManager2.getInstance(context)
    private val prefs = PreferenceManager.getInstance(context)

    private val viewModel = FolderViewModel(
        (context as? ComponentActivity)?.application ?: context.launcher.application,
    )
    private val categoryViewModel = CategoryViewModel(
        (context as? ComponentActivity)?.application ?: context.launcher.application,
    )
    private var folderList = mutableListOf<FolderEntry>()
    var categoryList = mutableListOf<CategoryEntry>()
    private var collapsedCategories: Set<String> = setOf()
    private val filteredList = mutableListOf<AppInfo>()
    var itemTouchHelper: androidx.recyclerview.widget.ItemTouchHelper? = null

    fun toggleCategoryCollapsed(categoryId: String) {
        val updated = collapsedCategories.toMutableSet()
        if (updated.contains(categoryId)) {
            updated.remove(categoryId)
        } else {
            updated.add(categoryId)
        }
        collapsedCategories = updated
        context.launcher.lifecycleScope.launch {
            prefs2.collapsedCategories.set(updated)
        }
        updateAdapterItems()
    }

    private val folderOrder get() = FolderOrderUtils.stringToIntList(prefs.drawerListOrder.get())

    init {
        context.launcher.deviceProfile.inv.addOnChangeListener(this)
        (context as? LifecycleOwner)?.lifecycle?.addObserver(this)
        try {
            prefs2.hiddenApps.onEach(launchIn = context.launcher.lifecycleScope) {
                hiddenApps = it
                onAppsUpdated()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to initialize hidden apps", t)
        }
        try {
            prefs2.categoriesAsAccordions.onEach(launchIn = context.launcher.lifecycleScope) {
                categoriesAsAccordions = it
                updateAdapterItems()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to initialize categoriesAsAccordions", t)
        }
        try {
            prefs2.collapsedCategories.onEach(launchIn = context.launcher.lifecycleScope) {
                collapsedCategories = it
                updateAdapterItems()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to initialize collapsedCategories", t)
        }
        observeFolders()
        observeCategories()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        context.launcher.deviceProfile.inv.removeOnChangeListener(this)
    }

    private fun observeFolders() {
        viewModel.folders
            .onEach { folders ->
                if (folders != null) {
                    val seenSets = mutableSetOf<Set<String>>()
                    val uniqueFolders = mutableListOf<FolderEntry>()
                    val duplicateIdsToDelete = mutableListOf<Int>()

                    folders.forEach { folder ->
                        val itemSet = folder.itemComponentKeys.toSet()
                        if (itemSet.size < 2 || seenSets.contains(itemSet)) {
                            duplicateIdsToDelete.add(folder.id)
                        } else {
                            seenSets.add(itemSet)
                            uniqueFolders.add(folder)
                        }
                    }

                    if (duplicateIdsToDelete.isNotEmpty()) {
                        context.launcher.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            duplicateIdsToDelete.forEach { id ->
                                app.lawnchair.data.folder.service.FolderService.INSTANCE.get(context).deleteFolderInfo(id)
                            }
                        }
                    }

                    folderList = uniqueFolders
                        .sortedBy {
                            val index = folderOrder.indexOf(it.id)
                            if (index == -1) Int.MAX_VALUE else index
                        }
                        .toMutableList()
                    onAppsUpdated()
                }
            }
            .launchIn(context.launcher.lifecycleScope)
    }

    private fun observeCategories() {
        val prefs2 = PreferenceManager2.getInstance(context)
        combine(
            categoryViewModel.categories,
            prefs2.categoryOrder.get(),
        ) { categories, orderString ->
            if (categories != null) {
                val noCategory = CategoryEntry(id = -100, title = "No Category")
                val fullList = categories + noCategory
                val orderList = orderString.split(",").mapNotNull { it.toIntOrNull() }
                if (orderString.isBlank()) {
                    fullList
                } else {
                    fullList.sortedBy { entry ->
                        val idx = orderList.indexOf(entry.id)
                        if (idx != -1) idx else Int.MAX_VALUE
                    }
                }
            } else {
                null
            }
        }
            .onEach { sortedCategories ->
                if (sortedCategories != null) {
                    categoryList = sortedCategories.toMutableList()
                    onAppsUpdated()
                }
            }
            .launchIn(context.launcher.lifecycleScope)
    }

    override fun updateItemFilter(itemFilter: Predicate<ItemInfo>?) {
        mItemFilter = Predicate { info ->
            require(info is AppInfo) { "`info` must be an instance of `AppInfo`." }
            val componentKey = info.toComponentKey().toString()
            (itemFilter?.test(info) != false) && !hiddenApps.contains(componentKey)
        }
        updateAdapterItems()
    }

    override fun addAppsWithSections(appList: List<AppInfo>?, startPosition: Int): Int {
        val effectiveAppList = appList
        if (effectiveAppList.isNullOrEmpty()) return startPosition
        val drawerListDefault = prefs.drawerList.get()
        filteredList.clear()
        var position = startPosition

        // Show app drawer folders only on main profile, to prevent state complexity
        if (isWorkOrPrivateSpace(effectiveAppList)) return super.addAppsWithSections(effectiveAppList, position)

        // Category and folder keys must resolve only inside the list that passed the active
        // profile/visibility filter. Looking them up in AllAppsStore can leak a work/private or
        // hidden row into this adapter.
        val validApps = effectiveAppList.filterNotNull()
        val eligibleAppsByKey = validApps.associateBy { it.toComponentKey().toString() }

        if (categoryList.isNotEmpty()) {
            val assignedAppKeys = mutableSetOf<String>()
            val globalProcessedAppKeys = mutableSetOf<String>()
            val globalProcessedFolderIds = mutableSetOf<Int>()
            val isAccordion = categoriesAsAccordions

            val directUserCategoryKeys = categoryList
                .filter { it.id.toString() != NO_CATEGORY_ID }
                .flatMap { it.itemComponentKeys }
                .toSet()

            val userCategoryFolderAppKeys = folderList
                .filter { folder -> folder.itemComponentKeys.any { directUserCategoryKeys.contains(it) } }
                .flatMap { it.itemComponentKeys }
                .toSet()

            val allUserClaimedKeys = directUserCategoryKeys + userCategoryFolderAppKeys
            fun isClaimedByUserCategory(appInfo: AppInfo): Boolean {
                val appKey = appInfo.toComponentKey().toString()
                return allUserClaimedKeys.contains(appKey)
            }

            categoryList.forEach { categoryEntry ->
                if (categoryEntry.id.toString() == NO_CATEGORY_ID) {
                    val unassignedOrder = prefs.unassignedCategoryOrder.get()
                        .split("|")
                        .filter { it.isNotBlank() }

                    val unassignedApps = validApps.filterNot { app ->
                        isClaimedByUserCategory(app) || globalProcessedAppKeys.contains(app.toComponentKey().toString())
                    }.sortedBy { app ->
                        val key = app.toComponentKey().toString()
                        val idx = unassignedOrder.indexOf(key)
                        if (idx != -1) idx else Int.MAX_VALUE
                    }

                    if (unassignedApps.isNotEmpty()) {
                        val categoryIdStr = NO_CATEGORY_ID
                        val isCollapsed = isAccordion && collapsedCategories.contains(categoryIdStr)
                        val headerItem = AdapterItem.asCategoryHeader("No Category", unassignedApps.size).apply {
                            categoryId = categoryIdStr
                            this.isAccordion = isAccordion
                            this.isCollapsed = isCollapsed
                        }
                        mAdapterItems.add(headerItem)
                        position++

                        if (!isCollapsed) {
                            unassignedApps.forEach { appInfo ->
                                val appKey = appInfo.toComponentKey().toString()

                                if (!globalProcessedAppKeys.contains(appKey)) {
                                    val targetFolder = folderList.find { folderEntry ->
                                        !globalProcessedFolderIds.contains(folderEntry.id) &&
                                            folderEntry.itemComponentKeys.contains(appKey) &&
                                            folderEntry.itemComponentKeys.none { allUserClaimedKeys.contains(it) }
                                    }

                                    var folderAdded = false
                                    if (targetFolder != null) {
                                        val folderApps = targetFolder.itemComponentKeys.mapNotNull { keyString ->
                                            if (hiddenApps.contains(keyString)) return@mapNotNull null
                                            val componentKey = ComponentKey.fromString(keyString) ?: return@mapNotNull null
                                            val app = eligibleAppsByKey[componentKey.toString()]
                                            if (app != null && unassignedApps.contains(app)) app else null
                                        }

                                        if (folderApps.size >= 2) {
                                            val folderInfo = FolderInfo().apply {
                                                id = targetFolder.id
                                                title = if (targetFolder.title.isNullOrBlank()) "Folder" else targetFolder.title
                                                container = com.android.launcher3.LauncherSettings.Favorites.CONTAINER_ALL_APPS
                                                folderApps.forEach { add(it) }
                                            }
                                            val folderAdapterItem = AdapterItem.asFolder(folderInfo)
                                            folderAdapterItem.categoryId = categoryIdStr
                                            mAdapterItems.add(folderAdapterItem)
                                            position++
                                            globalProcessedFolderIds.add(targetFolder.id)
                                            folderAdded = true

                                            folderApps.forEach { info ->
                                                val key = info.toComponentKey().toString()
                                                globalProcessedAppKeys.add(key)
                                            }
                                        }
                                    }

                                    if (!folderAdded && !globalProcessedAppKeys.contains(appKey)) {
                                        val item = AdapterItem.asApp(appInfo)
                                        item.categoryId = categoryIdStr
                                        mAdapterItems.add(item)
                                        globalProcessedAppKeys.add(appKey)
                                        position++
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val catFolderAppKeys = folderList
                        .filter { folder -> folder.itemComponentKeys.any { categoryEntry.itemComponentKeys.contains(it) } }
                        .flatMap { it.itemComponentKeys }

                    val fullCategoryAppKeys = (categoryEntry.itemComponentKeys + catFolderAppKeys).distinct()

                    val resolvedApps = fullCategoryAppKeys.mapNotNull { keyString ->
                        if (hiddenApps.contains(keyString)) return@mapNotNull null
                        val componentKey = ComponentKey.fromString(keyString) ?: return@mapNotNull null
                        eligibleAppsByKey[componentKey.toString()]
                    }

                    if (resolvedApps.isNotEmpty()) {
                        val categoryIdStr = categoryEntry.id.toString()
                        val isCollapsed = isAccordion && collapsedCategories.contains(categoryIdStr)
                        val headerItem = AdapterItem.asCategoryHeader(categoryEntry.title, resolvedApps.size).apply {
                            categoryId = categoryIdStr
                            this.isAccordion = isAccordion
                            this.isCollapsed = isCollapsed
                        }
                        mAdapterItems.add(headerItem)
                        position++

                        if (!isCollapsed) {
                            resolvedApps.forEach { appInfo ->
                                val appKey = appInfo.toComponentKey().toString()
                                assignedAppKeys.add(appKey)

                                if (!globalProcessedAppKeys.contains(appKey)) {
                                    val targetFolder = folderList.find { folderEntry ->
                                        !globalProcessedFolderIds.contains(folderEntry.id) &&
                                            folderEntry.itemComponentKeys.contains(appKey)
                                    }

                                    var folderAdded = false
                                    if (targetFolder != null) {
                                        val folderApps = targetFolder.itemComponentKeys.mapNotNull { keyString ->
                                            if (hiddenApps.contains(keyString)) return@mapNotNull null
                                            val componentKey = ComponentKey.fromString(keyString) ?: return@mapNotNull null
                                            eligibleAppsByKey[componentKey.toString()]
                                        }

                                        if (folderApps.size >= 2) {
                                            val folderInfo = FolderInfo().apply {
                                                id = targetFolder.id
                                                title = targetFolder.title
                                                container = com.android.launcher3.LauncherSettings.Favorites.CONTAINER_ALL_APPS
                                                folderApps.forEach { add(it) }
                                            }
                                            val folderAdapterItem = AdapterItem.asFolder(folderInfo)
                                            folderAdapterItem.categoryId = categoryIdStr
                                            mAdapterItems.add(folderAdapterItem)
                                            position++
                                            globalProcessedFolderIds.add(targetFolder.id)
                                            folderAdded = true

                                            folderApps.forEach { info ->
                                                val key = info.toComponentKey().toString()
                                                globalProcessedAppKeys.add(key)
                                                assignedAppKeys.add(key)
                                            }
                                        }
                                    }

                                    if (!folderAdded && !globalProcessedAppKeys.contains(appKey)) {
                                        val item = AdapterItem.asApp(appInfo)
                                        item.categoryId = categoryIdStr
                                        mAdapterItems.add(item)
                                        globalProcessedAppKeys.add(appKey)
                                        position++
                                    }
                                }
                            }
                        } else {
                            resolvedApps.forEach { appInfo ->
                                assignedAppKeys.add(appInfo.toComponentKey().toString())
                            }
                        }
                    }
                }
            }
            return position
        }

        if (!drawerListDefault) {
            val finalCategorizedApps = categorizeAppsWithSystemAndGoogle(validApps, context)

            finalCategorizedApps.forEach { (category, apps) ->
                if (apps.size == 1) {
                    mAdapterItems.add(AdapterItem.asApp(apps.first()))
                } else {
                    val folderInfo = FolderInfo().apply {
                        title = category
                        container = com.android.launcher3.LauncherSettings.Favorites.CONTAINER_ALL_APPS
                        apps.forEach { add(it) }
                    }
                    mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                }
                position++
            }
        } else {
            val processedDefaultFolderIds = mutableSetOf<Int>()
            val processedDefaultAppKeys = mutableSetOf<String>()

            validApps.forEach { app ->
                val appKey = app.toComponentKey().toString()
                if (!processedDefaultAppKeys.contains(appKey)) {
                    val targetFolder = folderList.find { folderEntry ->
                        !processedDefaultFolderIds.contains(folderEntry.id) &&
                            folderEntry.itemComponentKeys.contains(appKey)
                    }

                    var folderAdded = false
                    if (targetFolder != null) {
                        val resolvedApps = targetFolder.itemComponentKeys.mapNotNull { keyString ->
                            if (hiddenApps.contains(keyString)) return@mapNotNull null
                            val componentKey = ComponentKey.fromString(keyString) ?: return@mapNotNull null
                            eligibleAppsByKey[componentKey.toString()]
                        }

                        if (resolvedApps.size >= 2) {
                            val folderInfo = FolderInfo().apply {
                                id = targetFolder.id
                                title = if (targetFolder.title.isNullOrBlank()) "Folder" else targetFolder.title
                                container = com.android.launcher3.LauncherSettings.Favorites.CONTAINER_ALL_APPS
                                resolvedApps.forEach { add(it) }
                            }
                            mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                            position++
                            processedDefaultFolderIds.add(targetFolder.id)
                            resolvedApps.forEach {
                                processedDefaultAppKeys.add(it.toComponentKey().toString())
                            }
                            folderAdded = true
                        }
                    }

                    if (!folderAdded && !processedDefaultAppKeys.contains(appKey)) {
                        mAdapterItems.add(AdapterItem.asApp(app))
                        processedDefaultAppKeys.add(appKey)
                        position++
                    }
                }
            }
        }

        return position
    }

    fun persistCategoryChanges() {
        val categoryKeyMap = mutableMapOf<String, MutableList<String>>()
        val renderedCategoryIds = mutableSetOf<String>()

        mAdapterItems.forEach { item ->
            val catId = item.categoryId
            if (!catId.isNullOrEmpty()) {
                if (item.viewType == BaseAllAppsAdapter.VIEW_TYPE_CATEGORY_HEADER) {
                    renderedCategoryIds.add(catId)
                }
                val keys = when (item.viewType) {
                    BaseAllAppsAdapter.VIEW_TYPE_ICON -> listOfNotNull(item.itemInfo?.toComponentKey()?.toString())

                    BaseAllAppsAdapter.VIEW_TYPE_FOLDER -> item.folderInfo?.getContents()?.mapNotNull { itemInfo ->
                        (itemInfo as? AppInfo)?.toComponentKey()?.toString()
                            ?: (itemInfo as? com.android.launcher3.model.data.WorkspaceItemInfo)?.targetComponent?.let { ComponentKey(it, itemInfo.user).toString() }
                    } ?: emptyList()

                    else -> emptyList()
                }
                if (keys.isNotEmpty()) {
                    categoryKeyMap.getOrPut(catId) { mutableListOf() }.addAll(keys)
                }
            }
        }

        // Rendered assignments take precedence over saved membership regardless of category
        // order. This matters when an item is dropped into a collapsed category: its new row is
        // rendered, while all of that category's existing rows intentionally are not.
        val renderedAssignedKeys = categoryKeyMap.values.flatten().toSet()
        val claimedKeys = mutableSetOf<String>()

        val updatedList = categoryList.map { categoryEntry ->
            val categoryIdStr = categoryEntry.id.toString()
            val keysForThisCatFromAdapter = (categoryKeyMap[categoryIdStr] ?: emptyList())
                .filterNot { claimedKeys.contains(it) }
            val preservedKeys = categoryEntry.itemComponentKeys.filterNot { key ->
                renderedAssignedKeys.contains(key) || claimedKeys.contains(key)
            }

            val isCollapsed = categoriesAsAccordions && collapsedCategories.contains(categoryIdStr)
            val wasRendered = renderedCategoryIds.contains(categoryIdStr)
            val finalKeys = if (categoryIdStr == NO_CATEGORY_ID) {
                keysForThisCatFromAdapter.distinct()
            } else if (wasRendered && !isCollapsed) {
                val uninstalledCategoryApps = preservedKeys.filter { key ->
                    val ck = ComponentKey.fromString(key)
                    ck == null || (appsStore.getApp(ck) == null && appsStore.getApp(ck, AppInfo.PACKAGE_KEY_COMPARATOR) == null)
                }

                val hiddenCategoryApps = preservedKeys.filter { key ->
                    hiddenApps.contains(key)
                }

                (keysForThisCatFromAdapter + uninstalledCategoryApps + hiddenCategoryApps).distinct()
            } else if (wasRendered && isCollapsed) {
                (keysForThisCatFromAdapter + preservedKeys).distinct()
            } else {
                preservedKeys
            }
            claimedKeys.addAll(finalKeys)

            if (categoryIdStr != NO_CATEGORY_ID && finalKeys != categoryEntry.itemComponentKeys) {
                categoryViewModel.updateCategoryItems(categoryEntry.id, categoryEntry.title, finalKeys)
            }

            categoryEntry.copy(itemComponentKeys = finalKeys)
        }
        categoryList = updatedList.toMutableList()

        val rawNoCatKeys = categoryKeyMap[NO_CATEGORY_ID] ?: emptyList()
        val noCategoryKeys = rawNoCatKeys.filterNot { claimedKeys.contains(it) }.distinct()

        prefs.unassignedCategoryOrder.set(noCategoryKeys.joinToString("|"))

        if (noCategoryKeys.isNotEmpty()) {
            context.launcher.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    app.lawnchair.data.category.service.CategoryService.INSTANCE.get(context)
                        .removeComponentKeysFromAllCategories(noCategoryKeys)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove no_category keys from DB", e)
                }
            }
        }
    }

    fun createFolderWithApps(targetAppKey: String, draggedAppKey: String, targetCategoryId: String?) {
        context.launcher.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val folderId = app.lawnchair.data.folder.service.FolderService.INSTANCE.get(context).createFolderWithItems(
                title = "Folder",
                componentKeys = listOf(targetAppKey, draggedAppKey),
            )
            val newEntry = FolderEntry(id = folderId, title = "Folder", itemComponentKeys = listOf(targetAppKey, draggedAppKey))
            folderList = (folderList.filterNot { it.id == folderId } + newEntry).toMutableList()

            if (!targetCategoryId.isNullOrEmpty() && targetCategoryId != NO_CATEGORY_ID) {
                val catIdInt = targetCategoryId.toIntOrNull()
                categoryList = categoryList.map { cat ->
                    if (cat.id.toString() == targetCategoryId) {
                        val cleanKeys = cat.itemComponentKeys.filterNot { it == draggedAppKey }.toMutableList()
                        if (!cleanKeys.contains(targetAppKey)) {
                            cleanKeys.add(targetAppKey)
                        }
                        if (catIdInt != null) {
                            try {
                                app.lawnchair.data.category.service.CategoryService.INSTANCE.get(context)
                                    .updateCategoryWithItems(catIdInt, cat.title, cleanKeys)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to update category items in DB", e)
                            }
                        }
                        cat.copy(itemComponentKeys = cleanKeys)
                    } else {
                        val cleanKeys = cat.itemComponentKeys.filterNot { it == draggedAppKey || it == targetAppKey }
                        cat.copy(itemComponentKeys = cleanKeys)
                    }
                }.toMutableList()
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                safeNotifyAdapter {
                    updateAdapterItems()
                    adapter?.notifyDataSetChanged()
                    persistCategoryChanges()
                }
            }
        }
    }

    fun addAppToFolder(folderId: Int, folderTitle: String, draggedAppKey: String, targetCategoryId: String?) {
        context.launcher.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!targetCategoryId.isNullOrEmpty() && targetCategoryId != NO_CATEGORY_ID) {
                try {
                    val catId = targetCategoryId.toInt()
                    app.lawnchair.data.category.service.CategoryService.INSTANCE.get(context).moveAppToCategory(draggedAppKey, catId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to move dragged app to target category", e)
                }
            } else if (targetCategoryId == NO_CATEGORY_ID) {
                try {
                    app.lawnchair.data.category.service.CategoryService.INSTANCE.get(context).removeComponentKeysFromAllCategories(listOf(draggedAppKey))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove component keys from categories", e)
                }
            }
            val existingEntry = folderList.find { it.id == folderId }
            val existingKeys = existingEntry?.itemComponentKeys ?: emptyList()
            val updatedKeys = (existingKeys + draggedAppKey).distinct()

            app.lawnchair.data.folder.service.FolderService.INSTANCE.get(context).updateFolderWithItems(
                folderInfoId = folderId,
                title = folderTitle,
                componentKeys = updatedKeys,
            )
            val updatedEntry = FolderEntry(id = folderId, title = folderTitle, itemComponentKeys = updatedKeys)
            folderList = (folderList.filterNot { it.id == folderId } + updatedEntry).toMutableList()

            if (!targetCategoryId.isNullOrEmpty()) {
                categoryList = categoryList.map { cat ->
                    if (cat.id.toString() == targetCategoryId) {
                        val cleanKeys = cat.itemComponentKeys.filterNot { it == draggedAppKey }.toMutableList()
                        cat.copy(itemComponentKeys = cleanKeys)
                    } else {
                        cat.copy(itemComponentKeys = cat.itemComponentKeys.filterNot { it == draggedAppKey })
                    }
                }.toMutableList()
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                safeNotifyAdapter {
                    updateAdapterItems()
                    adapter?.notifyDataSetChanged()
                    persistCategoryChanges()
                }
            }
        }
    }

    fun onAppDraggedOutOfFolder(draggedAppKey: String, folderId: Int) {
        val folderEntry = folderList.find { it.id == folderId } ?: return
        val remainingKeys = folderEntry.itemComponentKeys.filterNot { it == draggedAppKey }
        val folderItem = mAdapterItems.find {
            it.viewType == BaseAllAppsAdapter.VIEW_TYPE_FOLDER && it.folderInfo?.id == folderId
        }
        val categoryId = folderItem?.categoryId ?: categoryList.find { cat ->
            cat.itemComponentKeys.any { key -> folderEntry.itemComponentKeys.contains(key) }
        }?.id?.toString()

        context.launcher.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                app.lawnchair.data.folder.service.FolderService.INSTANCE.get(context).moveAppOutOfFolder(
                    folderId = folderId,
                    folderTitle = folderEntry.title,
                    remainingFolderKeys = remainingKeys,
                    componentKey = draggedAppKey,
                    targetCategoryId = categoryId?.takeUnless { it == NO_CATEGORY_ID }?.toIntOrNull(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to move app out of drawer folder", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onAppDragCancelledFromFolder()
                }
                return@launch
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                updateFolderEntryAfterRemoval(folderEntry, remainingKeys)
                updateCategoryMembershipInMemory(draggedAppKey, categoryId)
                updateAdapterItems()
                adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun updateFolderEntryAfterRemoval(folderEntry: FolderEntry, remainingKeys: List<String>) {
        folderList = if (remainingKeys.size < 2) {
            folderList.filterNot { it.id == folderEntry.id }.toMutableList()
        } else {
            val updatedEntry = folderEntry.copy(itemComponentKeys = remainingKeys)
            (folderList.filterNot { it.id == folderEntry.id } + updatedEntry).toMutableList()
        }
    }

    private fun updateCategoryMembershipInMemory(componentKey: String, targetCategoryId: String?) {
        categoryList = categoryList.map { category ->
            if (category.id.toString() == NO_CATEGORY_ID) return@map category
            val keys = category.itemComponentKeys.filterNot { it == componentKey }.toMutableList()
            if (category.id.toString() == targetCategoryId && !keys.contains(componentKey)) {
                keys.add(componentKey)
            }
            category.copy(itemComponentKeys = keys)
        }.toMutableList()
    }

    private fun safeNotifyAdapter(action: () -> Unit) {
        val rv = context.launcher.appsView?.activeRecyclerView
        if (rv != null && rv.isComputingLayout) {
            rv.post {
                try {
                    action()
                } catch (e: Exception) {
                    Log.w(TAG, "Safe notify failed", e)
                }
            }
        } else {
            try {
                action()
            } catch (e: Exception) {
                Log.w(TAG, "Safe notify failed", e)
            }
        }
    }

    fun onAppRemovedFromFolder(draggedAppKey: String, folderId: Int) {
        val folderEntry = folderList.find { it.id == folderId } ?: return
        val currentKeys = folderEntry.itemComponentKeys
        val remainingKeys = currentKeys.filterNot { it == draggedAppKey }

        safeNotifyAdapter {
            try {
                val folderIndex = mAdapterItems.indexOfFirst {
                    it.viewType == BaseAllAppsAdapter.VIEW_TYPE_FOLDER && it.folderInfo?.id == folderId
                }

                if (remainingKeys.size < 2) {
                    if (folderIndex != -1 && folderIndex in mAdapterItems.indices) {
                        val folderItem = mAdapterItems[folderIndex]
                        val remainingInfo = folderItem.folderInfo?.getContents()?.firstOrNull { info ->
                            (info as? AppInfo)?.toComponentKey()?.toString() != draggedAppKey
                        } as? AppInfo

                        if (remainingInfo != null) {
                            val standaloneItem = AdapterItem.asApp(remainingInfo).apply {
                                categoryId = folderItem.categoryId
                            }
                            mAdapterItems[folderIndex] = standaloneItem
                            adapter?.notifyItemChanged(folderIndex)
                        } else {
                            mAdapterItems.removeAt(folderIndex)
                            adapter?.notifyItemRemoved(folderIndex)
                        }
                    }
                } else {
                    if (folderIndex != -1 && folderIndex in mAdapterItems.indices) {
                        val folderItem = mAdapterItems[folderIndex]
                        folderItem.folderInfo?.getContents()?.removeIf { info ->
                            (info as? AppInfo)?.toComponentKey()?.toString() == draggedAppKey
                        }
                        adapter?.notifyItemChanged(folderIndex)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed in onAppRemovedFromFolder", e)
            }
        }
    }

    fun onAppDragCancelledFromFolder() {
        safeNotifyAdapter {
            updateAdapterItems()
            adapter?.notifyDataSetChanged()
        }
    }

    fun onFolderDragOver(appInfo: AppInfo, targetPos: Int) {
        safeNotifyAdapter {
            try {
                val appKey = appInfo.toComponentKey().toString()
                if (targetPos !in mAdapterItems.indices) return@safeNotifyAdapter

                val existingIndex = mAdapterItems.indexOfFirst {
                    it.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON &&
                        it.itemInfo?.toComponentKey()?.toString() == appKey
                }

                var resolvedCatId: String? = null
                for (i in targetPos downTo 0) {
                    val item = mAdapterItems.getOrNull(i)
                    if (item != null) {
                        if (!item.categoryId.isNullOrEmpty()) {
                            resolvedCatId = item.categoryId
                            break
                        }
                        if (item.viewType == BaseAllAppsAdapter.VIEW_TYPE_CATEGORY_HEADER) {
                            resolvedCatId = item.categoryId
                            if (resolvedCatId != null) break
                        }
                    }
                }
                val targetCatId = resolvedCatId ?: NO_CATEGORY_ID

                val targetItem = mAdapterItems[targetPos]
                val effectivePos = if (targetItem.viewType == BaseAllAppsAdapter.VIEW_TYPE_CATEGORY_HEADER) {
                    (targetPos + 1).coerceAtMost(mAdapterItems.size)
                } else {
                    targetPos
                }

                if (existingIndex == -1) {
                    val newItem = AdapterItem.asApp(appInfo).apply {
                        categoryId = targetCatId
                    }
                    if (effectivePos <= mAdapterItems.size) {
                        mAdapterItems.add(effectivePos, newItem)
                        adapter?.notifyItemInserted(effectivePos)
                    }
                } else if (existingIndex != effectivePos && existingIndex in mAdapterItems.indices) {
                    val item = mAdapterItems.removeAt(existingIndex)
                    item.categoryId = targetCatId
                    val safePos = effectivePos.coerceAtMost(mAdapterItems.size)
                    mAdapterItems.add(safePos, item)
                    adapter?.notifyItemMoved(existingIndex, safePos)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed in onFolderDragOver", e)
            }
        }
    }

    fun onAppDroppedFromFolderAtPosition(appInfo: AppInfo, folderId: Int) {
        val draggedAppKey = appInfo.toComponentKey().toString()
        val targetPos = mAdapterItems.indexOfFirst {
            it.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON &&
                it.itemInfo?.toComponentKey()?.toString() == draggedAppKey
        }

        var dropCatId: String? = null
        if (targetPos != -1) {
            for (i in targetPos downTo 0) {
                val item = mAdapterItems.getOrNull(i)
                if (item != null) {
                    if (!item.categoryId.isNullOrEmpty()) {
                        dropCatId = item.categoryId
                        break
                    }
                    if (item.viewType == BaseAllAppsAdapter.VIEW_TYPE_CATEGORY_HEADER) {
                        dropCatId = item.categoryId
                        if (dropCatId != null) break
                    }
                }
            }
        }
        val targetCatId = dropCatId ?: NO_CATEGORY_ID
        val folderEntry = folderList.find { it.id == folderId } ?: return
        val remainingKeys = folderEntry.itemComponentKeys.filterNot { it == draggedAppKey }

        context.launcher.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                app.lawnchair.data.folder.service.FolderService.INSTANCE.get(context).moveAppOutOfFolder(
                    folderId = folderId,
                    folderTitle = folderEntry.title,
                    remainingFolderKeys = remainingKeys,
                    componentKey = draggedAppKey,
                    targetCategoryId = targetCatId.takeUnless { it == NO_CATEGORY_ID }?.toIntOrNull(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to drop app out of drawer folder", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onAppDragCancelledFromFolder()
                }
                return@launch
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                safeNotifyAdapter {
                    updateFolderEntryAfterRemoval(folderEntry, remainingKeys)
                    updateCategoryMembershipInMemory(draggedAppKey, targetCatId)
                    updateAdapterItems()
                    adapter?.notifyDataSetChanged()
                }
            }
        }
    }

    fun setupCategoryTouchHelper(recyclerView: androidx.recyclerview.widget.RecyclerView) {
        val callback = AllAppsCategoryTouchHelperCallback(this)
        val helper = androidx.recyclerview.widget.ItemTouchHelper(callback)
        helper.attachToRecyclerView(recyclerView)
        itemTouchHelper = helper
    }

    private var appsUpdateRunnable: Runnable? = null

    override fun onAppsUpdated() {
        val decorView = (context as? android.app.Activity)?.window?.decorView
        if (decorView != null) {
            appsUpdateRunnable?.let { decorView.removeCallbacks(it) }
            val runnable = Runnable { super.onAppsUpdated() }
            appsUpdateRunnable = runnable
            decorView.postDelayed(runnable, 150)
        } else {
            super.onAppsUpdated()
        }
    }

    override fun onIdpChanged(modelPropertiesChanged: Boolean) {
        onAppsUpdated()
    }
}
