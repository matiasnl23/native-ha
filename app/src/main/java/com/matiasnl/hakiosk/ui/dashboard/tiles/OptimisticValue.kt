package com.matiasnl.hakiosk.ui.dashboard.tiles

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** How long a released control keeps showing its own value while waiting for the entity to confirm it. */
const val OPTIMISTIC_CONFIRM_TIMEOUT_MILLIS = 3_000L

/**
 * One control's local value, shown instead of the entity's while the user drags it and, after release,
 * until the entity reports a matching value ([confirm]) or [timeoutMillis] pass. Then [display] follows
 * the entity again.
 *
 * Built for sliders: [drag] only writes a primitive snapshot state (no allocation per move) and
 * sends nothing; the caller sends one command when [release] returns true.
 */
@Stable
class OptimisticValue(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = OPTIMISTIC_CONFIRM_TIMEOUT_MILLIS,
    private val absoluteTolerance: Float = 0f,
    private val relativeTolerance: Float = 0f,
) {
    private val local = mutableFloatStateOf(0f)
    private val held = mutableStateOf(false)
    private var dragging = false
    private var timeout: Job? = null

    /** True while the local value is shown instead of the entity's. */
    val isHeld: Boolean get() = held.value

    /** The local value (meaningful while [isHeld]). */
    val value: Float get() = local.floatValue

    /** The value to render: the local one while held, else [confirmed]. */
    fun display(confirmed: Float): Float = if (held.value) local.floatValue else confirmed

    fun drag(value: Float) {
        timeout?.cancel()
        timeout = null
        dragging = true
        local.floatValue = value
        if (!held.value) held.value = true
    }

    /** Ends a drag and starts the confirmation timeout. False when no drag was in progress: send nothing. */
    fun release(): Boolean {
        if (!dragging) return false
        dragging = false
        timeout = scope.launch {
            delay(timeoutMillis)
            held.value = false
        }
        return true
    }

    /** A one-shot change (e.g. a switch): same as a drag immediately released. */
    fun set(value: Float) {
        drag(value)
        release()
    }

    /** The entity reported [confirmed] (NaN = unknown). Stops holding once it matches the local value. */
    fun confirm(confirmed: Float) {
        if (!held.value || dragging || confirmed.isNaN()) return
        val tolerance = maxOf(absoluteTolerance, abs(local.floatValue) * relativeTolerance)
        if (abs(confirmed - local.floatValue) <= tolerance) reset()
    }

    /** The command failed: follow the entity again, unless the user is already dragging anew. */
    fun abandon() {
        if (!dragging) reset()
    }

    private fun reset() {
        timeout?.cancel()
        timeout = null
        held.value = false
    }
}
