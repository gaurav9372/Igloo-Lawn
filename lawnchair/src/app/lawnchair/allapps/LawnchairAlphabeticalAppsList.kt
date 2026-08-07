package app.lawnchair.allapps

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import app.lawnchair.data.category.CategoryEntry
import app.lawnchair.data.category.model.CategoryViewModel
import app.lawnchair.data.folder.FolderEntry
import app.lawnchair.data.folder.model.FolderOrderUtils
import app.lawnchair.data.folder.model.FolderViewModel
import app.lawnchair.launcher
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
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
                    updateAdapterItems()
                }
            }
            .launchIn(context.launcher.lifecycleScope)
    }

    private fun observeCategories() {
        categoryViewModel.categories
            .onEach { categories ->
                if (categories != null) {
                    categoryList = categories.toMutableList()
                    updateAdapterItems()
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
        onAppsUpdated()
    }

    override fun addAppsWithSections(appList: List<AppInfo?>?, startPosition: Int): Int {
        if (appList.isNullOrEmpty()) return startPosition
        val drawerListDefault = prefs.drawerList.get()
        filteredList.clear()
        var position = startPosition

        // Show app drawer folders only on main profile, to prevent state complexity
        if (isWorkOrPrivateSpace(appList)) return super.addAppsWithSections(appList, position)

        if (categoryList.isNotEmpty()) {
            val validApps = appList.mapNotNull { it }
            val assignedAppKeys = mutableSetOf<String>()
            val globalProcessedAppKeys = mutableSetOf<String>()
            val globalProcessedFolderIds = mutableSetOf<Int>()
            val isAccordion = categoriesAsAccordions

            categoryList.forEach { categoryEntry ->
                val resolvedApps = categoryEntry.itemComponentKeys.mapNotNull { keyString ->
                    if (hiddenApps.contains(keyString)) return@mapNotNull null
                    val componentKey = ComponentKey.fromString(keyString) ?: return@mapNotNull null
                    appsStore.getApp(componentKey) as? AppInfo
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
                                        appsStore.getApp(componentKey) as? AppInfo
                                    }

                                    if (folderApps.size >= 2) {
                                        val folderInfo = FolderInfo().apply {
                                            id = targetFolder.id
                                            title = targetFolder.title
                                            container = ItemInfo.NO_ID
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

            val unassignedOrder = prefs.unassignedCategoryOrder.get()
                .split("|")
                .filter { it.isNotBlank() }

            val unassignedApps = validApps.filterNot { app ->
                val appKey = app.toComponentKey().toString()
                assignedAppKeys.contains(appKey) || globalProcessedAppKeys.contains(appKey)
            }.sortedBy { app ->
                val key = app.toComponentKey().toString()
                val idx = unassignedOrder.indexOf(key)
                if (idx != -1) idx else Int.MAX_VALUE
            }

            if (unassignedApps.isNotEmpty()) {
                val categoryIdStr = "no_category"
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
                                    folderEntry.itemComponentKeys.contains(appKey)
                            }

                            var folderAdded = false
                            if (targetFolder != null) {
                                val folderApps = targetFolder.itemComponentKeys.mapNotNull { keyString ->
                                    if (hiddenApps.contains(keyString)) return@mapNotNull null
                                    val componentKey = ComponentKey.fromString(keyString) ?: return@mapNotNull null
                                    val app = appsStore.getApp(componentKey) as? AppInfo
                                    if (app != null && unassignedApps.contains(app)) app else null
                                }

                                if (folderApps.size >= 2) {
                                    val folderInfo = FolderInfo().apply {
                                        id = targetFolder.id
                                        title = targetFolder.title
                                        container = ItemInfo.NO_ID
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
            return position
        }

        if (!drawerListDefault) {
            val validApps = appList.mapNotNull { it }
            val finalCategorizedApps = categorizeAppsWithSystemAndGoogle(validApps, context)

            finalCategorizedApps.forEach { (category, apps) ->
                if (apps.size == 1) {
                    mAdapterItems.add(AdapterItem.asApp(apps.first()))
                } else {
                    val folderInfo = FolderInfo().apply {
                        title = category
                        container = ItemInfo.NO_ID
                        apps.forEach { add(it) }
                    }
                    mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                }
                position++
            }
        } else {
            val processedDefaultFolderIds = mutableSetOf<Int>()
            val processedDefaultAppKeys = mutableSetOf<String>()

            appList.forEach { app ->
                if (app != null) {
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
                                appsStore.getApp(componentKey) as? AppInfo
                            }

                            if (resolvedApps.size >= 2) {
                                val folderInfo = FolderInfo().apply {
                                    id = targetFolder.id
                                    title = targetFolder.title
                                    container = ItemInfo.NO_ID
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
        }

        return position
    }

    fun persistCategoryChanges() {
        val categoryKeyMap = mutableMapOf<String, MutableList<String>>()

        mAdapterItems.forEach { item ->
            val catId = item.categoryId
            if (!catId.isNullOrEmpty()) {
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

        val claimedKeys = mutableSetOf<String>()

        val updatedList = categoryList.map { categoryEntry ->
            val categoryIdStr = categoryEntry.id.toString()
            val keysForThisCat = (categoryKeyMap[categoryIdStr] ?: emptyList())
                .filterNot { claimedKeys.contains(it) }

            claimedKeys.addAll(keysForThisCat)

            val uninstalledCategoryApps = categoryEntry.itemComponentKeys.filter { key ->
                val ck = ComponentKey.fromString(key)
                ck == null || appsStore.getApp(ck) == null
            }.filterNot { claimedKeys.contains(it) }

            val hiddenCategoryApps = categoryEntry.itemComponentKeys.filter { key ->
                hiddenApps.contains(key)
            }.filterNot { claimedKeys.contains(it) }

            val finalKeys = (keysForThisCat + uninstalledCategoryApps + hiddenCategoryApps).distinct()
            claimedKeys.addAll(finalKeys)

            categoryViewModel.updateCategoryItems(categoryEntry.id, categoryEntry.title, finalKeys)
            categoryEntry.copy(itemComponentKeys = finalKeys)
        }
        categoryList = updatedList.toMutableList()

        val rawNoCatKeys = categoryKeyMap["no_category"] ?: emptyList()
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
            if (!targetCategoryId.isNullOrEmpty() && targetCategoryId != "no_category") {
                try {
                    val catId = targetCategoryId.toInt()
                    app.lawnchair.data.category.service.CategoryService.INSTANCE.get(context).moveAppToCategory(draggedAppKey, catId)
                    app.lawnchair.data.category.service.CategoryService.INSTANCE.get(context).moveAppToCategory(targetAppKey, catId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to move dragged app to target category", e)
                }
            } else if (targetCategoryId == "no_category") {
                try {
                    app.lawnchair.data.category.service.CategoryService.INSTANCE.get(context).removeComponentKeysFromAllCategories(listOf(draggedAppKey, targetAppKey))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove component keys from categories", e)
                }
            }
            val folderId = app.lawnchair.data.folder.service.FolderService.INSTANCE.get(context).createFolderWithItems(
                title = "Folder",
                componentKeys = listOf(targetAppKey, draggedAppKey)
            )
            val newEntry = FolderEntry(id = folderId, title = "Folder", itemComponentKeys = listOf(targetAppKey, draggedAppKey))
            folderList = (folderList.filterNot { it.id == folderId } + newEntry).toMutableList()

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                updateAdapterItems()
                adapter?.notifyDataSetChanged()
                persistCategoryChanges()
            }
        }
    }

    fun addAppToFolder(folderId: Int, folderTitle: String, draggedAppKey: String, targetCategoryId: String?) {
        context.launcher.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!targetCategoryId.isNullOrEmpty() && targetCategoryId != "no_category") {
                try {
                    val catId = targetCategoryId.toInt()
                    app.lawnchair.data.category.service.CategoryService.INSTANCE.get(context).moveAppToCategory(draggedAppKey, catId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to move dragged app to target category", e)
                }
            } else if (targetCategoryId == "no_category") {
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
                componentKeys = updatedKeys
            )
            val updatedEntry = FolderEntry(id = folderId, title = folderTitle, itemComponentKeys = updatedKeys)
            folderList = (folderList.filterNot { it.id == folderId } + updatedEntry).toMutableList()

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                updateAdapterItems()
                adapter?.notifyDataSetChanged()
                persistCategoryChanges()
            }
        }
    }

    fun onAppDraggedOutOfFolder(draggedAppKey: String, folderId: Int) {
        context.launcher.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val folderEntry = folderList.find { it.id == folderId }
            val currentKeys = folderEntry?.itemComponentKeys ?: emptyList()
            val remainingKeys = currentKeys.filterNot { it == draggedAppKey }

            val folderItem = mAdapterItems.find { it.viewType == BaseAllAppsAdapter.VIEW_TYPE_FOLDER && it.folderInfo?.id == folderId }
            val categoryId = folderItem?.categoryId ?: categoryList.find { cat ->
                cat.itemComponentKeys.any { key -> currentKeys.contains(key) }
            }?.id?.toString()

            if (remainingKeys.size < 2) {
                try {
                    app.lawnchair.data.folder.service.FolderService.INSTANCE.get(context).deleteFolderInfo(folderId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete folder info", e)
                }
                folderList = folderList.filterNot { it.id == folderId }.toMutableList()
            } else {
                val title = folderEntry?.title ?: "Folder"
                try {
                    app.lawnchair.data.folder.service.FolderService.INSTANCE.get(context).updateFolderWithItems(
                        folderInfoId = folderId,
                        title = title,
                        componentKeys = remainingKeys
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update folder info", e)
                }
                val updatedEntry = FolderEntry(id = folderId, title = title, itemComponentKeys = remainingKeys)
                folderList = (folderList.filterNot { it.id == folderId } + updatedEntry).toMutableList()
            }

            if (!categoryId.isNullOrEmpty() && categoryId != "no_category") {
                try {
                    val catId = categoryId.toInt()
                    app.lawnchair.data.category.service.CategoryService.INSTANCE.get(context).moveAppToCategory(draggedAppKey, catId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to move dragged-out app to category", e)
                }
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                updateAdapterItems()
                adapter?.notifyDataSetChanged()
                persistCategoryChanges()
            }
        }
    }

    fun onAppRemovedFromFolder(draggedAppKey: String, folderId: Int) {
        val folderEntry = folderList.find { it.id == folderId }
        val currentKeys = folderEntry?.itemComponentKeys ?: emptyList()
        val remainingKeys = currentKeys.filterNot { it == draggedAppKey }

        val folderIndex = mAdapterItems.indexOfFirst {
            it.viewType == BaseAllAppsAdapter.VIEW_TYPE_FOLDER && it.folderInfo?.id == folderId
        }

        if (remainingKeys.size < 2) {
            folderList = folderList.filterNot { it.id == folderId }.toMutableList()
            if (folderIndex != -1) {
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
            val updatedEntry = folderEntry?.copy(itemComponentKeys = remainingKeys)
            if (updatedEntry != null) {
                folderList = (folderList.filterNot { it.id == folderId } + updatedEntry).toMutableList()
            }
            if (folderIndex != -1) {
                val folderItem = mAdapterItems[folderIndex]
                folderItem.folderInfo?.getContents()?.removeIf { info ->
                    (info as? AppInfo)?.toComponentKey()?.toString() == draggedAppKey
                }
                adapter?.notifyItemChanged(folderIndex)
            }
        }
    }

    fun onFolderDragOver(appInfo: AppInfo, targetPos: Int) {
        val appKey = appInfo.toComponentKey().toString()
        val existingIndex = mAdapterItems.indexOfFirst {
            it.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON &&
                it.itemInfo?.toComponentKey()?.toString() == appKey
        }

        if (targetPos !in mAdapterItems.indices) return

        val targetItem = mAdapterItems[targetPos]
        val targetCatId = if (targetItem.viewType == BaseAllAppsAdapter.VIEW_TYPE_CATEGORY_HEADER) {
            val targetCat = categoryList.find { it.title == targetItem.sectionTitle }
            targetCat?.id?.toString() ?: "no_category"
        } else {
            targetItem.categoryId
        } ?: "no_category"

        val effectivePos = if (targetItem.viewType == BaseAllAppsAdapter.VIEW_TYPE_CATEGORY_HEADER) {
            (targetPos + 1).coerceAtMost(mAdapterItems.size)
        } else {
            targetPos
        }

        if (existingIndex == -1) {
            val newItem = AdapterItem.asApp(appInfo).apply {
                categoryId = targetCatId
            }
            mAdapterItems.add(effectivePos, newItem)
            adapter?.notifyItemInserted(effectivePos)
        } else if (existingIndex != effectivePos) {
            val item = mAdapterItems.removeAt(existingIndex)
            item.categoryId = targetCatId
            mAdapterItems.add(effectivePos, item)
            adapter?.notifyItemMoved(existingIndex, effectivePos)
        }
    }

    fun onAppDroppedFromFolderAtPosition(appInfo: AppInfo, folderId: Int) {
        val draggedAppKey = appInfo.toComponentKey().toString()
        context.launcher.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val folderEntry = folderList.find { it.id == folderId }
            val currentKeys = folderEntry?.itemComponentKeys ?: emptyList()
            val remainingKeys = currentKeys.filterNot { it == draggedAppKey }

            if (remainingKeys.size < 2) {
                try {
                    app.lawnchair.data.folder.service.FolderService.INSTANCE.get(context).deleteFolderInfo(folderId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete folder info", e)
                }
                folderList = folderList.filterNot { it.id == folderId }.toMutableList()
            } else {
                val title = folderEntry?.title ?: "Folder"
                try {
                    app.lawnchair.data.folder.service.FolderService.INSTANCE.get(context).updateFolderWithItems(
                        folderInfoId = folderId,
                        title = title,
                        componentKeys = remainingKeys
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update folder info", e)
                }
                val updatedEntry = FolderEntry(id = folderId, title = title, itemComponentKeys = remainingKeys)
                folderList = (folderList.filterNot { it.id == folderId } + updatedEntry).toMutableList()
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                persistCategoryChanges()
                updateAdapterItems()
                adapter?.notifyDataSetChanged()
            }
        }
    }

    fun setupCategoryTouchHelper(recyclerView: androidx.recyclerview.widget.RecyclerView) {
        val callback = AllAppsCategoryTouchHelperCallback(this)
        val helper = androidx.recyclerview.widget.ItemTouchHelper(callback)
        helper.attachToRecyclerView(recyclerView)
        itemTouchHelper = helper
    }

    override fun onIdpChanged(modelPropertiesChanged: Boolean) {
        onAppsUpdated()
    }
}
