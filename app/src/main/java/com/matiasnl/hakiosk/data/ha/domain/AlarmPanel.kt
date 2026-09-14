package com.matiasnl.hakiosk.data.ha.domain

import com.matiasnl.hakiosk.data.ha.HaEntity
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * State of an `alarm_control_panel` entity. Matches `AlarmControlPanelState` in HA core
 * (homeassistant/components/alarm_control_panel/const.py, current `dev`); no other value exists in
 * that enum. HA 2024.11 introduced this enum-based `state` (previously top-level `STATE_ALARM_*`
 * constants in `homeassistant/const.py`); those old constants were fully removed in HA 2025.11, so
 * this is the only shape current HA ever reports.
 */
enum class AlarmPanelState {
    DISARMED,
    ARMED_HOME,
    ARMED_AWAY,
    ARMED_NIGHT,
    ARMED_VACATION,
    ARMED_CUSTOM_BYPASS,
    PENDING,
    ARMING,
    DISARMING,
    TRIGGERED,

    /** `state == "unavailable"`. */
    UNAVAILABLE,

    /** `state == "unknown"`, or any value this app doesn't recognize (future-proofing). */
    UNKNOWN,
    ;

    companion object {
        fun from(state: String): AlarmPanelState = when (state) {
            "disarmed" -> DISARMED
            "armed_home" -> ARMED_HOME
            "armed_away" -> ARMED_AWAY
            "armed_night" -> ARMED_NIGHT
            "armed_vacation" -> ARMED_VACATION
            "armed_custom_bypass" -> ARMED_CUSTOM_BYPASS
            "pending" -> PENDING
            "arming" -> ARMING
            "disarming" -> DISARMING
            "triggered" -> TRIGGERED
            "unavailable" -> UNAVAILABLE
            else -> UNKNOWN
        }

        fun from(entity: HaEntity): AlarmPanelState = from(entity.state)
    }
}

/** One of the panel's arm modes. Mirrors `AlarmArmMode`-shaped arm services; `TRIGGER` is not an arm mode, see [AlarmPanelCapabilities.supportsTrigger]. */
enum class AlarmArmMode { HOME, AWAY, NIGHT, VACATION, CUSTOM_BYPASS }

/**
 * `code_format` attribute, i.e. what kind of code (if any) the panel's keypad expects.
 * `CodeFormat` in HA core has only `NUMBER`/`TEXT`; the entity property returns Python `None` (no
 * attribute value at all) when no code is required, mapped here to [NONE].
 */
enum class AlarmCodeFormat { NONE, NUMBER, TEXT }

/**
 * Decoded `supported_features` bitmask and code requirements of an `alarm_control_panel` entity.
 * Bit values verified against `AlarmControlPanelEntityFeature` in HA core
 * (homeassistant/components/alarm_control_panel/const.py, current `dev`):
 * ARM_HOME=1, ARM_AWAY=2, ARM_NIGHT=4, TRIGGER=8, ARM_CUSTOM_BYPASS=16, ARM_VACATION=32.
 */
data class AlarmPanelCapabilities(
    val supportedArmModes: Set<AlarmArmMode>,
    val supportsTrigger: Boolean,
    val codeFormat: AlarmCodeFormat,
    /** Whether the *arm* services reject a missing code server-side. Attribute default is `true` when absent. */
    val codeArmRequired: Boolean,
    /**
     * Whether the UI should prompt for a code before disarming. HA core's `code_arm_required` only
     * gates the arm services (`check_code_arm_required` is never called from the disarm handler) —
     * whether disarm itself needs a code is entirely up to each integration and isn't exposed as an
     * attribute. We follow the same conservative rule the official frontend uses: prompt whenever the
     * panel declares any [codeFormat] at all.
     */
    val codeRequiredForDisarm: Boolean,
) {
    companion object {
        private const val FEATURE_ARM_HOME = 1
        private const val FEATURE_ARM_AWAY = 2
        private const val FEATURE_ARM_NIGHT = 4
        private const val FEATURE_TRIGGER = 8
        private const val FEATURE_ARM_CUSTOM_BYPASS = 16
        private const val FEATURE_ARM_VACATION = 32

        fun from(entity: HaEntity): AlarmPanelCapabilities {
            val features = (entity.attributes["supported_features"] as? JsonPrimitive)?.intOrNull ?: 0
            val modes = buildSet {
                if (features and FEATURE_ARM_HOME != 0) add(AlarmArmMode.HOME)
                if (features and FEATURE_ARM_AWAY != 0) add(AlarmArmMode.AWAY)
                if (features and FEATURE_ARM_NIGHT != 0) add(AlarmArmMode.NIGHT)
                if (features and FEATURE_ARM_VACATION != 0) add(AlarmArmMode.VACATION)
                if (features and FEATURE_ARM_CUSTOM_BYPASS != 0) add(AlarmArmMode.CUSTOM_BYPASS)
            }
            val codeFormat = when ((entity.attributes["code_format"] as? JsonPrimitive)?.contentOrNull) {
                "number" -> AlarmCodeFormat.NUMBER
                "text" -> AlarmCodeFormat.TEXT
                else -> AlarmCodeFormat.NONE
            }
            val codeArmRequired = (entity.attributes["code_arm_required"] as? JsonPrimitive)?.booleanOrNull ?: true
            return AlarmPanelCapabilities(
                supportedArmModes = modes,
                supportsTrigger = features and FEATURE_TRIGGER != 0,
                codeFormat = codeFormat,
                codeArmRequired = codeArmRequired,
                codeRequiredForDisarm = codeFormat != AlarmCodeFormat.NONE,
            )
        }
    }
}

/**
 * Builds `ServiceCall`s for `alarm_control_panel.*` services. Verified against HA core
 * (homeassistant/components/alarm_control_panel/__init__.py and services.yaml, current `dev`):
 * every arm/disarm service takes a single optional string field named `code`; it's included in the
 * payload only when non-blank, matching how the official frontend omits it for panels with no code.
 */
object AlarmPanelCommands {
    private const val DOMAIN = "alarm_control_panel"

    fun armHome(code: String? = null): ServiceCall = call("alarm_arm_home", code)

    fun armAway(code: String? = null): ServiceCall = call("alarm_arm_away", code)

    fun armNight(code: String? = null): ServiceCall = call("alarm_arm_night", code)

    fun armVacation(code: String? = null): ServiceCall = call("alarm_arm_vacation", code)

    fun armCustomBypass(code: String? = null): ServiceCall = call("alarm_arm_custom_bypass", code)

    /** Dispatches by [mode] instead of calling the per-mode function directly, e.g. from a generic arm-mode picker. */
    fun arm(mode: AlarmArmMode, code: String? = null): ServiceCall = when (mode) {
        AlarmArmMode.HOME -> armHome(code)
        AlarmArmMode.AWAY -> armAway(code)
        AlarmArmMode.NIGHT -> armNight(code)
        AlarmArmMode.VACATION -> armVacation(code)
        AlarmArmMode.CUSTOM_BYPASS -> armCustomBypass(code)
    }

    fun disarm(code: String? = null): ServiceCall = call("alarm_disarm", code)

    private fun call(service: String, code: String?): ServiceCall = ServiceCall(
        domain = DOMAIN,
        service = service,
        data = buildJsonObject {
            if (!code.isNullOrBlank()) put("code", code)
        },
    )
}
