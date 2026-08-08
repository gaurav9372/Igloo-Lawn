package app.lawnchair.data.category.model

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lawnchair.data.category.CategoryEntry
import app.lawnchair.data.category.service.CategoryService
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.ReloadHelper
import app.lawnchair.preferences2.firstCached
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CategoryViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository: CategoryService = CategoryService.INSTANCE.get(application)
    private val prefs2 = PreferenceManager2.getInstance(application)

    val categories: StateFlow<List<CategoryEntry>?> = repository.getCategoriesFlow()
        .map { list ->
            val orderString = prefs2.categoryOrder.firstCached()
            if (orderString.isBlank()) {
                list
            } else {
                val orderList = orderString.split(",").mapNotNull { it.toIntOrNull() }
                list.sortedBy { entry ->
                    val idx = orderList.indexOf(entry.id)
                    if (idx != -1) idx else Int.MAX_VALUE
                }
            }
        }
        .distinctUntilChanged()
        .catch { exception ->
            Log.e("CategoryViewModel", "Error in categories flow", exception)
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null,
        )

    val allCategoryComponentKeys: StateFlow<Set<String>?> = categories
        .map { categoryList ->
            categoryList?.flatMap { category -> category.itemComponentKeys }
                ?.toSet()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null,
        )

    val allCategoryPackages: StateFlow<Set<String>?> = categories
        .map { categoryList ->
            categoryList?.flatMap { category -> category.itemComponentKeys }
                ?.map { key -> key.substringBefore("/") }
                ?.toSet()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null,
        )

    private val reloadHelper = ReloadHelper(application)

    fun getCategoryFlowForId(categoryId: Int): Flow<CategoryEntry?> {
        return categories.map { list -> list?.find { it.id == categoryId } }
    }

    fun renameCategory(categoryId: Int, title: String) {
        viewModelScope.launch {
            repository.renameCategoryInfo(categoryId, title)
            reloadHelper.reloadGrid()
        }
    }

    fun updateCategoryItems(id: Int, title: String, componentKeys: List<String>) {
        viewModelScope.launch {
            repository.updateCategoryWithItems(id, title, componentKeys)
        }
    }

    fun createCategory(title: String) {
        viewModelScope.launch {
            repository.saveCategoryInfo(title)
        }
    }

    fun reorderCategories(orderedIds: List<Int>) {
        viewModelScope.launch {
            prefs2.categoryOrder.set(orderedIds.joinToString(","))
            val dbCategoryIds = orderedIds.filter { it != -100 }
            repository.reorderCategories(dbCategoryIds)
            reloadHelper.reloadGrid()
        }
    }

    fun deleteCategory(id: Int) {
        viewModelScope.launch {
            repository.deleteCategoryInfo(id)
            reloadHelper.reloadGrid()
        }
    }
}
