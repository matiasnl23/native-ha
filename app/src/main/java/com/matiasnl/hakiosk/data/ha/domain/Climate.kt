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

/**
 * `HVACMode` values, verified against HA core (homeassistant/components/climate/const.py, `dev`/2026.10).
 * A climate entity's state *is* its HVAC mode.
 */
enum class HvacMode(val value: String) {
    OFF("off"),
    HEAT("heat"),
    COOL("cool"),
    HEAT_COOL("heat_cool"),
    AUTO("auto"),
    DRY("dry"),
    FAN_ONLY("fan_only"),
    ;

    companion object {
        fun from(value: String?): HvacMode? = entries.firstOrNull { it.value == value }
    }
}

/**
 * `HVACAction` values (same source): what the device is doing right now. `defrosting` and `preheating`
 * exist since HA 2024.12; unrecognized values parse as null.
 */
enum class HvacAction(val value: String) {
    COOLING("cooling"),
    DEFROSTING("defrosting"),
    DRYING("drying"),
    FAN("fan"),
    HEATING("heating"),
    IDLE("idle"),
    OFF("off"),
    PREHEATING("preheating"),
    ;

    companion object {
        fun from(value: String?): HvacAction? = entries.firstOrNull { it.value == value }
    }
}

/** `ClimateEntityFeature` bits (same source). TURN_ON/TURN_OFF exist since HA 2024.2, SWING_HORIZONTAL_MODE since 2024.12. */
object ClimateFeature {
    const val TARGET_TEMPERATURE = 1
    const val TARGET_TEMPERATURE_RANGE = 2
    const val TARGET_HUMIDITY = 4
    const val FAN_MODE = 8
    const val PRESET_MODE = 16
    const val SWING_MODE = 32
    const val TURN_OFF = 128
    const val TURN_ON = 256
    const val SWING_HORIZONTAL_MODE = 512
}

/**
 * Decoded state of a `climate` entity. Verified against HA core (homeassistant/components/climate/__init__.py,
 * `ClimateEntity.capability_attributes`/`state_attributes`, `dev`/2026.10):
 * - `hvac_modes`, `min_temp`, `max_temp` are always published; `target_temp_step` only when the integration
 *   sets one (HA's frontend then falls back to 0.5 °C / 1 °F — see [DEFAULT_TEMPERATURE_STEP]).
 * - `temperature` only with TARGET_TEMPERATURE; `target_temp_low`/`target_temp_high` only with
 *   TARGET_TEMPERATURE_RANGE; each may be null (e.g. while off, or the range outside `heat_cool`).
 * - Humidity, fan, preset and swing attributes only with their feature bit.
 * - Temperatures are in the HA instance's unit system, which isn't part of the entity's attributes.
 */
data class ClimateCapabilities(
    /** From the entity's state; null while unavailable/unknown or for an unrecognized mode. */
    val hvacMode: HvacMode?,
    /** Supported modes in the order the integration reports them; unrecognized values dropped. */
    val hvacModes: List<HvacMode>,
    val hvacAction: HvacAction?,
    val supportedFeatures: Int,
    val currentTemperature: Double?,
    val targetTemperature: Double?,
    val targetTemperatureLow: Double?,
    val targetTemperatureHigh: Double?,
    val minTemperature: Double,
    val maxTemperature: Double,
    /** The integration's own step, or null to use [DEFAULT_TEMPERATURE_STEP]. */
    val targetTemperatureStep: Double?,
    val currentHumidity: Double?,
    val targetHumidity: Double?,
    val minHumidity: Double,
    val maxHumidity: Double,
    val fanMode: String?,
    val fanModes: List<String>,
    val presetMode: String?,
    val presetModes: List<String>,
    val swingMode: String?,
    val swingModes: List<String>,
    val swingHorizontalMode: String?,
    val swingHorizontalModes: List<String>,
) {
    private fun has(feature: Int) = supportedFeatures and feature != 0

    /** A single setpoint is shown when the feature is on and the device reports one (null e.g. while off). */
    val hasTargetTemperature: Boolean get() = has(ClimateFeature.TARGET_TEMPERATURE) && targetTemperature != null

    /** A low/high range is shown when the feature is on and the device reports both ends. */
    val hasTargetTemperatureRange: Boolean
        get() = has(ClimateFeature.TARGET_TEMPERATURE_RANGE) && targetTemperatureLow != null && targetTemperatureHigh != null

    val hasTargetHumidity: Boolean get() = has(ClimateFeature.TARGET_HUMIDITY) && targetHumidity != null

    val hasFanModes: Boolean get() = has(ClimateFeature.FAN_MODE) && fanModes.isNotEmpty()

    val hasPresetModes: Boolean get() = has(ClimateFeature.PRESET_MODE) && presetModes.isNotEmpty()

    val hasSwingModes: Boolean get() = has(ClimateFeature.SWING_MODE) && swingModes.isNotEmpty()

    val hasSwingHorizontalModes: Boolean get() = has(ClimateFeature.SWING_HORIZONTAL_MODE) && swingHorizontalModes.isNotEmpty()

    val temperatureStep: Double get() = targetTemperatureStep?.takeIf { it > 0.0 } ?: DEFAULT_TEMPERATURE_STEP

    companion object {
        /** HA's frontend default for Celsius installs when an integration sets no `target_temp_step`. */
        const val DEFAULT_TEMPERATURE_STEP = 0.5

        /** HA's own fallbacks (`DEFAULT_MIN_TEMP`/`DEFAULT_MAX_TEMP` in °C, `DEFAULT_MIN/MAX_HUMIDITY`). */
        private const val DEFAULT_MIN_TEMP = 7.0
        private const val DEFAULT_MAX_TEMP = 35.0
        private const val DEFAULT_MIN_HUMIDITY = 30.0
        private const val DEFAULT_MAX_HUMIDITY = 99.0

        fun from(entity: HaEntity): ClimateCapabilities {
            val attributes = entity.attributes
            val minTemp = attributes["min_temp"].asDouble() ?: DEFAULT_MIN_TEMP
            val maxTemp = attributes["max_temp"].asDouble() ?: DEFAULT_MAX_TEMP
            val minHumidity = attributes["min_humidity"].asDouble() ?: DEFAULT_MIN_HUMIDITY
            val maxHumidity = attributes["max_humidity"].asDouble() ?: DEFAULT_MAX_HUMIDITY
            return ClimateCapabilities(
                hvacMode = HvacMode.from(entity.state),
                hvacModes = attributes["hvac_modes"].stringList().mapNotNull(HvacMode::from).distinct(),
                hvacAction = HvacAction.from(attributes["hvac_action"].asString()),
                supportedFeatures = attributes["supported_features"].asInt() ?: 0,
                currentTemperature = attributes["current_temperature"].asDouble(),
                targetTemperature = attributes["temperature"].asDouble(),
                targetTemperatureLow = attributes["target_temp_low"].asDouble(),
                targetTemperatureHigh = attributes["target_temp_high"].asDouble(),
                minTemperature = minOf(minTemp, maxTemp),
                maxTemperature = maxOf(minTemp, maxTemp),
                targetTemperatureStep = attributes["target_temp_step"].asDouble(),
                currentHumidity = attributes["current_humidity"].asDouble(),
                targetHumidity = attributes["humidity"].asDouble(),
                minHumidity = minOf(minHumidity, maxHumidity),
                maxHumidity = maxOf(minHumidity, maxHumidity),
                fanMode = attributes["fan_mode"].asString(),
                fanModes = attributes["fan_modes"].stringList(),
                presetMode = attributes["preset_mode"].asString(),
                presetModes = attributes["preset_modes"].stringList(),
                swingMode = attributes["swing_mode"].asString(),
                swingModes = attributes["swing_modes"].stringList(),
                swingHorizontalMode = attributes["swing_horizontal_mode"].asString(),
                swingHorizontalModes = attributes["swing_horizontal_modes"].stringList(),
            )
        }

        private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

        private fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

        private fun JsonElement?.stringList(): List<String> =
            (this as? JsonArray)?.mapNotNull { it.asString() }?.distinct() ?: emptyList()
    }
}

/**
 * Builds `ServiceCall`s for `climate.*` services. Verified against HA core (climate/__init__.py and
 * services.yaml, `dev`/2026.10): `set_temperature` takes `temperature` *or* both `target_temp_low` and
 * `target_temp_high` (low must not exceed high) and rejects values outside `min_temp`..`max_temp`;
 * `set_humidity` takes an int within `min_humidity`..`max_humidity`; the mode services take a string.
 */
object ClimateCommands {
    private const val DOMAIN = "climate"

    /** Requires TURN_ON (HA 2024.2+). */
    fun turnOn(): ServiceCall = ServiceCall(DOMAIN, "turn_on")

    /** Requires TURN_OFF (HA 2024.2+). */
    fun turnOff(): ServiceCall = ServiceCall(DOMAIN, "turn_off")

    fun setHvacMode(mode: HvacMode): ServiceCall =
        ServiceCall(DOMAIN, "set_hvac_mode", buildJsonObject { put("hvac_mode", mode.value) })

    /** Clamped to the entity's range so HA doesn't reject it. */
    fun setTemperature(temperature: Double, capabilities: ClimateCapabilities): ServiceCall =
        ServiceCall(
            DOMAIN,
            "set_temperature",
            buildJsonObject { put("temperature", temperature.coerceIn(capabilities.minTemperature, capabilities.maxTemperature)) },
        )

    /** Both ends clamped to the entity's range, and swapped if needed so low never exceeds high. */
    fun setTemperatureRange(low: Double, high: Double, capabilities: ClimateCapabilities): ServiceCall {
        val min = capabilities.minTemperature
        val max = capabilities.maxTemperature
        val a = low.coerceIn(min, max)
        val b = high.coerceIn(min, max)
        return ServiceCall(
            DOMAIN,
            "set_temperature",
            buildJsonObject {
                put("target_temp_low", minOf(a, b))
                put("target_temp_high", maxOf(a, b))
            },
        )
    }

    fun setHumidity(humidity: Int, capabilities: ClimateCapabilities): ServiceCall {
        val clamped = humidity.coerceIn(
            kotlin.math.ceil(capabilities.minHumidity).toInt(),
            kotlin.math.floor(capabilities.maxHumidity).toInt(),
        )
        return ServiceCall(DOMAIN, "set_humidity", buildJsonObject { put("humidity", clamped) })
    }

    fun setFanMode(mode: String): ServiceCall = ServiceCall(DOMAIN, "set_fan_mode", buildJsonObject { put("fan_mode", mode) })

    fun setPresetMode(mode: String): ServiceCall =
        ServiceCall(DOMAIN, "set_preset_mode", buildJsonObject { put("preset_mode", mode) })

    fun setSwingMode(mode: String): ServiceCall =
        ServiceCall(DOMAIN, "set_swing_mode", buildJsonObject { put("swing_mode", mode) })

    /** Requires SWING_HORIZONTAL_MODE (HA 2024.12+). */
    fun setSwingHorizontalMode(mode: String): ServiceCall =
        ServiceCall(DOMAIN, "set_swing_horizontal_mode", buildJsonObject { put("swing_horizontal_mode", mode) })
}
