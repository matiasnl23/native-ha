package com.matiasnl.hakiosk.ui.dashboard.tiles.climate

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.domain.ClimateCapabilities
import com.matiasnl.hakiosk.data.ha.domain.ClimateCommands
import com.matiasnl.hakiosk.data.ha.domain.HvacAction
import com.matiasnl.hakiosk.data.ha.domain.HvacMode
import com.matiasnl.hakiosk.data.ha.domain.ServiceCall
import com.matiasnl.hakiosk.ui.dashboard.tiles.EntityControlSource
import com.matiasnl.hakiosk.ui.dashboard.tiles.OPTIMISTIC_CONFIRM_TIMEOUT_MILLIS
import com.matiasnl.hakiosk.ui.dashboard.tiles.OptimisticChoice
import com.matiasnl.hakiosk.ui.dashboard.tiles.OptimisticValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** How long the setpoint waits after the last +/− tap before it's sent, so repeated taps send one call. */
const val SETPOINT_COMMIT_DELAY_MILLIS = 1_000L

private enum class PendingSetpoint { SINGLE, RANGE }

/**
 * State holder of the climate panel. Collects [entityId] from [source] while [scope] lives (the panel's
 * composition) and exposes only snapshot state.
 *
 * - Setpoints move in the entity's step with +/−; the value shows at once and one `set_temperature` goes
 *   out [commitDelayMillis] after the last tap. [close] sends a still-pending one right away, so closing
 *   the panel right after a tap doesn't lose it.
 * - Modes (HVAC, preset, fan, swing) send on tap and show the choice until the entity confirms it.
 * - Target humidity is a slider: one call on release.
 *
 * Nothing is sent before the entity is known: values only move from what HA reported.
 */
@Stable
class ClimatePanelController(
    private val entityId: String,
    private val source: EntityControlSource,
    private val scope: CoroutineScope,
    initialEntity: HaEntity? = null,
    confirmTimeoutMillis: Long = OPTIMISTIC_CONFIRM_TIMEOUT_MILLIS,
    private val commitDelayMillis: Long = SETPOINT_COMMIT_DELAY_MILLIS,
) {
    var entity: HaEntity? by mutableStateOf(null)
        private set

    var capabilities: ClimateCapabilities? by mutableStateOf(null)
        private set

    /** HA's message for the last failed call; cleared when the next command is sent. */
    var error: String? by mutableStateOf(null)
        private set

    private val hvacModeChoice = OptimisticChoice<HvacMode>(scope, confirmTimeoutMillis)
    private val presetChoice = OptimisticChoice<String>(scope, confirmTimeoutMillis)
    private val fanChoice = OptimisticChoice<String>(scope, confirmTimeoutMillis)
    private val swingChoice = OptimisticChoice<String>(scope, confirmTimeoutMillis)
    private val swingHorizontalChoice = OptimisticChoice<String>(scope, confirmTimeoutMillis)

    private val target = OptimisticValue(scope, confirmTimeoutMillis, absoluteTolerance = SETPOINT_TOLERANCE)
    private val targetLow = OptimisticValue(scope, confirmTimeoutMillis, absoluteTolerance = SETPOINT_TOLERANCE)
    private val targetHigh = OptimisticValue(scope, confirmTimeoutMillis, absoluteTolerance = SETPOINT_TOLERANCE)
    private val humidity = OptimisticValue(scope, confirmTimeoutMillis, absoluteTolerance = 1f)

    private var pendingSetpoint: PendingSetpoint? = null
    private var pendingSetpointJob: Job? = null

    init {
        if (initialEntity != null) onEntity(initialEntity)
        scope.launch { source.entity(entityId).collect { onEntity(it) } }
    }

    val isMissing: Boolean get() = entity == null

    val isAvailable: Boolean get() = entity?.isUnavailable == false

    val hvacMode: HvacMode? get() = hvacModeChoice.display(capabilities?.hvacMode)

    val hvacModes: List<HvacMode> get() = if (isAvailable) capabilities?.hvacModes.orEmpty() else emptyList()

    val hvacAction: HvacAction? get() = capabilities?.hvacAction

    val currentTemperature: Double? get() = capabilities?.currentTemperature

    val currentHumidity: Double? get() = capabilities?.currentHumidity

    val showTargetRange: Boolean get() = isAvailable && capabilities?.usesTargetRange == true

    val showTargetTemperature: Boolean
        get() = isAvailable && capabilities?.let { it.hasTargetTemperature && !it.usesTargetRange } == true

    val targetTemperature: Double? get() = shown(target, capabilities?.targetTemperature)

    val targetTemperatureLow: Double? get() = shown(targetLow, capabilities?.targetTemperatureLow)

    val targetTemperatureHigh: Double? get() = shown(targetHigh, capabilities?.targetTemperatureHigh)

    val showTargetHumidity: Boolean get() = isAvailable && capabilities?.hasTargetHumidity == true

    val humidityRange: ClosedFloatingPointRange<Float>
        get() = capabilities?.let { it.minHumidity.toFloat()..it.maxHumidity.toFloat() } ?: 0f..100f

    val targetHumidity: Float get() = humidity.display(capabilities?.targetHumidity?.toFloat() ?: 0f)

    val presetModes: List<String> get() = options { it.hasPresetModes to it.presetModes }

    val presetMode: String? get() = presetChoice.display(capabilities?.presetMode)

    val fanModes: List<String> get() = options { it.hasFanModes to it.fanModes }

    val fanMode: String? get() = fanChoice.display(capabilities?.fanMode)

    val swingModes: List<String> get() = options { it.hasSwingModes to it.swingModes }

    val swingMode: String? get() = swingChoice.display(capabilities?.swingMode)

    val swingHorizontalModes: List<String> get() = options { it.hasSwingHorizontalModes to it.swingHorizontalModes }

    val swingHorizontalMode: String? get() = swingHorizontalChoice.display(capabilities?.swingHorizontalMode)

    fun canStepTargetTemperature(steps: Int): Boolean {
        val value = targetTemperature ?: return false
        val capabilities = capabilities ?: return false
        return !sameTemperature(stepped(value, steps, capabilities), value)
    }

    fun canStepTargetLow(steps: Int): Boolean {
        val low = targetTemperatureLow ?: return false
        val high = targetTemperatureHigh ?: return false
        val capabilities = capabilities ?: return false
        return !sameTemperature(stepped(low, steps, capabilities).coerceAtMost(high), low)
    }

    fun canStepTargetHigh(steps: Int): Boolean {
        val low = targetTemperatureLow ?: return false
        val high = targetTemperatureHigh ?: return false
        val capabilities = capabilities ?: return false
        return !sameTemperature(stepped(high, steps, capabilities).coerceAtLeast(low), high)
    }

    /** Moves the single setpoint [steps] steps (negative lowers it). */
    fun stepTargetTemperature(steps: Int) {
        if (!showTargetTemperature) return
        val capabilities = capabilities ?: return
        val value = targetTemperature ?: return
        val next = stepped(value, steps, capabilities)
        if (sameTemperature(next, value)) return
        target.drag(next.toFloat())
        scheduleSetpoint(PendingSetpoint.SINGLE)
    }

    /** Moves the range's low end, never above the high end. */
    fun stepTargetLow(steps: Int) {
        if (!showTargetRange) return
        val capabilities = capabilities ?: return
        val low = targetTemperatureLow ?: return
        val high = targetTemperatureHigh ?: return
        val next = stepped(low, steps, capabilities).coerceAtMost(high)
        if (sameTemperature(next, low)) return
        targetLow.drag(next.toFloat())
        scheduleSetpoint(PendingSetpoint.RANGE)
    }

    /** Moves the range's high end, never below the low end. */
    fun stepTargetHigh(steps: Int) {
        if (!showTargetRange) return
        val capabilities = capabilities ?: return
        val low = targetTemperatureLow ?: return
        val high = targetTemperatureHigh ?: return
        val next = stepped(high, steps, capabilities).coerceAtLeast(low)
        if (sameTemperature(next, high)) return
        targetHigh.drag(next.toFloat())
        scheduleSetpoint(PendingSetpoint.RANGE)
    }

    fun setHvacMode(mode: HvacMode) {
        if (mode !in hvacModes || mode == hvacMode) return
        // A setpoint still waiting goes first, so it applies to the mode it was set in.
        flushSetpoint()
        hvacModeChoice.set(mode)
        send(ClimateCommands.setHvacMode(mode)) { hvacModeChoice.abandon() }
    }

    fun setPresetMode(mode: String) = choose(presetChoice, mode, presetMode, presetModes, ClimateCommands::setPresetMode)

    fun setFanMode(mode: String) = choose(fanChoice, mode, fanMode, fanModes, ClimateCommands::setFanMode)

    fun setSwingMode(mode: String) = choose(swingChoice, mode, swingMode, swingModes, ClimateCommands::setSwingMode)

    fun setSwingHorizontalMode(mode: String) =
        choose(swingHorizontalChoice, mode, swingHorizontalMode, swingHorizontalModes, ClimateCommands::setSwingHorizontalMode)

    fun onHumidityChange(percent: Float) {
        if (!showTargetHumidity) return
        val range = humidityRange
        humidity.drag(percent.coerceIn(range.start, range.endInclusive))
    }

    fun onHumidityChangeFinished() {
        if (!humidity.release()) return
        val capabilities = capabilities ?: return humidity.abandon()
        send(ClimateCommands.setHumidity(humidity.value.roundToInt(), capabilities)) { humidity.abandon() }
    }

    /** The panel is closing: a setpoint still waiting for its delay is sent now, and survives the panel. */
    fun close() = flushSetpoint(outlivesPanel = true)

    private fun choose(
        choice: OptimisticChoice<String>,
        value: String,
        current: String?,
        options: List<String>,
        build: (String) -> ServiceCall,
    ) {
        if (value !in options || value == current) return
        choice.set(value)
        send(build(value)) { choice.abandon() }
    }

    private fun options(select: (ClimateCapabilities) -> Pair<Boolean, List<String>>): List<String> {
        val capabilities = capabilities ?: return emptyList()
        if (!isAvailable) return emptyList()
        val (supported, values) = select(capabilities)
        return if (supported) values else emptyList()
    }

    private fun shown(value: OptimisticValue, confirmed: Double?): Double? =
        confirmed?.let { roundTemperature(value.display(it.toFloat()).toDouble()) }

    private fun scheduleSetpoint(kind: PendingSetpoint) {
        pendingSetpoint = kind
        pendingSetpointJob?.cancel()
        pendingSetpointJob = scope.launch {
            delay(commitDelayMillis)
            pendingSetpointJob = null
            commitSetpoint(outlivesPanel = false)
        }
    }

    private fun flushSetpoint(outlivesPanel: Boolean = false) {
        pendingSetpointJob?.cancel()
        pendingSetpointJob = null
        commitSetpoint(outlivesPanel)
    }

    private fun commitSetpoint(outlivesPanel: Boolean) {
        val kind = pendingSetpoint ?: return
        pendingSetpoint = null
        val capabilities = capabilities
        when (kind) {
            PendingSetpoint.SINGLE -> {
                if (!target.release()) return
                if (capabilities == null) return target.abandon()
                val call = ClimateCommands.setTemperature(roundTemperature(target.value.toDouble()), capabilities)
                send(call, outlivesPanel) { target.abandon() }
            }
            PendingSetpoint.RANGE -> {
                val lowMoved = targetLow.release()
                val highMoved = targetHigh.release()
                if (!lowMoved && !highMoved) return
                val low = targetTemperatureLow
                val high = targetTemperatureHigh
                if (capabilities == null || low == null || high == null) {
                    targetLow.abandon()
                    targetHigh.abandon()
                    return
                }
                send(ClimateCommands.setTemperatureRange(low, high, capabilities), outlivesPanel) {
                    targetLow.abandon()
                    targetHigh.abandon()
                }
            }
        }
    }

    /** [outlivesPanel]: the call must complete even though the panel's scope is about to be cancelled. */
    private fun send(call: ServiceCall, outlivesPanel: Boolean = false, onFailure: () -> Unit) {
        error = null
        scope.launch(if (outlivesPanel) NonCancellable else EmptyCoroutineContext) {
            val failure = source.call(entityId, call).exceptionOrNull() ?: return@launch
            onFailure()
            error = failure.message ?: "${call.domain}.${call.service}"
        }
    }

    private fun onEntity(new: HaEntity?) {
        if (new != null && new === entity) return
        entity = new
        val capabilities = new?.let(ClimateCapabilities::from)
        this.capabilities = capabilities
        if (capabilities == null) return
        hvacModeChoice.confirm(capabilities.hvacMode)
        presetChoice.confirm(capabilities.presetMode)
        fanChoice.confirm(capabilities.fanMode)
        swingChoice.confirm(capabilities.swingMode)
        swingHorizontalChoice.confirm(capabilities.swingHorizontalMode)
        target.confirm(capabilities.targetTemperature?.toFloat() ?: Float.NaN)
        targetLow.confirm(capabilities.targetTemperatureLow?.toFloat() ?: Float.NaN)
        targetHigh.confirm(capabilities.targetTemperatureHigh?.toFloat() ?: Float.NaN)
        humidity.confirm(capabilities.targetHumidity?.toFloat() ?: Float.NaN)
    }

    private companion object {
        /** Integrations round setpoints to their own precision; a tenth of the smallest usual step. */
        const val SETPOINT_TOLERANCE = 0.05f

        fun roundTemperature(value: Double): Double = (value * 100).roundToLong() / 100.0

        /** [value] snapped to the entity's step grid, moved [steps] steps and kept within its range. */
        fun stepped(value: Double, steps: Int, capabilities: ClimateCapabilities): Double {
            val step = capabilities.temperatureStep
            val next = ((value / step).roundToLong() + steps) * step
            return roundTemperature(next.coerceIn(capabilities.minTemperature, capabilities.maxTemperature))
        }
    }
}
