package com.matiasnl.hakiosk.ui.dashboard.grid

import kotlin.math.roundToInt

/**
 * Pixel geometry of a dashboard grid, pure so it can be unit tested and reused by hit-testing (drag
 * & drop) without touching Compose.
 *
 * The viewport is split so exactly [columns] × [rows] cells fit, with a [gutter] between cells and
 * around the outer edge: `viewport = gutter + n × (cell + gutter)`. Rows beyond [rows] extend the
 * content height and scroll. Cell sizes never go negative on tiny viewports.
 */
data class GridMetrics(
    val columns: Int,
    val rows: Int,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val gutter: Float,
) {
    val cellWidth: Float = cellSize(viewportWidth, columns, gutter)
    val cellHeight: Float = cellSize(viewportHeight, rows, gutter)

    /** Left edge of [column], in content pixels. */
    fun left(column: Int): Int = (gutter + column * (cellWidth + gutter)).roundToInt()

    /** Top edge of [row], in content pixels (before scrolling). */
    fun top(row: Int): Int = (gutter + row * (cellHeight + gutter)).roundToInt()

    /** Exclusive right edge of a tile starting at [column] spanning [colSpan]. Rounded from the same float math as [left], so adjacent tiles never leave 1px seams. */
    fun right(column: Int, colSpan: Int): Int =
        (gutter + column * (cellWidth + gutter) + span(colSpan, cellWidth)).roundToInt()

    fun bottom(row: Int, rowSpan: Int): Int =
        (gutter + row * (cellHeight + gutter) + span(rowSpan, cellHeight)).roundToInt()

    fun left(placement: GridPlacement): Int = left(placement.column)
    fun top(placement: GridPlacement): Int = top(placement.row)
    fun width(placement: GridPlacement): Int = (right(placement.column, placement.colSpan) - left(placement.column)).coerceAtLeast(0)
    fun height(placement: GridPlacement): Int = (bottom(placement.row, placement.rowSpan) - top(placement.row)).coerceAtLeast(0)

    /**
     * Total content height for [totalRows] rows. Never less than the viewport, and exactly the
     * viewport when the content fits in [rows], so rounding can't create a 1px scroll range.
     */
    fun contentHeight(totalRows: Int): Int {
        if (totalRows <= rows) return viewportHeight
        return (gutter + totalRows * (cellHeight + gutter)).roundToInt().coerceAtLeast(viewportHeight)
    }

    /**
     * Whether any pixel of [placement] is inside the viewport when the content is scrolled by
     * [scrollOffset] pixels. Touching edges (a tile ending exactly at the viewport top) don't count.
     */
    fun isVisible(placement: GridPlacement, scrollOffset: Int): Boolean {
        val top = top(placement.row)
        val bottom = bottom(placement.row, placement.rowSpan)
        return bottom > scrollOffset && top < scrollOffset + viewportHeight
    }

    private fun span(count: Int, cell: Float): Float = count * cell + (count - 1).coerceAtLeast(0) * gutter

    private companion object {
        fun cellSize(viewport: Int, count: Int, gutter: Float): Float {
            val n = count.coerceAtLeast(1)
            return ((viewport - gutter * (n + 1)) / n).coerceAtLeast(0f)
        }
    }
}
