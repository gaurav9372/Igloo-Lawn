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
import com.android.launcher3.popup.PopupContainerWithArrow
import com.android.launcher3.views.BubbleTextHolder

/**
 * ItemTouchHelper.Callback for in-drawer category drag-and-drop reordering.
 *
 * Features:
 * - Drag and drop to reorder apps within and across categories.
 * - Holding icon stationary (~600ms after long-press drag starts) triggers the app icon context menu
 *   (App info, Uninstall, Customize).
 */
class AllAppsCategoryTouchHelperCallback(
    private val list: LawnchairAlphabeticalAppsList<*>,
) : ItemTouchHelper.Callback() {

    private companion object {
        /** Touch slop in dp — if drag displacement exceeds this, treat as drag and cancel menu timer. */
        const val DRAG_TOUCH_SLOP_DP = 16f

        /** Delay in ms after long-press drag starts to trigger context menu if held stationary. */
        const val HOLD_MENU_DELAY_MS = 600L
    }

    private var attachedRecyclerView: RecyclerView? = null
    private var draggingView: View? = null
    private var draggingHolder: RecyclerView.ViewHolder? = null

    private val menuHandler = Handler(Looper.getMainLooper())
    private var menuRunnable: Runnable? = null
    private var hasMovedBeyondSlop = false
    private var isMenuShowing = false

    private var activeHoverHolder: RecyclerView.ViewHolder? = null
    private var hoverTargetItem: BaseAllAppsAdapter.AdapterItem? = null
    private var initialDraggedItem: BaseAllAppsAdapter.AdapterItem? = null

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            if (dy != 0 || dx != 0) {
                hasMovedBeyondSlop = true
                cancelMenuTimer()
            }
        }
    }

    override fun isLongPressDragEnabled(): Boolean = true

    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int {
        if (attachedRecyclerView != recyclerView) {
            attachedRecyclerView?.removeOnScrollListener(scrollListener)
            attachedRecyclerView = recyclerView
            recyclerView.addOnScrollListener(scrollListener)
        }
        val launcher = Launcher.getLauncher(recyclerView.context)
        if (launcher.appsView != null && launcher.appsView.isSearching) {
            return makeMovementFlags(0, 0)
        }

        val pos = viewHolder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return makeMovementFlags(0, 0)
        val items = list.adapterItems
        if (pos in items.indices) {
            val item = items[pos]
            if (item.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON || item.viewType == BaseAllAppsAdapter.VIEW_TYPE_FOLDER) {
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
            hasMovedBeyondSlop = false
            isMenuShowing = false

            val pos = viewHolder.bindingAdapterPosition
            initialDraggedItem = list.adapterItems.getOrNull(pos)

            viewHolder.itemView.animate()
                .scaleX(1.15f)
                .scaleY(1.15f)
                .setDuration(150)
                .start()
            viewHolder.itemView.elevation = 20f

            val itemView = viewHolder.itemView
            val runnable = Runnable {
                val rv = attachedRecyclerView ?: return@Runnable
                if (!hasMovedBeyondSlop && !isMenuShowing) {
                    showIconContextMenu(rv, itemView)
                }
            }
            menuRunnable = runnable
            menuHandler.postDelayed(runnable, HOLD_MENU_DELAY_MS)
        } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
            draggingHolder = null
            draggingView = null
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

        // When actively hovering over another icon/folder center to create/add to folder, pause item shifting
        if (activeHoverHolder != null) {
            return false
        }

        val isFromMovable = fromItem.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON || fromItem.viewType == BaseAllAppsAdapter.VIEW_TYPE_FOLDER
        val isToMovable = toItem.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON || toItem.viewType == BaseAllAppsAdapter.VIEW_TYPE_FOLDER || toItem.viewType == BaseAllAppsAdapter.VIEW_TYPE_CATEGORY_HEADER

        if (isFromMovable && isToMovable) {
            if (toItem.viewType == BaseAllAppsAdapter.VIEW_TYPE_CATEGORY_HEADER) {
                val targetCat = list.categoryList.find { it.title == toItem.sectionTitle }
                val targetCatId = targetCat?.id?.toString() ?: "no_category"
                fromItem.categoryId = targetCatId
            } else if (!toItem.categoryId.isNullOrEmpty()) {
                fromItem.categoryId = toItem.categoryId
            }

            items.removeAt(fromPos)
            items.add(toPos, fromItem)
            recyclerView.adapter?.notifyItemMoved(fromPos, toPos)
            list.persistCategoryChanges()
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
        if (isMenuShowing) return

        val itemView = viewHolder.itemView
        val density = itemView.context.resources.displayMetrics.density

        val distancePx = Math.hypot(dX.toDouble(), dY.toDouble()).toFloat()
        val slopPx = DRAG_TOUCH_SLOP_DP * density
        if (distancePx > slopPx) {
            hasMovedBeyondSlop = true
            cancelMenuTimer()
        }

        checkHoverTarget(recyclerView, viewHolder, dX, dY)
    }

    private fun checkHoverTarget(
        recyclerView: RecyclerView,
        draggedHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
    ) {
        val draggedView = draggedHolder.itemView
        val dragCx = draggedView.left + dX + draggedView.width / 2f
        val dragCy = draggedView.top + dY + draggedView.height / 2f

        var newHoverTarget: RecyclerView.ViewHolder? = null
        val items = list.adapterItems

        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val childHolder = recyclerView.getChildViewHolder(child) ?: continue
            if (childHolder == draggedHolder) continue

            val pos = childHolder.bindingAdapterPosition
            if (pos !in items.indices) continue
            val item = items[pos]
            if (item.viewType != BaseAllAppsAdapter.VIEW_TYPE_ICON && item.viewType != BaseAllAppsAdapter.VIEW_TYPE_FOLDER) continue

            val targetCx = child.left + child.translationX + child.width / 2f
            val targetCy = child.top + child.translationY + child.height / 2f
            val dist = Math.hypot((dragCx - targetCx).toDouble(), (dragCy - targetCy).toDouble()).toFloat()
            val hoverRadius = child.width * 0.35f

            if (dist < hoverRadius) {
                newHoverTarget = childHolder
                break
            }
        }

        if (newHoverTarget != activeHoverHolder) {
            clearHoverTarget()
            if (newHoverTarget != null) {
                activeHoverHolder = newHoverTarget
                val targetPos = newHoverTarget.bindingAdapterPosition
                hoverTargetItem = items.getOrNull(targetPos)

                newHoverTarget.itemView.animate()
                    .scaleX(1.18f)
                    .scaleY(1.18f)
                    .setDuration(120)
                    .start()
                val folderIcon = (newHoverTarget.itemView as? com.android.launcher3.folder.FolderIcon)
                    ?: (newHoverTarget.itemView.findViewById(com.android.launcher3.R.id.folder_icon_name) as? View)?.parent as? com.android.launcher3.folder.FolderIcon
                folderIcon?.onDragEnter(null)
            }
        }
    }

    private fun clearHoverTarget() {
        activeHoverHolder?.let { holder ->
            holder.itemView.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(120)
                .start()
            val folderIcon = (holder.itemView as? com.android.launcher3.folder.FolderIcon)
                ?: (holder.itemView.findViewById(com.android.launcher3.R.id.folder_icon_name) as? View)?.parent as? com.android.launcher3.folder.FolderIcon
            folderIcon?.onDragExit()
        }
        activeHoverHolder = null
        hoverTargetItem = null
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

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        cancelMenuTimer()

        val targetItem = hoverTargetItem
        val draggedItem = initialDraggedItem
        clearHoverTarget()
        initialDraggedItem = null

        if (targetItem != null && draggedItem != null && targetItem != draggedItem) {
            if (draggedItem.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON) {
                val draggedAppKey = draggedItem.itemInfo?.toComponentKey()?.toString()
                val targetCategoryId = targetItem.categoryId

                if (targetItem.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON) {
                    val targetAppKey = targetItem.itemInfo?.toComponentKey()?.toString()
                    if (draggedAppKey != null && targetAppKey != null && draggedAppKey != targetAppKey) {
                        viewHolder.itemView.translationX = 0f
                        viewHolder.itemView.translationY = 0f
                        viewHolder.itemView.scaleX = 1.0f
                        viewHolder.itemView.scaleY = 1.0f
                        viewHolder.itemView.alpha = 1.0f
                        viewHolder.itemView.elevation = 0f
                        list.createFolderWithApps(targetAppKey, draggedAppKey, targetCategoryId)
                        return
                    }
                } else if (targetItem.viewType == BaseAllAppsAdapter.VIEW_TYPE_FOLDER) {
                    val folderId = targetItem.folderInfo?.id
                    val folderTitle = targetItem.folderInfo?.title?.toString() ?: "Folder"
                    if (draggedAppKey != null && folderId != null) {
                        viewHolder.itemView.translationX = 0f
                        viewHolder.itemView.translationY = 0f
                        viewHolder.itemView.scaleX = 1.0f
                        viewHolder.itemView.scaleY = 1.0f
                        viewHolder.itemView.alpha = 1.0f
                        viewHolder.itemView.elevation = 0f
                        list.addAppToFolder(folderId, folderTitle, draggedAppKey, targetCategoryId)
                        return
                    }
                }
            }
        }

        if (!isMenuShowing) {
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
