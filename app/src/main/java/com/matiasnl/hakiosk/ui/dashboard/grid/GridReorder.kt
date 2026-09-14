package com.matiasnl.hakiosk.ui.dashboard.grid

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach

/** Duration of the slide of tiles to their new placement after a re-pack, and of the drop settle. */
private val MotionSpec = tween<Float>(durationMillis = 220, easing = FastOutSlowInEasing)

/**
 * Slides cells from their previous placement to the new one whenever the packing changes (same
 * metrics only: a grid-settings or viewport change snaps). Keyed by item key, so it follows a tile
 * across re-orders.
 *
 * Cost model: one shared eased [progress] (a float state written once per animation frame) for the
 * whole grid; each moving cell stores its start offset as plain floats and its graphics layer reads
 * `from × (1 - progress)`. Frames only update layer translations: no recomposition, no re-layout, no
 * per-frame allocation. Re-packs (not frames) rebase every cell's offset to where it currently is on
 * screen, so interrupting an animation never jumps.
 */
internal class GridMotion {
    private class Cell(var left: Int, var top: Int) {
        var fromX = 0f
        var fromY = 0f
        var generation = 0
    }

    private val cells = HashMap<Any, Cell>()
    private var lastPacking: GridPacking? = null
    private var lastMetrics: GridMetrics? = null
    private var generation = 0

    /** Eased progress 0→1 of the current slide; 1 when idle. */
    private val progress = mutableFloatStateOf(1f)

    /** Bumped on every rebase: restarts the animation and re-runs every cell's layer block. */
    var token by mutableIntStateOf(0)
        private set

    /** Key of a just-dropped tile that should keep drawing above the others while it settles. */
    var settlingKey: Any? by mutableStateOf(null)
        private set

    /** Call after every composition of the grid; a no-op unless [packing] or [metrics] changed. */
    fun <T> onLayout(items: List<T>, itemKey: (T) -> Any, packing: GridPacking, metrics: GridMetrics) {
        if (packing === lastPacking && metrics == lastMetrics) return
        val animate = lastPacking != null && metrics == lastMetrics
        lastPacking = packing
        lastMetrics = metrics
        val remaining = 1f - progress.floatValue
        generation++
        var moving = false
        val count = minOf(items.size, packing.placements.size)
        for (index in 0 until count) {
            val key = itemKey(items[index])
            val placement = packing.placements[index]
            val left = metrics.left(placement)
            val top = metrics.top(placement)
            val cell = cells[key]
            if (cell == null) {
                cells[key] = Cell(left, top).also { it.generation = generation }
                continue
            }
            if (animate) {
                cell.fromX = cell.fromX * remaining + (cell.left - left)
                cell.fromY = cell.fromY * remaining + (cell.top - top)
            } else {
                cell.fromX = 0f
                cell.fromY = 0f
            }
            cell.left = left
            cell.top = top
            cell.generation = generation
            if (cell.fromX != 0f || cell.fromY != 0f) moving = true
        }
        if (cells.size > count) cells.values.removeAll { it.generation != generation }
        restart(moving)
    }

    /** Starts the dropped tile's settle from where it was drawn ([visualLeft], [visualTop], content px). */
    fun settle(key: Any, visualLeft: Float, visualTop: Float) {
        val remaining = 1f - progress.floatValue
        cells.values.forEach {
            it.fromX *= remaining
            it.fromY *= remaining
        }
        val cell = cells[key] ?: return restart(moving = true)
        cell.fromX = visualLeft - cell.left
        cell.fromY = visualTop - cell.top
        settlingKey = key
        restart(moving = true)
    }

    /** Sets the layer translation of the cell with [key]; call from its `graphicsLayer` block. */
    fun applyTranslation(scope: GraphicsLayerScope, key: Any) {
        if (token < 0) return // Reading token subscribes the layer to rebases.
        val cell = cells[key] ?: return
        if (cell.fromX == 0f && cell.fromY == 0f) return
        val remaining = 1f - progress.floatValue
        scope.translationX = cell.fromX * remaining
        scope.translationY = cell.fromY * remaining
    }

    private fun restart(moving: Boolean) {
        progress.floatValue = if (moving) 0f else 1f
        token++
    }

    /** Runs the slide for the current [token]. */
    suspend fun animate() {
        if (progress.floatValue < 1f) {
            animate(progress.floatValue, 1f, animationSpec = MotionSpec) { value, _ -> progress.floatValue = value }
        }
        settlingKey = null
    }
}

/** Runs [GridMotion.animate] once per rebase. Its own scope, so a rebase never recomposes the grid. */
@Composable
internal fun GridMotionDriver(motion: GridMotion) {
    val token = motion.token
    LaunchedEffect(motion, token) { motion.animate() }
}

/**
 * State of one grid's drag & drop reorder. Owned by the grid composable, fed the latest inputs after
 * every composition through [sync], and driven by [detectReorderGestures] and [GridAutoscrollDriver].
 * All access happens on the main thread.
 *
 * Only [draggedKey] (flips twice per drag), [inAutoscrollBand] (flips when entering/leaving an edge
 * band) and the pointer position (read only inside the dragged cell's graphics layer) are snapshot
 * state, so a pointer move redraws one layer and recomposes nothing.
 *
 * A move reported through `onMove` reaches the grid asynchronously (ViewModel → StateFlow →
 * recomposition). Until the re-packed layout arrives in [sync], evaluations are skipped, so the drop
 * target is never computed against stale placements.
 */
internal class GridReorderState<T> {
    private val tracker = GridDragTracker()

    private var items: List<T> = emptyList()
    private var itemKey: (T) -> Any = { it as Any }
    private var packing: GridPacking = GridPacking.Empty
    private var metrics: GridMetrics? = null
    private var draggableCount = 0
    private var onMove: ((from: Int, to: Int) -> Unit)? = null
    private var scrollState: ScrollState? = null
    private var haptics: HapticFeedback? = null
    private var motion: GridMotion? = null
    private var bandPx = 0f

    /** Key of the tile being dragged, or null. */
    var draggedKey: Any? by mutableStateOf(null)
        private set

    /** Pointer position in viewport pixels. */
    val pointerX = mutableFloatStateOf(0f)
    val pointerY = mutableFloatStateOf(0f)

    /** Pointer offset from the dragged tile's top-left at pickup, so the tile doesn't jump under the finger. */
    var grabX = 0f
        private set
    var grabY = 0f
        private set

    /** True while dragging with the pointer inside a top/bottom autoscroll band. */
    var inAutoscrollBand by mutableStateOf(false)
        private set

    private var pendingPacking: GridPacking? = null
    private var expectedIndex = -1

    val viewportHeight: Int get() = metrics?.viewportHeight ?: 0
    val autoscrollBand: Float get() = bandPx

    fun sync(
        items: List<T>,
        itemKey: (T) -> Any,
        packing: GridPacking,
        metrics: GridMetrics,
        draggableCount: Int,
        onMove: ((from: Int, to: Int) -> Unit)?,
        scrollState: ScrollState,
        haptics: HapticFeedback,
        motion: GridMotion,
        autoscrollBandPx: Float,
    ) {
        this.items = items
        this.itemKey = itemKey
        this.packing = packing
        this.metrics = metrics
        this.draggableCount = minOf(draggableCount, items.size, packing.placements.size)
        this.onMove = onMove
        this.scrollState = scrollState
        this.haptics = haptics
        this.motion = motion
        this.bandPx = autoscrollBandPx
        val key = draggedKey ?: return
        // Reorder turned off (edit mode ended) or the tile vanished: end the drag.
        if (onMove == null || indexOfKey(key) !in 0 until this.draggableCount) end() else evaluate()
    }

    /** Index of the draggable tile under a viewport point, or -1 (including when reorder is off). */
    fun draggableIndexAt(x: Float, viewportY: Float): Int {
        val m = metrics ?: return -1
        if (onMove == null) return -1
        return GridDragMath.tileAt(m, packing.placements, draggableCount, x, viewportY + scroll())
    }

    /** Lifts the tile at [index] with the pointer at ([x], [viewportY]). False if it can't be dragged any more. */
    fun start(index: Int, x: Float, viewportY: Float): Boolean {
        val m = metrics ?: return false
        if (onMove == null || index !in 0 until draggableCount) return false
        val placement = packing.placements[index]
        grabX = x - m.left(placement)
        grabY = viewportY + scroll() - m.top(placement)
        pointerX.floatValue = x
        pointerY.floatValue = viewportY
        tracker.reset()
        pendingPacking = null
        expectedIndex = index
        draggedKey = itemKey(items[index])
        updateBand()
        haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
        return true
    }

    fun moveTo(x: Float, viewportY: Float) {
        if (draggedKey == null) return
        pointerX.floatValue = x
        pointerY.floatValue = viewportY
        updateBand()
        evaluate()
    }

    /** Re-evaluates the drop target at the current pointer and scroll; moves the tile if it changed. */
    fun evaluate() {
        val key = draggedKey ?: return
        val m = metrics ?: return
        val move = onMove ?: return
        val index = indexOfKey(key)
        if (index !in 0 until draggableCount) return
        val pending = pendingPacking
        if (pending != null) {
            if (packing === pending) return // The last move hasn't been re-packed yet.
            pendingPacking = null
            if (index != expectedIndex) tracker.reset() // The order changed some other way.
        }
        val target = tracker.update(m, packing.placements, draggableCount, index, pointerX.floatValue, pointerY.floatValue, scroll())
        if (target == index) return
        pendingPacking = packing
        expectedIndex = target
        haptics?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        move(index, target)
    }

    /**
     * Ends the drag, if any. The order stays wherever the last target put it (a system cancel behaves
     * like a drop); the tile settles from where it was drawn into its placement.
     */
    fun end() {
        val key = draggedKey ?: return
        val left = pointerX.floatValue - grabX
        val top = pointerY.floatValue + scroll() - grabY
        draggedKey = null
        inAutoscrollBand = false
        pendingPacking = null
        tracker.reset()
        motion?.settle(key, left, top)
    }

    private fun updateBand() {
        inAutoscrollBand = GridDragMath.autoscrollVelocity(pointerY.floatValue, viewportHeight, bandPx, 1f) != 0f
    }

    private fun scroll(): Int = scrollState?.value ?: 0

    private fun indexOfKey(key: Any): Int {
        for (index in items.indices) if (itemKey(items[index]) == key) return index
        return -1
    }
}

/**
 * Long-press-then-drag reorder on the grid viewport (install it outside `verticalScroll`, so
 * positions are viewport pixels and a scroll gesture before the long-press timeout cancels it).
 *
 * Taps keep working: until the long press fires nothing is consumed, so cells' `clickable` and the
 * scroll behave as usual. Once lifted, every change is consumed in the Initial pass, so cells never
 * see an unconsumed up (no click after a drop) and the scroll never moves. A second finger, a system
 * cancel (which arrives as all pointers up) or this coroutine being cancelled (reorder turned off)
 * all end the drag through [GridReorderState.end].
 */
internal suspend fun <T> PointerInputScope.detectReorderGestures(state: GridReorderState<T>) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val index = state.draggableIndexAt(down.position.x, down.position.y)
        if (index < 0) return@awaitEachGesture
        val longPress = awaitLongPressInInitialPass(down) ?: return@awaitEachGesture
        if (!state.start(index, longPress.position.x, longPress.position.y)) return@awaitEachGesture
        val pointerId = longPress.id
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                var tracked: PointerInputChange? = null
                var otherPressed = false
                event.changes.fastForEach { change ->
                    if (change.id == pointerId) tracked = change else if (change.pressed) otherPressed = true
                    change.consume()
                }
                val change = tracked
                if (change == null || !change.pressed || otherPressed) break
                // Every change was consumed above and positionChanged() is false for consumed changes,
                // so compare positions directly.
                if (change.position != change.previousPosition) state.moveTo(change.position.x, change.position.y)
            }
        } finally {
            state.end()
        }
    }
}

/**
 * Waits for a long press on [down] in the Initial pass, before the cells see the events. Edit-mode
 * cells are `clickable`, which consumes the pointer in the Main pass; `awaitLongPressOrCancellation`
 * gives up as soon as anything is consumed, so with it a drag could never start.
 *
 * Returns the latest change once the long-press timeout passes with the pointer still down and within
 * touch slop, or null if it lifts, moves past slop (a scroll) or a second finger lands first. Consumes
 * nothing.
 */
private suspend fun AwaitPointerEventScope.awaitLongPressInInitialPass(down: PointerInputChange): PointerInputChange? {
    var latest = down
    try {
        withTimeout(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.fastFirstOrNull { it.id == down.id }
                if (change == null || !change.pressed) return@withTimeout
                if (event.changes.fastAny { it.id != down.id && it.pressed }) return@withTimeout
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeout
                latest = change
            }
        }
        return null
    } catch (_: PointerEventTimeoutCancellationException) {
        return latest
    }
}

/**
 * While a drag sits in an edge band, scrolls every frame at [GridDragMath.autoscrollVelocity] and
 * re-evaluates the drop target, so it keeps updating with a still finger. Idle (no frame callbacks
 * requested) otherwise.
 */
@Composable
internal fun <T> GridAutoscrollDriver(state: GridReorderState<T>, scrollState: ScrollState, maxSpeedPx: Float) {
    val active = state.draggedKey != null && state.inAutoscrollBand
    LaunchedEffect(active, state, scrollState) {
        if (!active) return@LaunchedEffect
        var lastFrame = -1L
        val onFrame: (Long) -> Unit = { now ->
            if (lastFrame >= 0) {
                val seconds = (now - lastFrame) / 1_000_000_000f
                val velocity = GridDragMath.autoscrollVelocity(
                    state.pointerY.floatValue,
                    state.viewportHeight,
                    state.autoscrollBand,
                    maxSpeedPx,
                )
                if (velocity != 0f && scrollState.dispatchRawDelta(velocity * seconds) != 0f) state.evaluate()
            }
            lastFrame = now
        }
        while (true) withFrameNanos(onFrame)
    }
}
