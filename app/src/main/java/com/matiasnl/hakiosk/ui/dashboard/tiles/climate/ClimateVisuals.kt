package com.matiasnl.hakiosk.ui.dashboard.tiles.climate

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.ha.domain.HvacAction
import com.matiasnl.hakiosk.data.ha.domain.HvacMode
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileSummary
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A temperature with at most one decimal and the degree sign, in [locale]'s number format ("24,5°").
 * The unit is left out: it's the HA instance's unit system, which entities don't report.
 */
fun formatTemperature(value: Double, locale: Locale = Locale.getDefault()): String {
    val tenths = (value * 10).roundToLong()
    return if (tenths % 10 == 0L) {
        String.format(locale, "%d°", tenths / 10)
    } else {
        String.format(locale, "%.1f°", tenths / 10.0)
    }
}

@StringRes
fun HvacMode.labelRes(): Int = when (this) {
    HvacMode.OFF -> R.string.climate_mode_off
    HvacMode.HEAT -> R.string.climate_mode_heat
    HvacMode.COOL -> R.string.climate_mode_cool
    HvacMode.HEAT_COOL -> R.string.climate_mode_heat_cool
    HvacMode.AUTO -> R.string.climate_mode_auto
    HvacMode.DRY -> R.string.climate_mode_dry
    HvacMode.FAN_ONLY -> R.string.climate_mode_fan_only
}

@StringRes
fun HvacAction.labelRes(): Int = when (this) {
    HvacAction.COOLING -> R.string.climate_action_cooling
    HvacAction.DEFROSTING -> R.string.climate_action_defrosting
    HvacAction.DRYING -> R.string.climate_action_drying
    HvacAction.FAN -> R.string.climate_action_fan
    HvacAction.HEATING -> R.string.climate_action_heating
    HvacAction.IDLE -> R.string.climate_action_idle
    HvacAction.OFF -> R.string.climate_mode_off
    HvacAction.PREHEATING -> R.string.climate_action_preheating
}

/** True while the device reports doing something (heating, cooling, …), not just sitting in a mode. */
val HvacAction?.isRunning: Boolean get() = this != null && this != HvacAction.IDLE && this != HvacAction.OFF

/** The action while running, else the mode: "Enfriando" beats "Frío" when the compressor is on. */
@Composable
fun climateStatusText(mode: HvacMode, action: HvacAction?): String =
    stringResource(if (action.isRunning) action!!.labelRes() else mode.labelRes())

/** The line under the tile's reading: "Enfriando · objetivo 22°", "Apagado"; null when the mode is unknown. */
@Composable
fun climateReadingDetail(summary: TileSummary.Climate): String? {
    val mode = summary.hvacMode ?: return null
    val status = climateStatusText(mode, summary.hvacAction)
    if (mode == HvacMode.OFF) return status
    val target = climateTargetText(summary, Locale.getDefault()) ?: return status
    return "$status · ${stringResource(R.string.climate_tile_target, target)}"
}

/** "22°" or "20°–24°"; null when the device reports no setpoint. */
fun climateTargetText(summary: TileSummary.Climate, locale: Locale = Locale.getDefault()): String? {
    val low = summary.targetTemperatureLow
    val high = summary.targetTemperatureHigh
    return when {
        low != null && high != null -> "${formatTemperature(low, locale)}–${formatTemperature(high, locale)}"
        else -> summary.targetTemperature?.let { formatTemperature(it, locale) }
    }
}

// Close to HA's own state colors for climate modes.
private val HeatTint = Color(0xFFFF8100)
private val CoolTint = Color(0xFF2B9AF9)
private val AutoTint = Color(0xFF43A047)
private val DryTint = Color(0xFFEFBD07)
private val FanTint = Color(0xFF00BCD4)

/** The tile tint: what the device is doing when it says, else its mode; null while off or unknown. */
fun TileSummary.Climate.tint(): Color? {
    val mode = hvacMode ?: return null
    if (mode == HvacMode.OFF) return null
    return when (hvacAction) {
        HvacAction.HEATING, HvacAction.PREHEATING -> HeatTint
        HvacAction.COOLING, HvacAction.DEFROSTING -> CoolTint
        HvacAction.DRYING -> DryTint
        HvacAction.FAN -> FanTint
        HvacAction.IDLE, HvacAction.OFF, null -> when (mode) {
            HvacMode.HEAT -> HeatTint
            HvacMode.COOL -> CoolTint
            HvacMode.HEAT_COOL, HvacMode.AUTO -> AutoTint
            HvacMode.DRY -> DryTint
            HvacMode.FAN_ONLY -> FanTint
            HvacMode.OFF -> null
        }
    }
}

/** A strong tint while running or when the device doesn't report its action; a faint one while idle. */
val TileSummary.Climate.isEmphasized: Boolean get() = hvacAction == null || hvacAction.isRunning

/** Known preset/fan/swing values get a Spanish label; anything integration-specific is prettified. */
@Composable
fun presetLabel(value: String): String = knownLabel(PresetLabels, value)

@Composable
fun fanModeLabel(value: String): String = knownLabel(FanModeLabels, value)

@Composable
fun swingModeLabel(value: String): String = knownLabel(SwingModeLabels, value)

@Composable
private fun knownLabel(labels: Map<String, Int>, value: String): String =
    labels[value]?.let { stringResource(it) } ?: prettifyModeValue(value)

/** "high_speed" → "High speed". */
fun prettifyModeValue(value: String, locale: Locale = Locale.getDefault()): String =
    value.replace('_', ' ').trim().replaceFirstChar { it.titlecase(locale) }

private val PresetLabels = mapOf(
    "none" to R.string.climate_preset_none,
    "eco" to R.string.climate_preset_eco,
    "away" to R.string.climate_preset_away,
    "boost" to R.string.climate_preset_boost,
    "comfort" to R.string.climate_preset_comfort,
    "home" to R.string.climate_preset_home,
    "sleep" to R.string.climate_preset_sleep,
    "activity" to R.string.climate_preset_activity,
)

private val FanModeLabels = mapOf(
    "on" to R.string.climate_fan_on,
    "off" to R.string.climate_fan_off,
    "auto" to R.string.climate_fan_auto,
    "low" to R.string.climate_fan_low,
    "medium" to R.string.climate_fan_medium,
    "high" to R.string.climate_fan_high,
    "top" to R.string.climate_fan_top,
    "middle" to R.string.climate_fan_middle,
    "focus" to R.string.climate_fan_focus,
    "diffuse" to R.string.climate_fan_diffuse,
)

private val SwingModeLabels = mapOf(
    "on" to R.string.climate_swing_on,
    "off" to R.string.climate_swing_off,
    "both" to R.string.climate_swing_both,
    "vertical" to R.string.climate_swing_vertical,
    "horizontal" to R.string.climate_swing_horizontal,
)

/** Whether two temperatures are the same setpoint (they come from floats and doubles). */
internal fun sameTemperature(a: Double, b: Double): Boolean = abs(a - b) < 0.001
