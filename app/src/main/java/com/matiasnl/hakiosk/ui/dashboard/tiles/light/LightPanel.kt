package com.matiasnl.hakiosk.ui.dashboard.tiles.light

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.ha.HaEntity
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

/** The light details panel: a dialog with an on/off switch and only the controls the light supports. */
object LightTileDetails : TileDetails {
    @Composable
    override fun Panel(request: TileDetailsRequest, source: EntityControlSource, onDismiss: () -> Unit) {
        // Tied to the panel's composition: closing the panel stops collecting the entity.
        val scope = rememberCoroutineScope()
        val controller = remember(request.entityId, source) { LightPanelController(request.entityId, source, scope) }
        TilePanelDialog(title = request.label, onDismiss = onDismiss) {
            LightPanelContent(controller)
        }
    }
}

private val BrightnessRange = 0f..100f
private val HueRange = 0f..360f
private val SaturationRange = 0f..100f
private val GradientTrackShape = RoundedCornerShape(8.dp)

@Composable
fun LightPanelContent(controller: LightPanelController, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        LightPowerRow(controller)
        controller.error?.let { message ->
            Text(
                text = stringResource(R.string.tile_panel_error, message),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (controller.showBrightness) BrightnessControl(controller)
        if (controller.showColorTemp) ColorTempControl(controller)
        if (controller.showColor) ColorControls(controller)
    }
}

@Composable
private fun LightPowerRow(controller: LightPanelController) {
    val status = when {
        controller.isMissing -> stringResource(R.string.dashboard_state_missing)
        !controller.isAvailable -> stringResource(R.string.dashboard_state_unavailable)
        controller.isOn -> stringResource(R.string.light_state_on)
        else -> stringResource(R.string.light_state_off)
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = status, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = controller.isOn, onCheckedChange = controller::setOn, enabled = controller.isAvailable)
    }
}

@Composable
private fun BrightnessControl(controller: LightPanelController) {
    val value = controller.brightnessPercent
    LabeledSlider(
        title = stringResource(R.string.light_panel_brightness),
        valueText = stringResource(R.string.light_panel_percent, value.roundToInt()),
        value = value,
        onValueChange = controller::onBrightnessChange,
        onValueChangeFinished = controller::onBrightnessChangeFinished,
        valueRange = BrightnessRange,
        trackBrush = null,
    )
}

@Composable
private fun ColorTempControl(controller: LightPanelController) {
    val min = controller.minColorTempKelvin
    val max = controller.maxColorTempKelvin
    val range = remember(min, max) { min.toFloat()..max.toFloat() }
    // Warm (low Kelvin) on the left, cool on the right, like HA's own slider.
    val brush = remember(min, max) { Brush.horizontalGradient(listOf(kelvinToColor(min), kelvinToColor((min + max) / 2), kelvinToColor(max))) }
    val value = controller.colorTempKelvin
    LabeledSlider(
        title = stringResource(R.string.light_panel_color_temp),
        valueText = stringResource(R.string.light_panel_kelvin, value.roundToInt()),
        value = value,
        onValueChange = controller::onColorTempChange,
        onValueChangeFinished = controller::onColorTempChangeFinished,
        valueRange = range,
        trackBrush = brush,
    )
}

@Composable
private fun ColorControls(controller: LightPanelController) {
    val hue = controller.hueDegrees
    val saturation = controller.saturationPercent
    val rainbow = remember { Brush.horizontalGradient(RainbowColors) }
    // Rebuilt at most every 10° of hue, not on every move.
    val hueBucket = (hue / 10f).roundToInt()
    val saturationBrush = remember(hueBucket) {
        Brush.horizontalGradient(listOf(Color.White, Color.hsv((hueBucket * 10f) % 360f, 1f, 1f)))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.light_panel_color), style = MaterialTheme.typography.titleSmall)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.hsv(hue.coerceIn(0f, 360f), (saturation / 100f).coerceIn(0f, 1f), 1f))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        }
        LabeledSlider(
            title = stringResource(R.string.light_panel_hue),
            valueText = stringResource(R.string.light_panel_degrees, hue.roundToInt()),
            value = hue,
            onValueChange = controller::onHueChange,
            onValueChangeFinished = controller::onHueChangeFinished,
            valueRange = HueRange,
            trackBrush = rainbow,
        )
        LabeledSlider(
            title = stringResource(R.string.light_panel_saturation),
            valueText = stringResource(R.string.light_panel_percent, saturation.roundToInt()),
            value = saturation,
            onValueChange = controller::onSaturationChange,
            onValueChangeFinished = controller::onSaturationChangeFinished,
            valueRange = SaturationRange,
            trackBrush = saturationBrush,
        )
    }
}

// The Slider overload with a custom track is still marked experimental in Material3.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabeledSlider(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    trackBrush: Brush?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
        }
        if (trackBrush == null) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
            )
        } else {
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                track = { GradientTrack(trackBrush) },
            )
        }
    }
}

@Composable
private fun GradientTrack(brush: Brush) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .clip(GradientTrackShape)
            .background(brush),
    )
}

// --- Previews ---

private class PreviewControlSource(private val entity: HaEntity) : EntityControlSource {
    override fun entity(entityId: String): Flow<HaEntity?> = flowOf(entity)

    override suspend fun call(entityId: String, call: ServiceCall): Result<Unit> = Result.success(Unit)
}

private fun previewLight(state: String, attributes: String) = HaEntity(
    entityId = "light.preview",
    state = state,
    attributes = Json.parseToJsonElement(attributes) as JsonObject,
    lastChanged = "2026-09-14T00:00:00+00:00",
)

@Composable
private fun LightPanelPreviewFrame(title: String, entity: HaEntity) {
    val scope = rememberCoroutineScope()
    val controller = remember { LightPanelController(entity.entityId, PreviewControlSource(entity), scope, initialEntity = entity) }
    HAKioskTheme {
        TilePanelSurface(title = title, onDismiss = {}) { LightPanelContent(controller) }
    }
}

@Preview(showBackground = true, widthDp = 520, heightDp = 300)
@Composable
private fun LightPanelOnOffPreview() {
    LightPanelPreviewFrame("Lámpara", previewLight("on", """{"supported_color_modes":["onoff"],"color_mode":"onoff"}"""))
}

@Preview(showBackground = true, widthDp = 520, heightDp = 360)
@Composable
private fun LightPanelBrightnessPreview() {
    LightPanelPreviewFrame(
        "Pasillo",
        previewLight("on", """{"supported_color_modes":["brightness"],"color_mode":"brightness","brightness":153}"""),
    )
}

@Preview(showBackground = true, widthDp = 520, heightDp = 640)
@Composable
private fun LightPanelFullPreview() {
    LightPanelPreviewFrame(
        "Living",
        previewLight(
            "on",
            """{"supported_color_modes":["color_temp","hs"],"color_mode":"hs","brightness":200,
               "color_temp_kelvin":null,"min_color_temp_kelvin":2202,"max_color_temp_kelvin":6535,"hs_color":[30.0,80.0]}""",
        ),
    )
}

@Preview(showBackground = true, widthDp = 520, heightDp = 300)
@Composable
private fun LightPanelUnavailablePreview() {
    LightPanelPreviewFrame("Lámpara", previewLight("unavailable", """{"supported_color_modes":["brightness"]}"""))
}
