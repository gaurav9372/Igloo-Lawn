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
                    folderList = folders
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
                        val processedCategoryAppKeys = mutableSetOf<String>()

                        folderList.forEach { folderEntry ->
                            val hasAppsInThisCategory = folderEntry.itemComponentKeys.any { key ->
                                categoryEntry.itemComponentKeys.contains(key)
                            }
                            if (hasAppsInThisCategory) {
                                val folderApps = folderEntry.itemComponentKeys.mapNotNull { keyString ->
                                    if (hiddenApps.contains(keyString)) return@mapNotNull null
                                    val componentKey = ComponentKey.fromString(keyString) ?: return@mapNotNull null
                                    appsStore.getApp(componentKey) as? AppInfo
                                }

                                if (folderApps.size >= 2) {
                                    val folderInfo = FolderInfo().apply {
                                        id = folderEntry.id
                                        title = folderEntry.title
                                        folderApps.forEach { add(it) }
                                    }
                                    val folderAdapterItem = AdapterItem.asFolder(folderInfo)
                                    folderAdapterItem.categoryId = categoryIdStr
                                    mAdapterItems.add(folderAdapterItem)
                                    position++

                                    folderApps.forEach { appInfo ->
                                        val key = appInfo.toComponentKey().toString()
                                        processedCategoryAppKeys.add(key)
                                        assignedAppKeys.add(key)
                                    }
                                }
                            }
                        }

                        resolvedApps.forEach { appInfo ->
                            val appKey = appInfo.toComponentKey().toString()
                            assignedAppKeys.add(appKey)
                            if (!processedCategoryAppKeys.contains(appKey)) {
                                val item = AdapterItem.asApp(appInfo)
                                item.categoryId = categoryIdStr
                                mAdapterItems.add(item)
                                position++
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
                assignedAppKeys.contains(app.toComponentKey().toString())
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
                        val item = AdapterItem.asApp(appInfo)
                        item.categoryId = categoryIdStr
                        mAdapterItems.add(item)
                        position++
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
                        apps.forEach { add(it) }
                    }
                    mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                }
                position++
            }
        } else {
            folderList.forEach { folderEntry ->
                val resolvedApps = folderEntry.itemComponentKeys.mapNotNull { keyString ->
                    val componentKey = ComponentKey.fromString(keyString) ?: return@mapNotNull null
                    appsStore.getApp(componentKey) as? AppInfo
                }

                if (resolvedApps.size >= 2) {
                    val folderInfo = FolderInfo().apply {
                        id = folderEntry.id
                        title = folderEntry.title
                        resolvedApps.forEach { add(it) }
                    }
                    mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                    position++
                    filteredList.addAll(resolvedApps)
                }
            }
            val remainingApps = appList.filterNot { app -> filteredList.contains(app) }
            position = super.addAppsWithSections(remainingApps, position)
        }

        return position
    }

    fun persistCategoryChanges() {
        val updatedList = categoryList.map { categoryEntry ->
            val categoryIdStr = categoryEntry.id.toString()
            val allCategoryApps = if (collapsedCategories.contains(categoryIdStr)) {
                categoryEntry.itemComponentKeys
            } else {
                val visibleCategoryApps = mAdapterItems.filter {
                    it.categoryId == categoryIdStr && it.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON
                }.mapNotNull { it.itemInfo?.toComponentKey()?.toString() }

                val uninstalledCategoryApps = categoryEntry.itemComponentKeys.filter { key ->
                    val ck = ComponentKey.fromString(key)
                    ck == null || appsStore.getApp(ck) == null
                }

                val hiddenCategoryApps = categoryEntry.itemComponentKeys.filter { key ->
                    hiddenApps.contains(key)
                }
                (visibleCategoryApps + uninstalledCategoryApps + hiddenCategoryApps).distinct()
            }

            categoryViewModel.updateCategoryItems(categoryEntry.id, categoryEntry.title, allCategoryApps)
            categoryEntry.copy(itemComponentKeys = allCategoryApps)
        }
        categoryList = updatedList.toMutableList()

        val noCategoryKeys = mAdapterItems.filter {
            it.categoryId == "no_category" && it.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON
        }.mapNotNull { it.itemInfo?.toComponentKey()?.toString() }

        if (noCategoryKeys.isNotEmpty()) {
            prefs.unassignedCategoryOrder.set(noCategoryKeys.joinToString("|"))
            context.launcher.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                app.lawnchair.data.category.service.CategoryService.INSTANCE.get(context).removeComponentKeysFromAllCategories(noCategoryKeys)
            }
        }
    }

    fun createFolderWithApps(targetAppKey: String, draggedAppKey: String, targetCategoryId: String?) {
        context.launcher.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!targetCategoryId.isNullOrEmpty() && targetCategoryId != "no_category") {
                try {
                    val catId = targetCategoryId.toInt()
                    app.lawnchair.data.category.service.CategoryService.INSTANCE.get(context).moveAppToCategory(draggedAppKey, catId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to move dragged app to target category", e)
                }
            }
            app.lawnchair.data.folder.service.FolderService.INSTANCE.get(context).createFolderWithItems(
                title = "Folder",
                componentKeys = listOf(targetAppKey, draggedAppKey)
            )
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onAppsUpdated()
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
            }
            val existingEntry = folderList.find { it.id == folderId }
            val existingKeys = existingEntry?.itemComponentKeys ?: emptyList()
            val updatedKeys = (existingKeys + draggedAppKey).distinct()

            app.lawnchair.data.folder.service.FolderService.INSTANCE.get(context).updateFolderWithItems(
                folderInfoId = folderId,
                title = folderTitle,
                componentKeys = updatedKeys
            )
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onAppsUpdated()
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
