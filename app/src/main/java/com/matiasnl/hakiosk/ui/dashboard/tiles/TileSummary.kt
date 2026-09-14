package com.matiasnl.hakiosk.ui.dashboard.tiles

/**
 * A tile's domain-specific summary, derived once per entity state change by
 * [DomainTileBehavior.summarize]. Rendered by [summaryStateText] and [TileSummaryVisual].
 */
sealed interface TileSummary {
    /** No special summary: the tile shows the raw state (and unit). */
    data object Default : TileSummary

    /** [brightnessPercent] is null when the light is off or doesn't support brightness. */
    data class Light(val isOn: Boolean, val brightnessPercent: Int?) : TileSummary
}
