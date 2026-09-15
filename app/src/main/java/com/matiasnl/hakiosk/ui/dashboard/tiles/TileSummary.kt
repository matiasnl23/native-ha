package com.matiasnl.hakiosk.ui.dashboard.tiles

import androidx.compose.ui.graphics.Color
import com.matiasnl.hakiosk.data.ha.domain.AlarmPanelState
import com.matiasnl.hakiosk.data.ha.domain.HvacAction
import com.matiasnl.hakiosk.data.ha.domain.HvacMode

/**
 * A tile's domain-specific summary, derived once per entity state change by
 * [DomainTileBehavior.summarize]. Rendered by [summaryStateText], [summaryTileColors] and
 * [TileSummaryBackground].
 */
sealed interface TileSummary {
    /** No special summary: the tile shows the raw state (and unit). */
    data object Default : TileSummary

    /**
     * [brightnessPercent] is null when the light is off or doesn't support brightness. [color] is the
     * light's current color at full value, or null when it's off or reports no color (the tile then uses
     * a default warm tint). [supportsBrightness] enables the tile's brightness swipe, on or off.
     */
    data class Light(
        val isOn: Boolean,
        val brightnessPercent: Int?,
        val color: Color? = null,
        val supportsBrightness: Boolean = false,
    ) : TileSummary

    /** An alarm control panel: its state picks the tile's text and color. */
    data class Alarm(val state: AlarmPanelState) : TileSummary

    /**
     * A climate entity: [hvacMode] (null while unavailable/unknown) and [hvacAction] pick the text and tint.
     * Either [targetTemperature] or the [targetTemperatureLow]..[targetTemperatureHigh] range is set, never both.
     */
    data class Climate(
        val hvacMode: HvacMode?,
        val hvacAction: HvacAction? = null,
        val currentTemperature: Double? = null,
        val targetTemperature: Double? = null,
        val targetTemperatureLow: Double? = null,
        val targetTemperatureHigh: Double? = null,
    ) : TileSummary
}
