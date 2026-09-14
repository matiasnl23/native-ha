package com.matiasnl.hakiosk.ui.dashboard.tiles.light

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.domain.LightCapabilities
import com.matiasnl.hakiosk.data.ha.domain.LightCommands
import com.matiasnl.hakiosk.data.ha.domain.ServiceCall
import com.matiasnl.hakiosk.ui.dashboard.tiles.EntityControlSource
import com.matiasnl.hakiosk.ui.dashboard.tiles.OPTIMISTIC_CONFIRM_TIMEOUT_MILLIS
import com.matiasnl.hakiosk.ui.dashboard.tiles.OptimisticValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** HA's fallback color temperature range, for a light that reports none. */
private const val FALLBACK_MIN_KELVIN = 2000
private const val FALLBACK_MAX_KELVIN = 6535

/**
 * State holder of the light details panel. Collects [entityId] from [source] for as long as [scope]
 * lives (the panel's composition), and exposes only snapshot state so a slider move recomposes just
 * that slider.
 *
 * Which controls exist comes from the light's `supported_color_modes` ([showBrightness],
 * [showColorTemp], [showColor]). Sliders follow the [OptimisticValue] rule: `on…Change` only moves the
 * local value, `on…ChangeFinished` sends exactly one service call. Changing temperature or color on an
 * "off" light turns it on (HA's `turn_on` does that); the switch shows it on right away.
 */
@Stable
class LightPanelController(
    private val entityId: String,
    private val source: EntityControlSource,
    private val scope: CoroutineScope,
    initialEntity: HaEntity? = null,
    confirmTimeoutMillis: Long = OPTIMISTIC_CONFIRM_TIMEOUT_MILLIS,
) {
    /** The light's latest state, or null while unknown (not synced, or removed). */
    var entity: HaEntity? by mutableStateOf(null)
        private set

    var capabilities: LightCapabilities? by mutableStateOf(null)
        private set

    /** HA's message for the last failed call; cleared when the next command is sent. */
    var error: String? by mutableStateOf(null)
        private set

    private val power = OptimisticValue(scope, confirmTimeoutMillis, absoluteTolerance = 0.5f)
    private val brightness = OptimisticValue(scope, confirmTimeoutMillis, absoluteTolerance = 1f)

    // Integrations often store mireds, so the Kelvin that comes back can be a few degrees off.
    private val colorTemp = OptimisticValue(scope, confirmTimeoutMillis, absoluteTolerance = 30f, relativeTolerance = 0.01f)

    // Lights that store xy report a slightly different hs back.
    private val hue = OptimisticValue(scope, confirmTimeoutMillis, absoluteTolerance = 3f)
    private val saturation = OptimisticValue(scope, confirmTimeoutMillis, absoluteTolerance = 3f)

    init {
        if (initialEntity != null) onEntity(initialEntity)
        scope.launch { source.entity(entityId).collect { onEntity(it) } }
    }

    val isMissing: Boolean get() = entity == null

    val isAvailable: Boolean get() = entity?.isUnavailable == false

    val isOn: Boolean get() = power.display(if (capabilities?.isOn == true) 1f else 0f) >= 0.5f

    val showBrightness: Boolean get() = isAvailable && capabilities?.supportsBrightness == true

    val showColorTemp: Boolean get() = isAvailable && capabilities?.supportsColorTemp == true

    val showColor: Boolean get() = isAvailable && capabilities?.supportsColor == true

    /** 0..100; 0 while the light is off. */
    val brightnessPercent: Float
        get() = brightness.display(if (capabilities?.isOn == true) capabilities?.brightnessPercent?.toFloat() ?: 0f else 0f)

    val minColorTempKelvin: Int get() = minOf(rawMinKelvin, rawMaxKelvin)

    val maxColorTempKelvin: Int get() = maxOf(rawMinKelvin, rawMaxKelvin)

    /** Within [minColorTempKelvin]..[maxColorTempKelvin]; the range's middle when the light reports none. */
    val colorTempKelvin: Float
        get() {
            val min = minColorTempKelvin.toFloat()
            val max = maxColorTempKelvin.toFloat()
            val confirmed = capabilities?.colorTempKelvin?.toFloat() ?: ((min + max) / 2f)
            return colorTemp.display(confirmed).coerceIn(min, max)
        }

    /** 0..360. */
    val hueDegrees: Float get() = hue.display(capabilities?.hue?.toFloat() ?: 0f)

    /** 0..100; full saturation when the light reports no color. */
    val saturationPercent: Float get() = saturation.display(capabilities?.saturation?.toFloat() ?: 100f)

    private val rawMinKelvin: Int get() = capabilities?.minColorTempKelvin ?: FALLBACK_MIN_KELVIN

    private val rawMaxKelvin: Int get() = capabilities?.maxColorTempKelvin ?: FALLBACK_MAX_KELVIN

    fun setOn(on: Boolean) {
        power.set(if (on) 1f else 0f)
        send(if (on) LightCommands.turnOn() else LightCommands.turnOff(), power)
    }

    fun onBrightnessChange(percent: Float) = brightness.drag(percent.coerceIn(0f, 100f))

    fun onBrightnessChangeFinished() {
        if (!brightness.release()) return
        val percent = brightness.value.roundToInt()
        power.set(if (percent > 0) 1f else 0f)
        send(LightCommands.setBrightnessPercent(percent), brightness, power)
    }

    fun onColorTempChange(kelvin: Float) = colorTemp.drag(kelvin)

    fun onColorTempChangeFinished() {
        if (!colorTemp.release()) return
        val capabilities = capabilities ?: return colorTemp.abandon()
        power.set(1f)
        send(LightCommands.setColorTempKelvin(colorTemp.value.roundToInt(), capabilities), colorTemp, power)
    }

    fun onHueChange(degrees: Float) = hue.drag(degrees.coerceIn(0f, 360f))

    fun onHueChangeFinished() {
        if (!hue.release()) return
        power.set(1f)
        send(LightCommands.setHsColor(hue.value.roundToInt().toDouble(), saturationPercent.roundToInt().toDouble()), hue, power)
    }

    fun onSaturationChange(percent: Float) = saturation.drag(percent.coerceIn(0f, 100f))

    fun onSaturationChangeFinished() {
        if (!saturation.release()) return
        power.set(1f)
        send(LightCommands.setHsColor(hueDegrees.roundToInt().toDouble(), saturation.value.roundToInt().toDouble()), saturation, power)
    }

    private fun send(call: ServiceCall, vararg held: OptimisticValue) {
        error = null
        scope.launch {
            val failure = source.call(entityId, call).exceptionOrNull() ?: return@launch
            held.forEach { it.abandon() }
            error = failure.message ?: "${call.domain}.${call.service}"
        }
    }

    private fun onEntity(new: HaEntity?) {
        if (new != null && new === entity) return
        entity = new
        val capabilities = new?.let(LightCapabilities::from)
        this.capabilities = capabilities
        if (capabilities == null) return
        power.confirm(if (capabilities.isOn) 1f else 0f)
        brightness.confirm(if (capabilities.isOn) capabilities.brightnessPercent?.toFloat() ?: Float.NaN else 0f)
        colorTemp.confirm(capabilities.colorTempKelvin?.toFloat() ?: Float.NaN)
        hue.confirm(capabilities.hue?.toFloat() ?: Float.NaN)
        saturation.confirm(capabilities.saturation?.toFloat() ?: Float.NaN)
    }
}
