package com.matiasnl.hakiosk.ui.dashboard.tiles.climate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.ha.domain.HvacMode
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileSummary

/** Below this inner height the status line is dropped so the buttons keep their size. */
private val ShortTileHeight = 100.dp

/**
 * The climate tile's "Ajuste rápido" style: name and current temperature on top, the status, and − / +
 * buttons around the setpoint (two pairs for a range on a wide tile). An off device that accepts
 * `turn_on` shows "Encender" instead. A range on a 1-column tile doesn't fit two pairs, so it falls back
 * to [ClimateTileContent].
 *
 * The buttons consume their own taps, so they never open the panel; the rest of the tile still does.
 * [setpoint] and [onTurnOn] are null in edit mode, where the buttons show but are disabled.
 */
@Composable
fun ClimateQuickAdjustContent(
    label: String,
    summary: TileSummary.Climate,
    wide: Boolean,
    labelStyle: TextStyle,
    setpoint: ClimateSetpointState?,
    onTurnOn: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val mode = summary.hvacMode ?: return
    val isRange = summary.targetTemperatureLow != null && summary.targetTemperatureHigh != null
    if (isRange && !wide) {
        ClimateTileContent(label = label, summary = summary, wide = false, labelStyle = labelStyle, modifier = modifier)
        return
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize().padding(12.dp)) {
        val showStatus = maxHeight >= ShortTileHeight
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = label, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    summary.currentTemperature?.let {
                        Text(text = formatTemperature(it), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (showStatus) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            imageVector = climateIcon(mode, summary.hvacAction),
                            contentDescription = null,
                            tint = summary.tint() ?: LocalContentColor.current,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = climateStatusText(mode, summary.hvacAction),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            when {
                mode == HvacMode.OFF -> if (summary.canTurnOn) TurnOnButton(onTurnOn)
                isRange -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SetpointStepper(
                        caption = stringResource(R.string.climate_panel_target_low),
                        value = setpoint?.targetLow ?: summary.targetTemperatureLow,
                        state = setpoint,
                        end = SetpointEnd.LOW,
                    )
                    SetpointStepper(
                        caption = stringResource(R.string.climate_panel_target_high),
                        value = setpoint?.targetHigh ?: summary.targetTemperatureHigh,
                        state = setpoint,
                        end = SetpointEnd.HIGH,
                    )
                }
                summary.targetTemperature != null -> SetpointStepper(
                    caption = null,
                    value = setpoint?.targetTemperature ?: summary.targetTemperature,
                    state = setpoint,
                    end = SetpointEnd.TARGET,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SetpointStepper(
    caption: String?,
    value: Double?,
    state: ClimateSetpointState?,
    end: SetpointEnd,
    modifier: Modifier = Modifier,
) {
    val subject = caption ?: stringResource(R.string.climate_panel_target)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (caption == null) Arrangement.SpaceBetween else Arrangement.spacedBy(6.dp),
    ) {
        caption?.let { Text(text = it, style = MaterialTheme.typography.labelMedium) }
        StepButton(
            symbol = "−",
            description = stringResource(R.string.climate_panel_decrease, subject),
            enabled = state?.canStep(end, -1) == true,
            onClick = { state?.step(end, -1) },
        )
        Text(
            text = value?.let(::formatTemperature) ?: "—",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 56.dp),
        )
        StepButton(
            symbol = "+",
            description = stringResource(R.string.climate_panel_increase, subject),
            enabled = state?.canStep(end, 1) == true,
            onClick = { state?.step(end, 1) },
        )
    }
}

@Composable
private fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp).semantics { contentDescription = description },
    ) {
        Text(text = symbol, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun TurnOnButton(onTurnOn: (() -> Unit)?) {
    FilledTonalButton(
        onClick = { onTurnOn?.invoke() },
        enabled = onTurnOn != null,
        contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
        modifier = Modifier.height(40.dp),
    ) {
        Icon(imageVector = ClimateIcons.Off, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.climate_tile_turn_on))
    }
}
