package com.matiasnl.hakiosk.ui.dashboard.tiles.light

import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.matiasnl.hakiosk.ui.dashboard.tiles.OPTIMISTIC_CONFIRM_TIMEOUT_MILLIS
import com.matiasnl.hakiosk.ui.dashboard.tiles.OptimisticValue
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

/**
 * Brightness driven by a horizontal swipe across a light tile: a full tile width is 0..100%, relative
 * to where the light was. Follows the [OptimisticValue] rule: moves only change the shown value and
 * [onDragStopped] commits exactly one value. A swipe that starts after a long press has fired (the
 * details panel is opening) is ignored until the next press.
 */
@Stable
class BrightnessSwipeState internal constructor(
    scope: CoroutineScope,
    private val confirmedPercent: () -> Float,
    private val onCommit: (percent: Int) -> Unit,
    confirmTimeoutMillis: Long = OPTIMISTIC_CONFIRM_TIMEOUT_MILLIS,
) {
    private val level = OptimisticValue(scope, confirmTimeoutMillis, absoluteTolerance = 1f)
    private var dragging = false
    private var suppressed = false

    /** The tile's width in pixels, which maps to the whole 0..100 range. */
    var widthPx: Float = 0f

    val draggableState: DraggableState = DraggableState { delta -> onDrag(delta) }

    /** The swiped brightness (0..100) while it's shown instead of the entity's, else null. */
    val heldPercent: Int? by derivedStateOf { if (level.isHeld) level.value.roundToInt() else null }

    /** A new press started: a swipe in it is allowed again. */
    fun onPress() {
        suppressed = false
    }

    /** The press became a long press: ignore any swipe until the finger lifts. */
    fun onLongPress() {
        suppressed = true
    }

    fun onDragStarted() {
        if (suppressed || widthPx <= 0f) return
        dragging = true
        level.drag(level.display(confirmedPercent()))
    }

    fun onDragStopped() {
        if (!dragging) return
        dragging = false
        if (!level.release()) return
        val percent = level.value.roundToInt()
        if (percent != confirmedPercent().roundToInt()) onCommit(percent)
    }

    internal fun confirm(percent: Float) = level.confirm(percent)

    private fun onDrag(deltaPx: Float) {
        if (!dragging) return
        level.drag((level.value + deltaPx / widthPx * 100f).coerceIn(0f, 100f))
    }
}

/**
 * [confirmedPercent] is the entity's brightness (0 while off); [onCommit] receives the swiped value
 * (0 means turn off).
 */
@Composable
fun rememberBrightnessSwipeState(confirmedPercent: Float, onCommit: (percent: Int) -> Unit): BrightnessSwipeState {
    val scope = rememberCoroutineScope()
    val latestConfirmed: State<Float> = rememberUpdatedState(confirmedPercent)
    val latestOnCommit = rememberUpdatedState(onCommit)
    val state = remember(scope) {
        BrightnessSwipeState(scope, confirmedPercent = { latestConfirmed.value }, onCommit = { latestOnCommit.value(it) })
    }
    LaunchedEffect(state, confirmedPercent) { state.confirm(confirmedPercent) }
    return state
}
