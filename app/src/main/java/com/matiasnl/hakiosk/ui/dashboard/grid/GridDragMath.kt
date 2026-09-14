package com.matiasnl.hakiosk.ui.dashboard.grid

import kotlin.math.floor

/**
 * Pure hit-testing for drag & drop reordering on a packed dashboard grid. No Compose, no allocation.
 *
 * Coordinates are in *content* pixels (the grid's own space before scrolling): `contentY` is the
 * pointer's viewport y plus the scroll offset. Only indices below `realCount` are real tiles; any
 * trailing placeholder (the edit-mode "＋" tile) sits past them, is never a target and its cell is
 * treated as empty.
 *
 * ## Target rule (stateless, [dropTarget])
 * - Over the dragged tile's own placement, or over a gutter: keep the current index.
 * - Over another real tile: that tile's index, but only once the pointer is inside the tile's inner
 *   area (its central [INNER_FRACTION] on both axes). Its outer ring keeps the current index.
 * - Over an empty cell (or the placeholder's cell): reading-order insertion, i.e. the number of real
 *   tiles other than the dragged one whose placement starts before the pointer's cell, row-major.
 *   Also only inside the cell's inner area. That count can never exceed `realCount - 1`.
 *
 * The inner-area requirement alone is not enough when spans differ: after dropping a 1×1 onto a wide
 * tile the re-pack slides the wide tile under the finger, possibly with the pointer still inside its
 * new inner area, and the next evaluation would move it straight back. [GridDragTracker] adds the
 * hysteresis that makes the rule stable.
 */
object GridDragMath {

    /** Share of a tile's (or empty cell's) width and height, centered, that the pointer must enter to switch target. */
    const val INNER_FRACTION = 0.6f

    /** Index of the real tile (below [realCount]) whose placement contains the point, or -1. */
    fun tileAt(metrics: GridMetrics, placements: List<GridPlacement>, realCount: Int, x: Float, contentY: Float): Int {
        val count = minOf(realCount, placements.size)
        for (index in 0 until count) {
            if (contains(metrics, placements[index], x, contentY, inset = 0f)) return index
        }
        return -1
    }

    /**
     * New index for the tile currently at [draggedIndex] with the pointer at ([x], [contentY]), or
     * [draggedIndex] itself for "no change". [ignoredIndex] is a tile the pointer must not retarget to
     * (see [GridDragTracker]); -1 for none.
     */
    fun dropTarget(
        metrics: GridMetrics,
        placements: List<GridPlacement>,
        realCount: Int,
        draggedIndex: Int,
        x: Float,
        contentY: Float,
        ignoredIndex: Int = -1,
    ): Int {
        val count = minOf(realCount, placements.size)
        if (draggedIndex !in 0 until count) return draggedIndex

        val hit = tileAt(metrics, placements, count, x, contentY)
        if (hit >= 0) {
            if (hit == draggedIndex || hit == ignoredIndex) return draggedIndex
            val inset = (1f - INNER_FRACTION) / 2f
            return if (contains(metrics, placements[hit], x, contentY, inset)) hit else draggedIndex
        }

        val column = cellColumnAt(metrics, x)
        val row = cellRowAt(metrics, contentY)
        if (column < 0 || row < 0) return draggedIndex // Gutter or outside the grid.
        if (!insideCellInnerArea(metrics, column, row, x, contentY)) return draggedIndex

        var before = 0
        for (index in 0 until count) {
            if (index == draggedIndex) continue
            val placement = placements[index]
            if (placement.row < row || (placement.row == row && placement.column < column)) before++
        }
        return before.coerceAtMost(count - 1)
    }

    /**
     * Column of the cell "territory" containing [x]: the cell plus half of each adjacent gutter, so
     * every x maps to a column (clamped to the grid). Used to decide when the pointer clearly left a cell.
     */
    fun territoryColumn(metrics: GridMetrics, x: Float): Int {
        val pitch = metrics.cellWidth + metrics.gutter
        if (pitch <= 0f) return 0
        return floor((x - metrics.gutter / 2f) / pitch).toInt().coerceIn(0, (metrics.columns - 1).coerceAtLeast(0))
    }

    /** Row counterpart of [territoryColumn]; unbounded below because the content scrolls. */
    fun territoryRow(metrics: GridMetrics, contentY: Float): Int {
        val pitch = metrics.cellHeight + metrics.gutter
        if (pitch <= 0f) return 0
        return floor((contentY - metrics.gutter / 2f) / pitch).toInt().coerceAtLeast(0)
    }

    /** New index of the item that was at [index] after moving the item at [from] to [to] (list remove + insert). */
    fun indexAfterMove(index: Int, from: Int, to: Int): Int = when {
        index == from -> to
        from < to && index in (from + 1)..to -> index - 1
        to < from && index in to until from -> index + 1
        else -> index
    }

    /**
     * Autoscroll velocity in px/s for a pointer at [viewportY]: negative (scroll up) inside the top
     * [band], positive inside the bottom one, proportional to how deep the pointer is into the band
     * and capped at [maxSpeed] (also past the viewport edge). Zero elsewhere.
     */
    fun autoscrollVelocity(viewportY: Float, viewportHeight: Int, band: Float, maxSpeed: Float): Float {
        if (band <= 0f || viewportHeight <= 0) return 0f
        val topDepth = band - viewportY
        if (topDepth > 0f) return -maxSpeed * (topDepth / band).coerceAtMost(1f)
        val bottomDepth = viewportY - (viewportHeight - band)
        if (bottomDepth > 0f) return maxSpeed * (bottomDepth / band).coerceAtMost(1f)
        return 0f
    }

    private fun contains(metrics: GridMetrics, placement: GridPlacement, x: Float, y: Float, inset: Float): Boolean =
        contains(
            left = metrics.left(placement).toFloat(),
            top = metrics.top(placement).toFloat(),
            width = metrics.width(placement).toFloat(),
            height = metrics.height(placement).toFloat(),
            x = x,
            y = y,
            inset = inset,
        )

    /** Whether ([x], [y]) is inside the rect shrunk by [inset] × size on every side. */
    private fun contains(left: Float, top: Float, width: Float, height: Float, x: Float, y: Float, inset: Float): Boolean {
        val innerLeft = left + width * inset
        val innerTop = top + height * inset
        return x >= innerLeft && x < innerLeft + width * (1f - 2f * inset) &&
            y >= innerTop && y < innerTop + height * (1f - 2f * inset)
    }

    /** Column whose cell (excluding gutters) contains [x], or -1. */
    private fun cellColumnAt(metrics: GridMetrics, x: Float): Int {
        val pitch = metrics.cellWidth + metrics.gutter
        val offset = x - metrics.gutter
        if (pitch <= 0f || offset < 0f) return -1
        val column = floor(offset / pitch).toInt()
        if (column >= metrics.columns) return -1
        return if (offset - column * pitch < metrics.cellWidth) column else -1
    }

    /** Row whose cell (excluding gutters) contains [contentY], or -1. */
    private fun cellRowAt(metrics: GridMetrics, contentY: Float): Int {
        val pitch = metrics.cellHeight + metrics.gutter
        val offset = contentY - metrics.gutter
        if (pitch <= 0f || offset < 0f) return -1
        val row = floor(offset / pitch).toInt()
        return if (offset - row * pitch < metrics.cellHeight) row else -1
    }

    private fun insideCellInnerArea(metrics: GridMetrics, column: Int, row: Int, x: Float, y: Float): Boolean =
        contains(
            left = metrics.gutter + column * (metrics.cellWidth + metrics.gutter),
            top = metrics.gutter + row * (metrics.cellHeight + metrics.gutter),
            width = metrics.cellWidth,
            height = metrics.cellHeight,
            x = x,
            y = y,
            inset = (1f - INNER_FRACTION) / 2f,
        )
}

/**
 * Stateful wrapper around [GridDragMath.dropTarget] for one drag, adding hysteresis so the target
 * can't oscillate while re-packs move tiles under a still (or barely moving) finger:
 *
 * 1. **Cell lock.** After a target change, no further change happens until the pointer leaves the
 *    cell territory (cell plus half gutters, see [GridDragMath.territoryColumn]) it was in when the
 *    change happened. Any oscillation needs repeated evaluations at the same spot, so this rules it out.
 * 2. **Anchor tile.** The tile the dragged one was just swapped with is ignored as a target while the
 *    pointer stays anywhere inside it. Without this, sliding a 1×1 across a wide tile would flip the
 *    order at every cell boundary, because the wide tile keeps re-packing under the finger.
 *
 * Contract: whenever [update] returns an index different from `draggedIndex`, the caller moves the
 * tile there and passes the re-packed placements (with the dragged tile at the returned index) on
 * the next call. If the order changed some other way, call [reset].
 */
class GridDragTracker {
    private var lockedColumn = -1
    private var lockedRow = -1
    private var anchorIndex = -1

    fun reset() {
        lockedColumn = -1
        lockedRow = -1
        anchorIndex = -1
    }

    /** See [GridDragMath.dropTarget]; [viewportY] + [scrollOffset] is the pointer's content y. */
    fun update(
        metrics: GridMetrics,
        placements: List<GridPlacement>,
        realCount: Int,
        draggedIndex: Int,
        x: Float,
        viewportY: Float,
        scrollOffset: Int,
    ): Int {
        val contentY = viewportY + scrollOffset
        val column = GridDragMath.territoryColumn(metrics, x)
        val row = GridDragMath.territoryRow(metrics, contentY)
        if (lockedColumn >= 0) {
            if (column == lockedColumn && row == lockedRow) return draggedIndex
            lockedColumn = -1
            lockedRow = -1
        }
        if (anchorIndex >= 0 && GridDragMath.tileAt(metrics, placements, realCount, x, contentY) != anchorIndex) {
            anchorIndex = -1
        }

        val target = GridDragMath.dropTarget(metrics, placements, realCount, draggedIndex, x, contentY, anchorIndex)
        if (target != draggedIndex) {
            val hit = GridDragMath.tileAt(metrics, placements, realCount, x, contentY)
            anchorIndex = if (hit >= 0 && hit != draggedIndex) GridDragMath.indexAfterMove(hit, draggedIndex, target) else -1
            lockedColumn = column
            lockedRow = row
        }
        return target
    }
}
