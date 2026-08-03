package app.lawnchair.allapps

import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.BubbleTextView
import com.android.launcher3.Launcher
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.dragndrop.DragOptions
import com.android.launcher3.popup.PopupContainerWithArrow
import com.android.launcher3.views.BubbleTextHolder

/**
 * ItemTouchHelper.Callback for in-drawer category drag-and-drop reordering.
 *
 * Features:
 * - Drag and drop to reorder apps within and across categories.
 * - Drag to top edge of screen to hand off to homescreen.
 * - Holding icon stationary (~600ms after long-press drag starts) triggers the app icon context menu
 *   (App info, Uninstall, Customize).
 */
class AllAppsCategoryTouchHelperCallback(
    private val list: LawnchairAlphabeticalAppsList<*>,
) : ItemTouchHelper.Callback() {

    private companion object {
        /** Top of screen threshold in dp — drag above this to hand off to homescreen. */
        const val TOP_EDGE_THRESHOLD_DP = 80f

        /** Touch slop in dp — if drag displacement exceeds this, treat as drag and cancel menu timer. */
        const val DRAG_TOUCH_SLOP_DP = 16f

        /** Delay in ms after long-press drag starts to trigger context menu if held stationary. */
        const val HOLD_MENU_DELAY_MS = 600L
    }

    private var attachedRecyclerView: RecyclerView? = null
    private var pendingHomescreenHandoff = false
    private var draggingView: View? = null
    private var draggingHolder: RecyclerView.ViewHolder? = null

    private val menuHandler = Handler(Looper.getMainLooper())
    private var menuRunnable: Runnable? = null
    private var hasMovedBeyondSlop = false
    private var isMenuShowing = false

    override fun isLongPressDragEnabled(): Boolean = true

    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int {
        if (attachedRecyclerView != recyclerView) {
            attachedRecyclerView = recyclerView
            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0 || dx != 0) {
                        hasMovedBeyondSlop = true
                        cancelMenuTimer()
                    }
                }
            })
        }
        val launcher = Launcher.getLauncher(recyclerView.context)
        if (launcher.appsView != null && launcher.appsView.isSearching) {
            return makeMovementFlags(0, 0)
        }

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
        cancelMenuTimer()

        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
            draggingHolder = viewHolder
            draggingView = viewHolder.itemView
            pendingHomescreenHandoff = false
            hasMovedBeyondSlop = false
            isMenuShowing = false

            viewHolder.itemView.animate()
                .scaleX(1.15f)
                .scaleY(1.15f)
                .setDuration(150)
                .start()
            viewHolder.itemView.elevation = 20f

            val itemView = viewHolder.itemView
            val runnable = Runnable {
                val rv = attachedRecyclerView ?: return@Runnable
                if (!hasMovedBeyondSlop && !pendingHomescreenHandoff && !isMenuShowing) {
                    showIconContextMenu(rv, itemView)
                }
            }
            menuRunnable = runnable
            menuHandler.postDelayed(runnable, HOLD_MENU_DELAY_MS)
        } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
            draggingHolder = null
            draggingView = null
            pendingHomescreenHandoff = false
            hasMovedBeyondSlop = false
        }
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean {
        hasMovedBeyondSlop = true
        cancelMenuTimer()

        val fromPos = viewHolder.bindingAdapterPosition
        val toPos = target.bindingAdapterPosition
        val items = list.adapterItems

        if (fromPos !in items.indices || toPos !in items.indices) return false

        val fromItem = items[fromPos]
        val toItem = items[toPos]

        if (fromItem.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON && !fromItem.categoryId.isNullOrEmpty()) {
            val targetCatId = when {
                toItem.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON && !toItem.categoryId.isNullOrEmpty() -> toItem.categoryId
                toItem.viewType == BaseAllAppsAdapter.VIEW_TYPE_CATEGORY_HEADER -> {
                    val targetCat = list.categoryList.find { it.title == toItem.sectionTitle }
                    targetCat?.id?.toString() ?: "no_category"
                }
                else -> null
            }
            if (targetCatId != null) {
                if (fromItem.categoryId != targetCatId) {
                    fromItem.categoryId = targetCatId
                }
                items.removeAt(fromPos)
                items.add(toPos, fromItem)
                recyclerView.adapter?.notifyItemMoved(fromPos, toPos)
                return true
            }
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
        if (pendingHomescreenHandoff || isMenuShowing) return

        val itemView = viewHolder.itemView
        val density = itemView.context.resources.displayMetrics.density

        val distancePx = Math.hypot(dX.toDouble(), dY.toDouble()).toFloat()
        val slopPx = DRAG_TOUCH_SLOP_DP * density
        if (distancePx > slopPx) {
            hasMovedBeyondSlop = true
            cancelMenuTimer()
        }

        val thresholdPx = TOP_EDGE_THRESHOLD_DP * density

        val location = IntArray(2)
        itemView.getLocationOnScreen(location)
        val itemScreenTop = location[1].toFloat() + dY

        if (itemScreenTop < thresholdPx && !pendingHomescreenHandoff) {
            pendingHomescreenHandoff = true
            cancelMenuTimer()
            val dragView = itemView
            recyclerView.post {
                handOffToHomescreen(recyclerView, dragView)
            }
        }
    }

    private fun cancelMenuTimer() {
        menuRunnable?.let { menuHandler.removeCallbacks(it) }
        menuRunnable = null
    }

    private fun showIconContextMenu(recyclerView: RecyclerView, itemView: View) {
        try {
            if (!itemView.isAttachedToWindow) return
            isMenuShowing = true
            cancelMenuTimer()

            itemView.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(100)
                .start()
            itemView.elevation = 0f

            val helper = list.itemTouchHelper
            helper?.attachToRecyclerView(null)
            helper?.attachToRecyclerView(recyclerView)

            list.persistCategoryChanges()

            val bubbleTextView = (itemView as? BubbleTextView)
                ?: (itemView as? BubbleTextHolder)?.bubbleText

            if (bubbleTextView != null) {
                PopupContainerWithArrow.showForIcon(bubbleTextView)
            }
        } catch (e: Exception) {
            // Silently handle if view is detached
        }
    }

    /**
     * Cancels the ItemTouchHelper drag and starts the launcher's native beginDragShared,
     * allowing the icon to be placed anywhere on the home screen.
     */
    private fun handOffToHomescreen(recyclerView: RecyclerView, itemView: View) {
        try {
            cancelMenuTimer()
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
        cancelMenuTimer()
        // Only animate back if we didn't already hand off or show menu (those reset visuals themselves)
        if (!pendingHomescreenHandoff && !isMenuShowing) {
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
