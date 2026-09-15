package com.matiasnl.hakiosk.ui.dashboard.tiles

import androidx.compose.ui.graphics.Color
import com.matiasnl.hakiosk.data.ha.domain.AlarmPanelState

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
     * a default warm tint).
     */
    data class Light(val isOn: Boolean, val brightnessPercent: Int?, val color: Color? = null) : TileSummary

    /** An alarm control panel: its state picks the tile's text and color. */
    data class Alarm(val state: AlarmPanelState) : TileSummary
}
