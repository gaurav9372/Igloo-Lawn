package app.lawnchair.data.category.service

import android.content.Context
import app.lawnchair.data.AppDatabase
import app.lawnchair.data.category.CategoryEntry
import app.lawnchair.data.category.CategoryInfoEntity
import app.lawnchair.data.category.CategoryItemEntity
import app.lawnchair.util.MainThreadInitializedObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CategoryService(context: Context) {

    private val categoryDao = AppDatabase.INSTANCE.get(context).categoryDao()

    fun getCategoriesFlow(): Flow<List<CategoryEntry>> {
        return categoryDao.getAllCategoriesWithItems().map { list ->
            list.map { it.toCategoryEntry() }
        }
    }

    suspend fun updateCategoryWithItems(categoryId: Int, title: String, componentKeys: List<String>) = withContext(Dispatchers.IO) {
        val items = componentKeys.mapIndexed { index, componentKey ->
            CategoryItemEntity(
                categoryId = categoryId,
                rank = index,
                componentKey = componentKey,
            )
        }
        categoryDao.replaceCategoryItems(categoryId, title, items)
    }

    suspend fun saveCategoryInfo(title: String) = withContext(Dispatchers.IO) {
        categoryDao.insertCategory(CategoryInfoEntity(title = title))
    }

    suspend fun renameCategoryInfo(categoryId: Int, title: String) = withContext(Dispatchers.IO) {
        categoryDao.updateCategoryTitle(categoryId, title)
    }

    suspend fun deleteCategoryInfo(id: Int) = withContext(Dispatchers.IO) {
        categoryDao.deleteCategory(id)
    }

    private fun CategoryWithItems.toCategoryEntry() = CategoryEntry(
        id = category.id,
        title = category.title,
        hide = category.hide,
        itemComponentKeys = items
            .sortedBy { it.rank }
            .mapNotNull { it.componentKey },
    )

    companion object {
        @JvmField
        val INSTANCE = MainThreadInitializedObject { context -> CategoryService(context) }
    }
}
