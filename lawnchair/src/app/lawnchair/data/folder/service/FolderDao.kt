package app.lawnchair.data.folder.service

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
import app.lawnchair.data.folder.FolderInfoEntity
import app.lawnchair.data.folder.FolderItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderInfoEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolderItems(items: List<FolderItemEntity>)

    @Transaction
    suspend fun createFolderWithItems(title: String, componentKeys: List<String>): Int {
        val folderId = insertFolder(FolderInfoEntity(title = title)).toInt()
        insertFolderItems(
            componentKeys.mapIndexed { index, componentKey ->
                FolderItemEntity(
                    folderId = folderId,
                    rank = index,
                    componentKey = componentKey,
                )
            },
        )
        return folderId
    }

    @Query("SELECT * FROM Folders")
    @Transaction
    fun getAllFoldersWithItems(): Flow<List<FolderWithItems>>

    @Query("SELECT * FROM Folders WHERE id = :id LIMIT 1")
    suspend fun getFolderById(id: Int): FolderInfoEntity?

    @Query("DELETE FROM FolderItems WHERE folderId = :folderId")
    suspend fun deleteFolderItemsByFolderId(folderId: Int)

    @Query("UPDATE Folders SET title = :title, timestamp = :timestamp WHERE id = :id")
    suspend fun updateFolderTitle(id: Int, title: String, timestamp: Long = System.currentTimeMillis())

    @Transaction
    suspend fun replaceFolderItems(folderId: Int, title: String, items: List<FolderItemEntity>) {
        if (getFolderById(folderId) == null) return
        updateFolderTitle(folderId, title)
        deleteFolderItemsByFolderId(folderId)
        insertFolderItems(items.map { it.copy(folderId = folderId) })
    }

    @Query(
        value = """
                UPDATE Folders
                SET hide = :hide, timestamp = :timestamp
                WHERE id = :folderId
            """,
    )
    suspend fun setFolderHidden(
        folderId: Int,
        hide: Boolean,
        timestamp: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM Folders WHERE id = :folderId")
    suspend fun deleteFolder(folderId: Int)

    @Query("DELETE FROM CategoryItems WHERE item_info = :componentKey")
    suspend fun removeAppFromCategories(componentKey: String)

    @Query("SELECT * FROM Categories WHERE id = :categoryId LIMIT 1")
    suspend fun getCategoryById(categoryId: Int): CategoryInfoEntity?

    @Query("SELECT MAX(rank) FROM CategoryItems WHERE categoryId = :categoryId")
    suspend fun getMaxCategoryRank(categoryId: Int): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategoryItem(item: CategoryItemEntity)

    @Transaction
    suspend fun moveAppOutOfFolder(
        folderId: Int,
        folderTitle: String,
        remainingFolderKeys: List<String>,
        componentKey: String,
        targetCategoryId: Int?,
    ) {
        if (remainingFolderKeys.size < 2) {
            deleteFolder(folderId)
        } else {
            replaceFolderItems(
                folderId,
                folderTitle,
                remainingFolderKeys.mapIndexed { index, key ->
                    FolderItemEntity(folderId = folderId, rank = index, componentKey = key)
                },
            )
        }

        removeAppFromCategories(componentKey)
        if (targetCategoryId != null && targetCategoryId > 0 &&
            getCategoryById(targetCategoryId) != null
        ) {
            insertCategoryItem(
                CategoryItemEntity(
                    categoryId = targetCategoryId,
                    rank = (getMaxCategoryRank(targetCategoryId) ?: -1) + 1,
                    componentKey = componentKey,
                ),
            )
        }
    }

    @RawQuery
    suspend fun checkpoint(supportSQLiteQuery: SupportSQLiteQuery): Int
}

data class FolderWithItems(
    @Embedded val folder: FolderInfoEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "folderId",
    )
    val items: List<FolderItemEntity>,
)
