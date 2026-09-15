package com.matiasnl.hakiosk.ui.dashboard.tiles.climate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.domain.HvacMode
import com.matiasnl.hakiosk.data.ha.domain.ServiceCall
import com.matiasnl.hakiosk.ui.dashboard.tiles.EntityControlSource
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileDetails
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileDetailsRequest
import com.matiasnl.hakiosk.ui.dashboard.tiles.TilePanelDialog
import com.matiasnl.hakiosk.ui.dashboard.tiles.TilePanelSurface
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/** The climate panel: current reading, setpoint stepper(s), and chips for every mode the device supports. */
object ClimateTileDetails : TileDetails {
    @Composable
    override fun Panel(request: TileDetailsRequest, source: EntityControlSource, onDismiss: () -> Unit) {
        val scope = rememberCoroutineScope()
        val controller = remember(request.entityId, source) { ClimatePanelController(request.entityId, source, scope) }
        // Whatever closes the panel (Cerrar, back, outside tap, inactivity) still sends a pending setpoint.
        DisposableEffect(controller) {
            onDispose { controller.close() }
        }
        TilePanelDialog(title = request.label, onDismiss = onDismiss) {
            ClimatePanelContent(controller)
        }
    }
}

@Composable
fun ClimatePanelContent(controller: ClimatePanelController, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ClimateReading(controller)
        controller.error?.let { message ->
            Text(
                text = stringResource(R.string.tile_panel_error, message),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (controller.showTargetTemperature) {
            SetpointStepper(
                title = stringResource(R.string.climate_panel_target),
                value = controller.targetTemperature,
                canDecrease = controller.canStepTargetTemperature(-1),
                canIncrease = controller.canStepTargetTemperature(1),
                onStep = controller::stepTargetTemperature,
            )
        }
        if (controller.showTargetRange) {
            SetpointStepper(
                title = stringResource(R.string.climate_panel_target_low),
                value = controller.targetTemperatureLow,
                canDecrease = controller.canStepTargetLow(-1),
                canIncrease = controller.canStepTargetLow(1),
                onStep = controller::stepTargetLow,
            )
            SetpointStepper(
                title = stringResource(R.string.climate_panel_target_high),
                value = controller.targetTemperatureHigh,
                canDecrease = controller.canStepTargetHigh(-1),
                canIncrease = controller.canStepTargetHigh(1),
                onStep = controller::stepTargetHigh,
            )
        }
        val hvacModes = controller.hvacModes
        if (hvacModes.isNotEmpty()) {
            ChoiceChips(
                title = stringResource(R.string.climate_panel_mode),
                options = hvacModes,
                selected = controller.hvacMode,
                label = { stringResource(it.labelRes()) },
                onSelect = controller::setHvacMode,
            )
        }
        if (controller.presetModes.isNotEmpty()) {
            ChoiceChips(
                title = stringResource(R.string.climate_panel_preset),
                options = controller.presetModes,
                selected = controller.presetMode,
                label = { presetLabel(it) },
                onSelect = controller::setPresetMode,
            )
        }
        if (controller.fanModes.isNotEmpty()) {
            ChoiceChips(
                title = stringResource(R.string.climate_panel_fan),
                options = controller.fanModes,
                selected = controller.fanMode,
                label = { fanModeLabel(it) },
                onSelect = controller::setFanMode,
            )
        }
        if (controller.swingModes.isNotEmpty()) {
            ChoiceChips(
                title = stringResource(R.string.climate_panel_swing),
                options = controller.swingModes,
                selected = controller.swingMode,
                label = { swingModeLabel(it) },
                onSelect = controller::setSwingMode,
            )
        }
        if (controller.swingHorizontalModes.isNotEmpty()) {
            ChoiceChips(
                title = stringResource(R.string.climate_panel_swing_horizontal),
                options = controller.swingHorizontalModes,
                selected = controller.swingHorizontalMode,
                label = { swingModeLabel(it) },
                onSelect = controller::setSwingHorizontalMode,
            )
        }
        if (controller.showTargetHumidity) HumidityControl(controller)
    }
}

@Composable
private fun ClimateReading(controller: ClimatePanelController) {
    val mode = controller.hvacMode
    val status = when {
        controller.isMissing -> stringResource(R.string.dashboard_state_missing)
        !controller.isAvailable -> stringResource(R.string.dashboard_state_unavailable)
        mode == null -> stringResource(R.string.climate_state_unknown)
        else -> climateStatusText(mode, controller.hvacAction)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        controller.currentTemperature?.let { temperature ->
            Text(text = formatTemperature(temperature), style = MaterialTheme.typography.displayMedium)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = status, style = MaterialTheme.typography.titleMedium)
            controller.currentHumidity?.let { humidity ->
                Text(
                    text = stringResource(R.string.climate_panel_current_humidity, humidity.roundToInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SetpointStepper(
    title: String,
    value: Double?,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onStep: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val decreaseDescription = stringResource(R.string.climate_panel_decrease, title)
            FilledTonalButton(
                onClick = { onStep(-1) },
                enabled = canDecrease,
                modifier = Modifier.width(88.dp).height(56.dp).semantics { contentDescription = decreaseDescription },
            ) {
                Text("−", style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                text = value?.let(::formatTemperature) ?: "—",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            val increaseDescription = stringResource(R.string.climate_panel_increase, title)
            FilledTonalButton(
                onClick = { onStep(1) },
                enabled = canIncrease,
                modifier = Modifier.width(88.dp).height(56.dp).semantics { contentDescription = increaseDescription },
            ) {
                Text("+", style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(
    title: String,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun HumidityControl(controller: ClimatePanelController) {
    val value = controller.targetHumidity
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.climate_panel_humidity), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.light_panel_percent, value.roundToInt()), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value,
            onValueChange = controller::onHumidityChange,
            onValueChangeFinished = controller::onHumidityChangeFinished,
            valueRange = controller.humidityRange,
        )
    }
}

// --- Previews ---

private class PreviewClimateSource(private val entity: HaEntity) : EntityControlSource {
    override fun entity(entityId: String): Flow<HaEntity?> = flowOf(entity)

    override suspend fun call(entityId: String, call: ServiceCall): Result<Unit> = Result.success(Unit)
}

@Composable
private fun ClimatePanelPreviewFrame(title: String, state: String, attributes: String) {
    val entity = HaEntity(
        entityId = "climate.preview",
        state = state,
        attributes = Json.parseToJsonElement(attributes) as JsonObject,
        lastChanged = "2026-09-14T00:00:00+00:00",
    )
    val scope = rememberCoroutineScope()
    val controller = remember { ClimatePanelController(entity.entityId, PreviewClimateSource(entity), scope, initialEntity = entity) }
    HAKioskTheme {
        TilePanelSurface(title = title, onDismiss = {}) { ClimatePanelContent(controller) }
    }
}

@Preview(showBackground = true, widthDp = 520, heightDp = 820)
@Composable
private fun ClimatePanelSplitPreview() {
    ClimatePanelPreviewFrame(
        "Aire del living",
        "cool",
        """{"hvac_modes":["off","cool","heat","dry","fan_only","auto"],"min_temp":16,"max_temp":30,"target_temp_step":1,
           "current_temperature":24.5,"temperature":22,"hvac_action":"cooling","current_humidity":58,
           "fan_modes":["auto","low","medium","high"],"fan_mode":"auto","swing_modes":["off","vertical"],"swing_mode":"off",
           "preset_modes":["none","eco","boost","sleep"],"preset_mode":"none","supported_features":441}""",
    )
}

@Preview(showBackground = true, widthDp = 520, heightDp = 620)
@Composable
private fun ClimatePanelRangePreview() {
    ClimatePanelPreviewFrame(
        "Termostato",
        HvacMode.HEAT_COOL.value,
        """{"hvac_modes":["off","heat","cool","heat_cool"],"min_temp":7,"max_temp":35,"current_temperature":21.3,
           "target_temp_low":20,"target_temp_high":24.5,"temperature":null,"hvac_action":"idle",
           "humidity":45,"min_humidity":30,"max_humidity":70,"supported_features":6}""",
    )
}

@Preview(showBackground = true, widthDp = 520, heightDp = 360)
@Composable
private fun ClimatePanelUnavailablePreview() {
    ClimatePanelPreviewFrame("Aire del living", "unavailable", """{"hvac_modes":["off","cool"],"supported_features":1}""")
}
