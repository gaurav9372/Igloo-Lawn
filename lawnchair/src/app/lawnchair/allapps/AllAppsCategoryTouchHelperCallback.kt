package app.lawnchair.allapps

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.allapps.BaseAllAppsAdapter

class AllAppsCategoryTouchHelperCallback(
    private val list: LawnchairAlphabeticalAppsList<*>,
    private val onReorderCategoryApps: (categoryId: Int, newComponentKeys: List<String>) -> Unit,
) : ItemTouchHelper.Callback() {

    override fun isLongPressDragEnabled(): Boolean = true

    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int {
        val pos = viewHolder.bindingAdapterPosition
        val items = list.adapterItems
        if (pos in items.indices) {
            val item = items[pos]
            if (item.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON && !item.categoryId.isNullOrEmpty()) {
                val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                return makeMovementFlags(dragFlags, 0)
            }
        }
        return makeMovementFlags(0, 0)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean {
        val fromPos = viewHolder.bindingAdapterPosition
        val toPos = target.bindingAdapterPosition
        val items = list.adapterItems

        if (fromPos !in items.indices || toPos !in items.indices) return false

        val fromItem = items[fromPos]
        val toItem = items[toPos]

        if (fromItem.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON &&
            toItem.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON &&
            !fromItem.categoryId.isNullOrEmpty() &&
            fromItem.categoryId == toItem.categoryId
        ) {
            items.removeAt(fromPos)
            items.add(toPos, fromItem)
            recyclerView.adapter?.notifyItemMoved(fromPos, toPos)
            return true
        }
        return false
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        val pos = viewHolder.bindingAdapterPosition
        val items = list.adapterItems
        if (pos in items.indices) {
            val item = items[pos]
            val catIdInt = item.categoryId?.toIntOrNull() ?: return
            val categoryApps = items.filter {
                it.categoryId == item.categoryId && it.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON
            }.mapNotNull { it.itemInfo?.toComponentKey()?.toString() }
            onReorderCategoryApps(catIdInt, categoryApps)
        }
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
}
