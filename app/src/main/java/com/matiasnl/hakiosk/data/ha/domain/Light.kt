package com.matiasnl.hakiosk.data.ha.domain

import com.matiasnl.hakiosk.data.ha.HaEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * `ColorMode` values a `light` entity can report, verified against HA core
 * (homeassistant/components/light/const.py, current `dev`/2026.9.x). `ColorMode.UNKNOWN` ("ambiguous
 * color mode") is deliberately not modeled: it's never meant to appear in `supported_color_modes` for
 * a well-behaved integration, and we ignore it defensively like any other unrecognized value.
 */
enum class LightColorMode {
    ONOFF,
    BRIGHTNESS,
    COLOR_TEMP,
    HS,
    XY,
    RGB,
    RGBW,
    RGBWW,
    WHITE,
    ;

    companion object {
        fun from(value: String): LightColorMode? = when (value) {
            "onoff" -> ONOFF
            "brightness" -> BRIGHTNESS
            "color_temp" -> COLOR_TEMP
            "hs" -> HS
            "xy" -> XY
            "rgb" -> RGB
            "rgbw" -> RGBW
            "rgbww" -> RGBWW
            "white" -> WHITE
            else -> null // covers "unknown" and any future/unrecognized mode
        }
    }
}

/**
 * Decoded capabilities and current color state of a `light` entity. Verified against HA core
 * (homeassistant/components/light/const.py and __init__.py, current `dev`/2026.9.x):
 * - Every color mode except [LightColorMode.ONOFF] implies brightness support (`COLOR_MODES_BRIGHTNESS`).
 * - hs/xy/rgb/rgbw/rgbww are the modes with a color picker (`COLOR_MODES_COLOR`); `color_temp` and
 *   `white` are not.
 * - Mireds (`color_temp`, `min_mireds`, `max_mireds`) were removed from HA core in **2026.3** (deprecated
 *   since 2022.11); `color_temp_kelvin`/`min_color_temp_kelvin`/`max_color_temp_kelvin` have existed
 *   since 2022.11, so reading only the Kelvin attributes degrades correctly on HA 2024.x too — nothing
 *   mireds-specific is needed.
 * - `supported_features` no longer carries any color/brightness bits (removed in 2026.3 as well); color
 *   capability is entirely driven by `supported_color_modes`.
 */
data class LightCapabilities(
    val supportedColorModes: Set<LightColorMode>,
    /** `color_mode` attribute: the mode currently in effect, or null when the light is off or unset. */
    val colorMode: LightColorMode?,
    val isOn: Boolean,
    /** `brightness` (0..255) converted to a 0..100 percentage; null when off or unsupported. */
    val brightnessPercent: Int?,
    val colorTempKelvin: Int?,
    val minColorTempKelvin: Int?,
    val maxColorTempKelvin: Int?,
    /** Hue in 0..360 degrees, from `hs_color`; null unless both hue and saturation parsed. */
    val hue: Double?,
    /** Saturation in 0..100, from `hs_color`; null unless both hue and saturation parsed. */
    val saturation: Double?,
) {
    val supportsBrightness: Boolean get() = supportedColorModes.any { it != LightColorMode.ONOFF }

    val supportsColorTemp: Boolean get() = LightColorMode.COLOR_TEMP in supportedColorModes

    val supportsColor: Boolean get() = supportedColorModes.any { it in COLOR_PICKER_MODES }

    companion object {
        private val COLOR_PICKER_MODES =
            setOf(LightColorMode.HS, LightColorMode.XY, LightColorMode.RGB, LightColorMode.RGBW, LightColorMode.RGBWW)

        /** HA's own fallback range (`DEFAULT_MIN_KELVIN`/`DEFAULT_MAX_KELVIN`) when an integration doesn't set one. */
        private const val DEFAULT_MIN_KELVIN = 2000
        private const val DEFAULT_MAX_KELVIN = 6535

        fun from(entity: HaEntity): LightCapabilities {
            val attributes = entity.attributes
            val supportedColorModes = (attributes["supported_color_modes"] as? JsonArray)
                ?.mapNotNull { it.stringOrNull()?.let(LightColorMode::from) }
                ?.toSet()
                ?: emptySet()
            val colorMode = attributes["color_mode"].stringOrNull()?.let(LightColorMode::from)
            val supportsColorTemp = LightColorMode.COLOR_TEMP in supportedColorModes

            val brightness = attributes["brightness"].asInt()?.coerceIn(0, 255)
            val brightnessPercent = brightness?.let { ((it * 100.0) / 255.0).roundToInt() }

            val colorTempKelvin = attributes["color_temp_kelvin"].asInt()
            val minColorTempKelvin = if (supportsColorTemp) attributes["min_color_temp_kelvin"].asInt() ?: DEFAULT_MIN_KELVIN else null
            val maxColorTempKelvin = if (supportsColorTemp) attributes["max_color_temp_kelvin"].asInt() ?: DEFAULT_MAX_KELVIN else null

            val hsColor = attributes["hs_color"] as? JsonArray
            val hue = hsColor?.getOrNull(0).asDouble()
            val saturation = hsColor?.getOrNull(1).asDouble()
            val (clampedHue, clampedSaturation) = if (hue != null && saturation != null) {
                hue.coerceIn(0.0, 360.0) to saturation.coerceIn(0.0, 100.0)
            } else {
                null to null
            }

            return LightCapabilities(
                supportedColorModes = supportedColorModes,
                colorMode = colorMode,
                isOn = entity.state == "on",
                brightnessPercent = brightnessPercent,
                colorTempKelvin = colorTempKelvin,
                minColorTempKelvin = minColorTempKelvin,
                maxColorTempKelvin = maxColorTempKelvin,
                hue = clampedHue,
                saturation = clampedSaturation,
            )
        }

        private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

        /** Accepts a plain int or a numeric value with a fractional part (e.g. a malformed `128.0`); never throws. */
        private fun JsonElement?.asInt(): Int? {
            val primitive = this as? JsonPrimitive ?: return null
            return primitive.intOrNull ?: primitive.doubleOrNull?.roundToInt()
        }

        private fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.doubleOrNull
    }
}

/**
 * Builds `ServiceCall`s for `light.*` services. Verified against HA core
 * (homeassistant/components/light/__init__.py, current `dev`/2026.9.x): `turn_on`/`toggle` share the
 * same schema (`brightness_pct` 0..100, `color_temp_kelvin` positive int, `hs_color` = [hue 0..360,
 * saturation 0..100]); `turn_off` only takes `transition`/`flash`, neither of which this module exposes.
 */
object LightCommands {
    private const val DOMAIN = "light"

    fun turnOn(): ServiceCall = ServiceCall(DOMAIN, "turn_on")

    fun turnOff(): ServiceCall = ServiceCall(DOMAIN, "turn_off")

    fun toggle(): ServiceCall = ServiceCall(DOMAIN, "toggle")

    /**
     * Sets brightness as a 0..100 percentage of `turn_on`'s `brightness_pct` field. There's no
     * "0% but still on" state in HA, so a percent of 0 or below turns the light off instead
     * (mirrors how Lovelace's own brightness slider behaves at its lowest point).
     */
    fun setBrightnessPercent(percent: Int): ServiceCall = if (percent <= 0) {
        turnOff()
    } else {
        ServiceCall(DOMAIN, "turn_on", buildJsonObject { put("brightness_pct", percent.coerceAtMost(100)) })
    }

    /** Clamps [kelvin] to the entity's own range when [capabilities] reports one, else sends it as-is. */
    fun setColorTempKelvin(kelvin: Int, capabilities: LightCapabilities): ServiceCall {
        val min = capabilities.minColorTempKelvin
        val max = capabilities.maxColorTempKelvin
        val clamped = if (min != null && max != null) kelvin.coerceIn(minOf(min, max), maxOf(min, max)) else kelvin
        return ServiceCall(DOMAIN, "turn_on", buildJsonObject { put("color_temp_kelvin", clamped) })
    }

    /** Clamps hue to 0..360 and saturation to 0..100 before sending `hs_color`. */
    fun setHsColor(hue: Double, saturation: Double): ServiceCall {
        val clampedHue = hue.coerceIn(0.0, 360.0)
        val clampedSaturation = saturation.coerceIn(0.0, 100.0)
        val hsColor = JsonArray(listOf(JsonPrimitive(clampedHue), JsonPrimitive(clampedSaturation)))
        return ServiceCall(DOMAIN, "turn_on", buildJsonObject { put("hs_color", hsColor) })
    }
}
