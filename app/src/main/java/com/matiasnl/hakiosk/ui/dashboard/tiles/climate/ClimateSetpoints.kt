package com.matiasnl.hakiosk.ui.dashboard.tiles.climate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.matiasnl.hakiosk.data.ha.domain.ClimateCapabilities
import com.matiasnl.hakiosk.ui.dashboard.tiles.OPTIMISTIC_CONFIRM_TIMEOUT_MILLIS
import com.matiasnl.hakiosk.ui.dashboard.tiles.OptimisticValue
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** Setpoints never need more than hundredths; rounds away float noise before showing or sending one. */
internal fun roundTemperature(value: Double): Double = (value * 100).roundToLong() / 100.0

/** [value] snapped to the [step] grid, moved [steps] steps and kept within [min]..[max]. */
internal fun steppedTemperature(value: Double, steps: Int, step: Double, min: Double, max: Double): Double {
    val safeStep = step.takeIf { it > 0.0 } ?: ClimateCapabilities.DEFAULT_TEMPERATURE_STEP
    val next = ((value / safeStep).roundToLong() + steps) * safeStep
    return roundTemperature(next.coerceIn(min, max))
}

/** The setpoint a climate tile's buttons settled on. */
sealed interface ClimateSetpoint {
    data class Single(val temperature: Double) : ClimateSetpoint

    data class Range(val low: Double, val high: Double) : ClimateSetpoint
}

/** Which setpoint a tile button moves. */
enum class SetpointEnd { TARGET, LOW, HIGH }

/**
 * The quick-adjust tile's setpoint: a − or + tap shows the new value at once and [onCommit] receives one
 * setpoint [commitDelayMillis] after the last tap (or on [flush]). Moves start from what HA reported in
 * the tile's summary, stay within its limits, and a range's low end never passes its high end.
 */
@Stable
class ClimateSetpointState internal constructor(
    private val scope: CoroutineScope,
    private val summary: () -> TileSummary.Climate,
    private val onCommit: (ClimateSetpoint) -> Unit,
    confirmTimeoutMillis: Long = OPTIMISTIC_CONFIRM_TIMEOUT_MILLIS,
    private val commitDelayMillis: Long = SETPOINT_COMMIT_DELAY_MILLIS,
) {
    private val target = OptimisticValue(scope, confirmTimeoutMillis, absoluteTolerance = SETPOINT_TOLERANCE)
    private val low = OptimisticValue(scope, confirmTimeoutMillis, absoluteTolerance = SETPOINT_TOLERANCE)
    private val high = OptimisticValue(scope, confirmTimeoutMillis, absoluteTolerance = SETPOINT_TOLERANCE)
    private var pending: Job? = null
    private var dirty = false

    val targetTemperature: Double? get() = shown(target, summary().targetTemperature)

    val targetLow: Double? get() = shown(low, summary().targetTemperatureLow)

    val targetHigh: Double? get() = shown(high, summary().targetTemperatureHigh)

    fun canStep(end: SetpointEnd, steps: Int): Boolean = next(end, steps) != null

    fun step(end: SetpointEnd, steps: Int) {
        val next = next(end, steps) ?: return
        valueOf(end).drag(next.toFloat())
        dirty = true
        pending?.cancel()
        pending = scope.launch {
            delay(commitDelayMillis)
            pending = null
            commit()
        }
    }

    /** Commits a setpoint still waiting for its delay, right now. */
    fun flush() {
        pending?.cancel()
        pending = null
        commit()
    }

    internal fun confirm(summary: TileSummary.Climate) {
        target.confirm(summary.targetTemperature?.toFloat() ?: Float.NaN)
        low.confirm(summary.targetTemperatureLow?.toFloat() ?: Float.NaN)
        high.confirm(summary.targetTemperatureHigh?.toFloat() ?: Float.NaN)
    }

    private fun commit() {
        if (!dirty) return
        dirty = false
        if (summary().targetTemperatureLow != null) {
            low.release()
            high.release()
            val low = targetLow ?: return
            val high = targetHigh ?: return
            onCommit(ClimateSetpoint.Range(low, high))
        } else {
            if (!target.release()) return
            onCommit(ClimateSetpoint.Single(targetTemperature ?: return))
        }
    }

    private fun next(end: SetpointEnd, steps: Int): Double? {
        val summary = summary()
        fun stepped(value: Double) =
            steppedTemperature(value, steps, summary.temperatureStep, summary.minTemperature, summary.maxTemperature)
        val (current, next) = when (end) {
            SetpointEnd.TARGET -> {
                val value = targetTemperature ?: return null
                value to stepped(value)
            }
            SetpointEnd.LOW -> {
                val value = targetLow ?: return null
                value to stepped(value).coerceAtMost(targetHigh ?: return null)
            }
            SetpointEnd.HIGH -> {
                val value = targetHigh ?: return null
                value to stepped(value).coerceAtLeast(targetLow ?: return null)
            }
        }
        return next.takeUnless { sameTemperature(it, current) }
    }

    private fun valueOf(end: SetpointEnd): OptimisticValue = when (end) {
        SetpointEnd.TARGET -> target
        SetpointEnd.LOW -> low
        SetpointEnd.HIGH -> high
    }

    private fun shown(value: OptimisticValue, confirmed: Double?): Double? =
        confirmed?.let { roundTemperature(value.display(it.toFloat()).toDouble()) }

    private companion object {
        const val SETPOINT_TOLERANCE = 0.05f
    }
}

/**
 * A [ClimateSetpointState] for one tile. Leaving composition (a page swipe, edit mode) flushes a tap
 * still waiting for its delay: [onCommit] hands it to the ViewModel, whose scope outlives the tile.
 */
@Composable
fun rememberClimateSetpointState(summary: TileSummary.Climate, onCommit: (ClimateSetpoint) -> Unit): ClimateSetpointState {
    val scope = rememberCoroutineScope()
    val latestSummary = rememberUpdatedState(summary)
    val latestOnCommit = rememberUpdatedState(onCommit)
    val state = remember(scope) {
        ClimateSetpointState(scope, summary = { latestSummary.value }, onCommit = { latestOnCommit.value(it) })
    }
    LaunchedEffect(state, summary) { state.confirm(summary) }
    DisposableEffect(state) {
        onDispose { state.flush() }
    }
    return state
}
