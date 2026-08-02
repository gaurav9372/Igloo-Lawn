package app.lawnchair.allapps

import android.graphics.Canvas
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.Launcher
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.dragndrop.DragOptions

/**
 * ItemTouchHelper.Callback for in-drawer category drag-and-drop reordering.
 *
 * When the user drags an icon to within [TOP_EDGE_THRESHOLD_DP] dp of the top of the screen,
 * the ItemTouchHelper drag is cancelled and the launcher's native beginDragShared is invoked,
 * allowing the icon to be placed on the home screen — exactly as other launchers behave.
 */
class AllAppsCategoryTouchHelperCallback(
    private val list: LawnchairAlphabeticalAppsList<*>,
) : ItemTouchHelper.Callback() {

    private companion object {
        /** Top of screen threshold in dp — drag above this to hand off to homescreen. */
        const val TOP_EDGE_THRESHOLD_DP = 80f
    }

    private var attachedRecyclerView: RecyclerView? = null
    private var pendingHomescreenHandoff = false
    private var draggingView: View? = null
    private var draggingHolder: RecyclerView.ViewHolder? = null

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
                val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                return makeMovementFlags(dragFlags, 0)
            }
        }
        return makeMovementFlags(0, 0)
    }

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
            draggingHolder = viewHolder
            draggingView = viewHolder.itemView
            pendingHomescreenHandoff = false
            viewHolder.itemView.animate()
                .scaleX(1.15f)
                .scaleY(1.15f)
                .setDuration(150)
                .start()
            viewHolder.itemView.elevation = 20f
        } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
            draggingHolder = null
            draggingView = null
            pendingHomescreenHandoff = false
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

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)

        if (!isCurrentlyActive || actionState != ItemTouchHelper.ACTION_STATE_DRAG) return
        if (pendingHomescreenHandoff) return

        val itemView = viewHolder.itemView
        val density = itemView.context.resources.displayMetrics.density
        val thresholdPx = TOP_EDGE_THRESHOLD_DP * density

        // Compute the top of the dragged icon in screen coordinates
        val location = IntArray(2)
        itemView.getLocationOnScreen(location)
        val itemScreenTop = location[1].toFloat() + dY

        if (itemScreenTop < thresholdPx) {
            pendingHomescreenHandoff = true
            // Grab the view reference before clearing so we can start drag on it
            val dragView = itemView
            // Post to next frame so ItemTouchHelper finishes its current draw pass cleanly
            recyclerView.post {
                handOffToHomescreen(recyclerView, dragView)
            }
        }
    }

    /**
     * Cancels the ItemTouchHelper drag and starts the launcher's native beginDragShared,
     * allowing the icon to be placed anywhere on the home screen.
     */
    private fun handOffToHomescreen(recyclerView: RecyclerView, itemView: View) {
        try {
            val launcher = Launcher.getLauncher(itemView.context)

            // Reset item visual state immediately
            itemView.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(100)
                .start()
            itemView.elevation = 0f

            // Cancel the ItemTouchHelper drag by detach + re-attach
            val helper = list.itemTouchHelper
            helper?.attachToRecyclerView(null)
            helper?.attachToRecyclerView(recyclerView)

            // Persist any order changes that happened during the drag
            list.persistCategoryChanges()

            // Hand off to the launcher drag system (opens homescreen for dropping)
            launcher.workspace.beginDragShared(
                itemView,
                launcher.appsView,
                DragOptions(),
            )
        } catch (e: Exception) {
            // If handoff fails (e.g. view detached), silently ignore — the user can try again
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        // Only animate back if we didn't already hand off (hand-off resets visuals itself)
        if (!pendingHomescreenHandoff) {
            viewHolder.itemView.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(150)
                .start()
            viewHolder.itemView.elevation = 0f
            list.persistCategoryChanges()
        }
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
}
