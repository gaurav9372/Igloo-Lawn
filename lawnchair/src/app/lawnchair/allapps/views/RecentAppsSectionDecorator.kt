package app.lawnchair.allapps.views

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.recyclerview.widget.RecyclerView
import app.lawnchair.allapps.LawnchairAlphabeticalAppsList
import app.lawnchair.theme.color.tokens.ColorTokens
import com.android.launcher3.R
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.util.Themes
import com.android.systemui.shared.system.BlurUtils
import com.android.systemui.util.dpToPx

/**
 * ItemDecoration that renders a subtle rounded card background behind
 * the Recent Apps section in AllAppsRecyclerView.
 */
class RecentAppsSectionDecorator(
    private val list: LawnchairAlphabeticalAppsList<*>,
) : RecyclerView.ItemDecoration() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boundsRect = RectF()
    private val path = Path()

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val items = list.adapterItems
        if (items.isEmpty()) return

        var minTop = Float.MAX_VALUE
        var maxBottom = Float.MIN_VALUE
        var hasVisibleRecentIcons = false

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) ?: continue
            val pos = parent.getChildAdapterPosition(child)
            if (pos !in items.indices) continue
            val item = items[pos]

            if (item.categoryId == LawnchairAlphabeticalAppsList.RECENT_CATEGORY_ID &&
                item.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON
            ) {
                val top = child.top + child.translationY
                val bottom = child.bottom + child.translationY
                if (top < minTop) minTop = top
                if (bottom > maxBottom) maxBottom = bottom
                hasVisibleRecentIcons = true
            }
        }

        if (!hasVisibleRecentIcons || minTop >= maxBottom) return

        val context = parent.context
        val resources = context.resources

        val horizontalMargin = 8.dpToPx(resources)
        val verticalPadding = 6.dpToPx(resources)
        val cornerRadius = 18.dpToPx(resources)

        val left = parent.paddingLeft.toFloat() + horizontalMargin
        val right = (parent.width - parent.paddingRight).toFloat() - horizontalMargin
        val top = minTop - verticalPadding
        val bottom = maxBottom + verticalPadding

        val defaultBgColor = Themes.getAttrColor(context, android.R.attr.colorBackground)
        val drawerBgColor = app.lawnchair.util.getAllAppsBackgroundColor(context, defaultBgColor)
        val isDark = androidx.core.graphics.ColorUtils.calculateLuminance(drawerBgColor) < 0.5

        val color = if (isDark) {
            // Subtly brighter than the drawer background (blend 10% white)
            androidx.core.graphics.ColorUtils.blendARGB(drawerBgColor, android.graphics.Color.WHITE, 0.10f)
        } else {
            // In light mode: subtly brighter towards white if background is tinted/grey
            if (androidx.core.graphics.ColorUtils.calculateLuminance(drawerBgColor) > 0.95) {
                androidx.core.graphics.ColorUtils.blendARGB(drawerBgColor, android.graphics.Color.BLACK, 0.04f)
            } else {
                androidx.core.graphics.ColorUtils.blendARGB(drawerBgColor, android.graphics.Color.WHITE, 0.50f)
            }
        }

        if (color == 0) return

        paint.color = color
        boundsRect.set(left, top, right, bottom)
        path.reset()
        path.addRoundRect(boundsRect, cornerRadius, cornerRadius, Path.Direction.CW)
        c.drawPath(path, paint)
    }
}
