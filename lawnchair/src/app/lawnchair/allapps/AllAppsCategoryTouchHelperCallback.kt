package app.lawnchair.allapps

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.allapps.BaseAllAppsAdapter

class AllAppsCategoryTouchHelperCallback(
    private val list: LawnchairAlphabeticalAppsList<*>,
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

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
            viewHolder.itemView.animate()
                .scaleX(1.15f)
                .scaleY(1.15f)
                .setDuration(150)
                .start()
            viewHolder.itemView.elevation = 20f
        }
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
            !toItem.categoryId.isNullOrEmpty()
        ) {
            if (fromItem.categoryId != toItem.categoryId) {
                fromItem.categoryId = toItem.categoryId
            }
            items.removeAt(fromPos)
            items.add(toPos, fromItem)
            recyclerView.adapter?.notifyItemMoved(fromPos, toPos)
            return true
        }
        return false
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewHolder.itemView.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(150)
            .start()
        viewHolder.itemView.elevation = 0f

        list.persistCategoryChanges()
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
}
