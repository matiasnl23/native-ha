package com.matiasnl.hakiosk.ui.dashboard.tiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.matiasnl.hakiosk.R

/** The localized state line for [summary], or null to fall back to the raw state. */
@Composable
fun summaryStateText(summary: TileSummary): String? = when (summary) {
    TileSummary.Default -> null
    is TileSummary.Light -> when {
        !summary.isOn -> stringResource(R.string.light_state_off)
        summary.brightnessPercent != null -> stringResource(R.string.light_state_on_brightness, summary.brightnessPercent)
        else -> stringResource(R.string.light_state_on)
    }
}

/** The compact visual under the state line (e.g. a light's brightness bar), or nothing. */
@Composable
fun TileSummaryVisual(summary: TileSummary, modifier: Modifier = Modifier) {
    when (summary) {
        TileSummary.Default -> Unit
        is TileSummary.Light -> if (summary.isOn && summary.brightnessPercent != null) {
            LevelBar(fraction = summary.brightnessPercent / 100f, modifier = modifier)
        }
    }
}

private val LevelBarShape = RoundedCornerShape(2.dp)

/** A thin bar filled to [fraction] in the current content color. */
@Composable
fun LevelBar(fraction: Float, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(LevelBarShape)
            .background(color.copy(alpha = 0.25f)),
    ) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}
