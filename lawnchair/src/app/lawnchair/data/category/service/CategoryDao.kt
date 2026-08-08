package app.lawnchair.data.category.service

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Relation
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import app.lawnchair.data.category.CategoryInfoEntity
import app.lawnchair.data.category.CategoryItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryInfoEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategoryItems(items: List<CategoryItemEntity>)

    @Query("SELECT * FROM Categories ORDER BY rank ASC, id ASC")
    @Transaction
    fun getAllCategoriesWithItems(): Flow<List<CategoryWithItems>>

    @Query("DELETE FROM CategoryItems WHERE categoryId = :categoryId")
    suspend fun deleteCategoryItemsByCategoryId(categoryId: Int)

    @Query("SELECT * FROM Categories WHERE id = :id LIMIT 1")
    suspend fun getCategoryById(id: Int): CategoryInfoEntity?

    @Query("DELETE FROM CategoryItems WHERE item_info IN (:componentKeys)")
    suspend fun removeComponentKeysFromAllCategories(componentKeys: List<String>)

    @Query("UPDATE Categories SET title = :title, timestamp = :timestamp WHERE id = :id")
    suspend fun updateCategoryTitle(id: Int, title: String, timestamp: Long = System.currentTimeMillis())

    @Transaction
    suspend fun replaceCategoryItems(categoryId: Int, title: String, items: List<CategoryItemEntity>) {
        if (getCategoryById(categoryId) == null) return
        updateCategoryTitle(categoryId, title)
        deleteCategoryItemsByCategoryId(categoryId)
        val validKeys = items.mapNotNull { it.componentKey }
        if (validKeys.isNotEmpty()) {
            removeComponentKeysFromAllCategories(validKeys)
        }
        insertCategoryItems(items.map { it.copy(categoryId = categoryId) })
    }

    @Query(
        value = """
                UPDATE Categories
                SET hide = :hide, timestamp = :timestamp
                WHERE id = :categoryId
            """,
    )
    suspend fun setCategoryHidden(
        categoryId: Int,
        hide: Boolean,
        timestamp: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE Categories SET rank = :rank WHERE id = :id")
    suspend fun updateCategoryRank(id: Int, rank: Int)

    @Transaction
    suspend fun reorderCategories(orderedIds: List<Int>) {
        orderedIds.forEachIndexed { index, id ->
            updateCategoryRank(id, index)
        }
    }

    @Query("DELETE FROM Categories WHERE id = :categoryId")
    suspend fun deleteCategoryById(categoryId: Int)

    @Transaction
    suspend fun deleteCategory(categoryId: Int) {
        deleteCategoryItemsByCategoryId(categoryId)
        deleteCategoryById(categoryId)
    }

    @Query("SELECT MAX(rank) FROM CategoryItems WHERE categoryId = :categoryId")
    suspend fun getMaxRank(categoryId: Int): Int?

    @Transaction
    suspend fun moveAppsToCategory(componentKeys: List<String>, targetCategoryId: Int) {
        removeComponentKeysFromAllCategories(componentKeys)
        if (targetCategoryId > 0 && getCategoryById(targetCategoryId) != null) {
            val startRank = (getMaxRank(targetCategoryId) ?: 0) + 1
            val items = componentKeys.mapIndexed { index, key ->
                CategoryItemEntity(
                    categoryId = targetCategoryId,
                    rank = startRank + index,
                    componentKey = key,
                )
            }
            insertCategoryItems(items)
        }
    }

    @RawQuery
    suspend fun checkpoint(supportSQLiteQuery: SupportSQLiteQuery): Int
}

data class CategoryWithItems(
    @Embedded val category: CategoryInfoEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "categoryId",
    )
    val items: List<CategoryItemEntity>,
)
