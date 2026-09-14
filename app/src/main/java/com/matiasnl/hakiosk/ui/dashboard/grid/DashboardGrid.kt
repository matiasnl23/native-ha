package com.matiasnl.hakiosk.ui.dashboard.grid

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object DashboardGridDefaults {
    /** Space between cells and around the grid's outer edge. */
    val Gutter: Dp = 12.dp
}

/**
 * Non-lazy dashboard grid with row and column spans.
 *
 * Cells are sized from the viewport so exactly [packing]`.columns` × [visibleRows] cells fit (see
 * [GridMetrics]); content with more rows scrolls vertically with [scrollState]. [items] and
 * [packing]`.placements` are index-aligned. Every item is composed under `key(itemKey(item))`, so an
 * item keeps its composition state when a re-pack moves it.
 *
 * [cellContent] gets `isVisible`: whether any part of the cell is inside the viewport at the current
 * scroll offset. It is computed from the placement and scroll value (no layout callbacks) and only
 * recomposes a cell when it flips, so offscreen cells can pause expensive work such as camera
 * polling. Content is measured with fixed constraints equal to the cell's size.
 */
@Composable
fun <T> DashboardGrid(
    items: List<T>,
    itemKey: (T) -> Any,
    packing: GridPacking,
    visibleRows: Int,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    gutter: Dp = DashboardGridDefaults.Gutter,
    cellContent: @Composable (item: T, placement: GridPlacement, isVisible: Boolean) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val gutterPx = with(LocalDensity.current) { gutter.toPx() }
        val viewportWidth = constraints.maxWidth
        // An unbounded parent (shouldn't happen for the dashboard) falls back to square cells.
        val viewportHeight = if (constraints.hasBoundedHeight) {
            constraints.maxHeight
        } else {
            val columns = packing.columns.coerceAtLeast(1)
            ((viewportWidth - gutterPx) / columns * visibleRows.coerceAtLeast(1) + gutterPx).toInt()
        }
        val metrics = remember(packing.columns, visibleRows, viewportWidth, viewportHeight, gutterPx) {
            GridMetrics(packing.columns, visibleRows, viewportWidth, viewportHeight, gutterPx)
        }
        val count = minOf(items.size, packing.placements.size)
        val contentHeight = metrics.contentHeight(packing.totalRows)

        Layout(
            content = {
                for (index in 0 until count) {
                    val item = items[index]
                    key(itemKey(item)) {
                        GridCell(item, packing.placements[index], metrics, scrollState, cellContent)
                    }
                }
            },
            // Always scrollable: when the content fits, its height equals the viewport and the
            // scroll range is 0, which also clamps a stale offset after rows or tiles shrink.
            modifier = Modifier.verticalScroll(scrollState),
        ) { measurables, _ ->
            val placeables = arrayOfNulls<Placeable>(measurables.size)
            for (index in measurables.indices) {
                val placement = packing.placements[index]
                placeables[index] = measurables[index].measure(
                    Constraints.fixed(metrics.width(placement), metrics.height(placement)),
                )
            }
            layout(viewportWidth, contentHeight) {
                for (index in placeables.indices) {
                    val placement = packing.placements[index]
                    placeables[index]!!.place(metrics.left(placement), metrics.top(placement))
                }
            }
        }
    }
}

/** One cell: a restartable scope, so a visibility flip recomposes only this cell. Emits exactly one layout node. */
@Composable
private fun <T> GridCell(
    item: T,
    placement: GridPlacement,
    metrics: GridMetrics,
    scrollState: ScrollState,
    content: @Composable (item: T, placement: GridPlacement, isVisible: Boolean) -> Unit,
) {
    val isVisible by remember(placement, metrics, scrollState) {
        derivedStateOf { metrics.isVisible(placement, scrollState.value) }
    }
    Box(propagateMinConstraints = true) {
        content(item, placement, isVisible)
    }
}
