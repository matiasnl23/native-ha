package com.matiasnl.hakiosk.ui.dashboard.tiles

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.ha.domain.AlarmPanelState
import com.matiasnl.hakiosk.ui.dashboard.tiles.alarm.TileColors
import com.matiasnl.hakiosk.ui.dashboard.tiles.alarm.TriggeredPulse
import com.matiasnl.hakiosk.ui.dashboard.tiles.alarm.alarmColors
import com.matiasnl.hakiosk.ui.dashboard.tiles.alarm.alarmStateText
import com.matiasnl.hakiosk.ui.dashboard.tiles.alarm.tone
import com.matiasnl.hakiosk.ui.dashboard.tiles.climate.climateReadingDetail
import com.matiasnl.hakiosk.ui.dashboard.tiles.climate.isEmphasized
import com.matiasnl.hakiosk.ui.dashboard.tiles.climate.tint

/** The localized state line for [summary], or null to fall back to the raw state. */
@Composable
fun summaryStateText(summary: TileSummary): String? = when (summary) {
    TileSummary.Default -> null
    is TileSummary.Light -> when {
        !summary.isOn -> stringResource(R.string.light_state_off)
        summary.brightnessPercent != null -> stringResource(R.string.light_state_on_brightness, summary.brightnessPercent)
        else -> stringResource(R.string.light_state_on)
    }
    is TileSummary.Alarm -> alarmStateText(summary.state)
    is TileSummary.Climate -> climateReadingDetail(summary)
}

/** The tile's own container/content colors (e.g. an alarm's state color), or null for the default on/off colors. */
@Composable
fun summaryTileColors(summary: TileSummary): TileColors? = when (summary) {
    is TileSummary.Alarm -> alarmColors(summary.state.tone)
    is TileSummary.Light -> if (summary.isOn) tintColors(summary.color ?: DefaultLightTint).let { TileColors(it.track, it.content) } else null
    is TileSummary.Climate -> summary.tint()?.let { tint ->
        val colors = tintColors(tint)
        // Softer than a light: the tile's reading and icon carry the state, the tint only hints at it.
        TileColors(if (summary.isEmphasized) colors.medium else colors.soft, colors.content)
    }
    TileSummary.Default -> null
}

/**
 * Drawn behind the tile's content: a pulse while an alarm is triggered, or an on light's brightness as a
 * fill over the tile (the whole tile when it has no brightness); otherwise nothing.
 */
@Composable
fun TileSummaryBackground(summary: TileSummary, modifier: Modifier = Modifier) {
    when (summary) {
        is TileSummary.Alarm -> if (summary.state == AlarmPanelState.TRIGGERED) TriggeredPulse(modifier)
        is TileSummary.Light -> if (summary.isOn) {
            LevelFill(
                fraction = summary.brightnessPercent?.div(100f) ?: 1f,
                color = tintColors(summary.color ?: DefaultLightTint).fill,
                modifier = modifier,
            )
        }
        is TileSummary.Climate, TileSummary.Default -> Unit
    }
}

/**
 * Fills [fraction] of the available width with [color], from the start edge. The animated width is only
 * read while drawing, so a brightness change redraws without recomposing.
 */
@Composable
private fun LevelFill(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val animated = animateFloatAsState(fraction.coerceIn(0f, 1f), label = "levelFill")
    Spacer(
        modifier.drawBehind {
            val width = size.width * animated.value
            val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
            drawRect(color, topLeft = Offset(left, 0f), size = Size(width, size.height))
        },
    )
}

@Immutable
private data class TintColors(val track: Color, val fill: Color, val soft: Color, val medium: Color, val content: Color)

/** HA's own active-light amber, for lights that report no color (brightness-only or on/off). */
private val DefaultLightTint = Color(0xFFFFA000)

/**
 * [tint] blended over the theme's tile surface: a faint track and a stronger fill. The text keeps the
 * theme's own color, so the fill is backed off toward the surface until that text stays readable over it
 * (dark text needs a light enough fill, light text a dark enough one).
 */
@Composable
private fun tintColors(tint: Color): TintColors {
    val scheme = MaterialTheme.colorScheme
    val base = scheme.surfaceVariant
    val content = scheme.onSurfaceVariant
    return remember(tint, base, content) {
        val darkText = content.luminance() < 0.5f
        var amount = MAX_FILL_AMOUNT
        var fill = lerp(base, tint, amount)
        while (amount > MIN_FILL_AMOUNT && !readable(fill, darkText)) {
            amount -= FILL_AMOUNT_STEP
            fill = lerp(base, tint, amount)
        }
        TintColors(
            track = lerp(base, tint, amount * TRACK_TO_FILL),
            fill = fill,
            soft = lerp(base, tint, amount * SOFT_TO_FILL),
            medium = lerp(base, tint, amount * MEDIUM_TO_FILL),
            content = content,
        )
    }
}

private fun readable(background: Color, darkText: Boolean): Boolean =
    if (darkText) background.luminance() >= MIN_LUMINANCE_UNDER_DARK_TEXT else background.luminance() <= MAX_LUMINANCE_UNDER_LIGHT_TEXT

private const val MAX_FILL_AMOUNT = 0.7f
private const val MIN_FILL_AMOUNT = 0.3f
private const val FILL_AMOUNT_STEP = 0.05f
private const val TRACK_TO_FILL = 0.35f
private const val SOFT_TO_FILL = 0.2f
private const val MEDIUM_TO_FILL = 0.45f
private const val MIN_LUMINANCE_UNDER_DARK_TEXT = 0.3f
private const val MAX_LUMINANCE_UNDER_LIGHT_TEXT = 0.2f
