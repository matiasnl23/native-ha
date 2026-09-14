package com.matiasnl.hakiosk.ui.dashboard.grid

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

object DashboardGridDefaults {
    /** Space between cells and around the grid's outer edge. */
    val Gutter: Dp = 12.dp

    /** Outline used for the lifted tile's shadow; matches Material 3's medium (Card) shape. */
    val DragShape: Shape = RoundedCornerShape(12.dp)

    /** Height of the top/bottom viewport bands that autoscroll while dragging. */
    val AutoscrollBand: Dp = 56.dp

    /** Autoscroll speed with the pointer at (or past) the viewport edge. */
    val AutoscrollMaxSpeedPerSecond: Dp = 900.dp
}

private const val DragScale = 1.05f
private val DragElevation = 8.dp

/**
 * Non-lazy dashboard grid with row and column spans.
 *
 * Cells are sized from the viewport so exactly [packing]`.columns` × [visibleRows] cells fit (see
 * [GridMetrics]); content with more rows scrolls vertically with [scrollState]. [items] and
 * [packing]`.placements` are index-aligned. Every item is composed under `key(itemKey(item))`, so an
 * item keeps its composition state when a re-pack moves it, and slides from its old to its new
 * position (see [GridMotion]) instead of jumping.
 *
 * [cellContent] gets `isVisible`: whether any part of the cell is inside the viewport at the current
 * scroll offset. It is computed from the placement and scroll value (no layout callbacks) and only
 * recomposes a cell when it flips, so offscreen cells can pause expensive work such as camera
 * polling. Content is measured with fixed constraints equal to the cell's size.
 *
 * **Reorder.** When [onMove] is non-null, a long press on one of the first [draggableCount] items
 * lifts it (haptic, slight scale and shadow), it follows the finger through a layer translation, and
 * [onMove]`(from, to)` is called each time the drop target changes (see [GridDragTracker] for the
 * rule). The caller must apply the move so a re-packed [packing] comes back; the other tiles then
 * slide into place. Dragging near the top/bottom edge autoscrolls. Items at or past [draggableCount]
 * (a trailing "add" placeholder) can't be lifted and are never targets. Releasing, a second finger or
 * a system cancel end the drag with the order at the last target; setting [onMove] to null mid-drag
 * ends it too.
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
    draggableCount: Int = 0,
    onMove: ((from: Int, to: Int) -> Unit)? = null,
    cellContent: @Composable (item: T, placement: GridPlacement, isVisible: Boolean) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val gutterPx = with(density) { gutter.toPx() }
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

        val motion = remember { GridMotion() }
        val reorder = remember { GridReorderState<T>() }
        val haptics = LocalHapticFeedback.current
        val bandPx = with(density) { DashboardGridDefaults.AutoscrollBand.toPx() }
        val maxSpeedPx = with(density) { DashboardGridDefaults.AutoscrollMaxSpeedPerSecond.toPx() }
        SideEffect {
            motion.onLayout(items, itemKey, packing, metrics)
            reorder.sync(items, itemKey, packing, metrics, draggableCount, onMove, scrollState, haptics, motion, bandPx)
        }
        GridMotionDriver(motion)
        GridAutoscrollDriver(reorder, scrollState, maxSpeedPx)

        val gestures = if (onMove != null) Modifier.pointerInput(reorder) { detectReorderGestures(reorder) } else Modifier

        Layout(
            content = {
                for (index in 0 until count) {
                    val item = items[index]
                    val itemKey = itemKey(item)
                    key(itemKey) {
                        GridCell(item, itemKey, packing.placements[index], metrics, scrollState, motion, reorder, cellContent)
                    }
                }
            },
            // Always scrollable: when the content fits, its height equals the viewport and the
            // scroll range is 0, which also clamps a stale offset after rows or tiles shrink.
            // Gestures sit outside the scroll so they see viewport coordinates and a scroll drag
            // cancels a pending long press.
            modifier = gestures.verticalScroll(scrollState),
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

/**
 * One cell: a restartable scope, so a visibility or drag flip recomposes only this cell. Emits exactly
 * one layout node. Its layer carries either the drag translation (dragged cell) or the re-pack slide.
 */
@Composable
private fun <T> GridCell(
    item: T,
    key: Any,
    placement: GridPlacement,
    metrics: GridMetrics,
    scrollState: ScrollState,
    motion: GridMotion,
    reorder: GridReorderState<T>,
    content: @Composable (item: T, placement: GridPlacement, isVisible: Boolean) -> Unit,
) {
    val isVisible by remember(placement, metrics, scrollState) {
        derivedStateOf { metrics.isVisible(placement, scrollState.value) }
    }
    val isDragged by remember(reorder, key) { derivedStateOf { reorder.draggedKey == key } }
    val isSettling by remember(motion, key) { derivedStateOf { motion.settlingKey == key } }
    Box(
        propagateMinConstraints = true,
        modifier = Modifier
            .zIndex(if (isDragged || isSettling) 1f else 0f)
            .graphicsLayer {
                if (isDragged) {
                    translationX = reorder.pointerX.floatValue - reorder.grabX - metrics.left(placement)
                    translationY = reorder.pointerY.floatValue + scrollState.value - reorder.grabY - metrics.top(placement)
                    scaleX = DragScale
                    scaleY = DragScale
                    shadowElevation = DragElevation.toPx()
                    shape = DashboardGridDefaults.DragShape
                } else {
                    motion.applyTranslation(this, key)
                }
            },
    ) {
        content(item, placement, isVisible)
    }
}
