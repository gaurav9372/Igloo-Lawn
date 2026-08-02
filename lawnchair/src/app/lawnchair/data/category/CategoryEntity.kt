package app.lawnchair.data.category

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "Categories")
data class CategoryInfoEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val hide: Boolean = false,
    val rank: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "CategoryItems",
    foreignKeys = [
        ForeignKey(
            entity = CategoryInfoEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["item_info"], unique = true),
    ],
)
data class CategoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val categoryId: Int,
    val rank: Int = 0,
    @ColumnInfo(name = "item_info") val componentKey: String?,
    val timestamp: Long = System.currentTimeMillis(),
)

@Immutable
data class CategoryEntry(
    val id: Int,
    val title: String,
    val hide: Boolean = false,
    val itemComponentKeys: List<String> = emptyList(),
)
