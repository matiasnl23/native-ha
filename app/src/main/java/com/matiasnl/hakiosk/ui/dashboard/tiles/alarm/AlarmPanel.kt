package com.matiasnl.hakiosk.ui.dashboard.tiles.alarm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.domain.AlarmArmMode
import com.matiasnl.hakiosk.data.ha.domain.AlarmCodeFormat
import com.matiasnl.hakiosk.data.ha.domain.ServiceCall
import com.matiasnl.hakiosk.ui.dashboard.tiles.EntityControlSource
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileDetails
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileDetailsRequest
import com.matiasnl.hakiosk.ui.dashboard.tiles.TilePanelDialog
import com.matiasnl.hakiosk.ui.dashboard.tiles.TilePanelSurface
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The alarm panel: arm buttons (disarmed) or "Desarmar", with a code entry only when one is needed. */
object AlarmTileDetails : TileDetails {
    @Composable
    override fun Panel(request: TileDetailsRequest, source: EntityControlSource, onDismiss: () -> Unit) {
        val scope = rememberCoroutineScope()
        val controller = remember(request.entityId, source) { AlarmPanelController(request.entityId, source, scope) }
        val latestOnDismiss by rememberUpdatedState(onDismiss)
        // Close as soon as a call succeeds.
        LaunchedEffect(controller) {
            snapshotFlow { controller.isFinished }.first { it }
            latestOnDismiss()
        }
        // Whatever closes the panel (Cerrar, back, outside tap, edit mode, inactivity) forgets the code.
        DisposableEffect(controller) {
            onDispose { controller.close() }
        }
        TilePanelDialog(title = request.label, onDismiss = onDismiss) {
            AlarmPanelContent(controller)
        }
    }
}

@Composable
fun AlarmPanelContent(controller: AlarmPanelController, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AlarmStateBanner(controller)
        controller.error?.let { message ->
            Text(
                text = stringResource(R.string.tile_panel_error, message),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when (controller.codeInput) {
            AlarmCodeFormat.NUMBER -> NumericCodeEntry(controller)
            AlarmCodeFormat.TEXT -> TextCodeEntry(controller)
            AlarmCodeFormat.NONE -> Unit
        }
        AlarmActions(controller)
    }
}

@Composable
private fun AlarmStateBanner(controller: AlarmPanelController) {
    val state = controller.state
    val colors = alarmColors(state.tone)
    val text = if (controller.isMissing) stringResource(R.string.dashboard_state_missing) else alarmStateText(state)
    Surface(color = colors.container, contentColor = colors.content, shape = MaterialTheme.shapes.medium) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 16.dp),
        )
    }
}

@Composable
private fun AlarmActions(controller: AlarmPanelController) {
    val enabled = !controller.isBusy
    val modes = controller.armModes
    val rows = remember(modes) { modes.chunked(2) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rows.isNotEmpty()) {
            Text(stringResource(R.string.alarm_panel_arm), style = MaterialTheme.typography.titleSmall)
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { mode ->
                        Button(
                            onClick = { controller.arm(mode) },
                            enabled = enabled,
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) {
                            Text(stringResource(mode.labelRes()))
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (controller.canDisarm) {
            Button(
                onClick = controller::disarm,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(stringResource(R.string.alarm_panel_disarm))
            }
        }
        if (rows.isEmpty() && !controller.canDisarm) {
            Text(
                text = stringResource(R.string.alarm_panel_no_actions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (controller.isBusy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(stringResource(R.string.alarm_panel_sending), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun AlarmArmMode.labelRes(): Int = when (this) {
    AlarmArmMode.HOME -> R.string.alarm_mode_home
    AlarmArmMode.AWAY -> R.string.alarm_mode_away
    AlarmArmMode.NIGHT -> R.string.alarm_mode_night
    AlarmArmMode.VACATION -> R.string.alarm_mode_vacation
    AlarmArmMode.CUSTOM_BYPASS -> R.string.alarm_mode_custom_bypass
}

private val KeypadDigitRows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'))

@Composable
private fun NumericCodeEntry(controller: AlarmPanelController) {
    val enabled = !controller.isBusy
    val length = controller.code.length
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val maskedDescription = stringResource(R.string.alarm_panel_code_entered, length)
        Box(
            modifier = Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = maskedDescription },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (length == 0) stringResource(R.string.alarm_panel_code_placeholder) else "●".repeat(length),
                style = if (length == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.headlineSmall,
                color = if (length == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        KeypadDigitRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit -> KeypadDigit(digit, enabled, controller::onDigit, Modifier.weight(1f)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val clearDescription = stringResource(R.string.alarm_panel_keypad_clear)
            OutlinedButton(
                onClick = controller::onClearCode,
                enabled = enabled && length > 0,
                modifier = Modifier.weight(1f).height(56.dp).semantics { contentDescription = clearDescription },
            ) {
                Text("C", style = MaterialTheme.typography.titleLarge)
            }
            KeypadDigit('0', enabled, controller::onDigit, Modifier.weight(1f))
            val deleteDescription = stringResource(R.string.alarm_panel_keypad_delete)
            OutlinedButton(
                onClick = controller::onDeleteDigit,
                enabled = enabled && length > 0,
                modifier = Modifier.weight(1f).height(56.dp).semantics { contentDescription = deleteDescription },
            ) {
                Text("⌫", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun KeypadDigit(digit: Char, enabled: Boolean, onDigit: (Char) -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = { onDigit(digit) }, enabled = enabled, modifier = modifier.height(56.dp)) {
        Text(digit.toString(), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun TextCodeEntry(controller: AlarmPanelController) {
    // Plain state from the controller, deliberately not rememberSaveable: the code is never saved.
    OutlinedTextField(
        value = controller.code,
        onValueChange = controller::onCodeChange,
        enabled = !controller.isBusy,
        label = { Text(stringResource(R.string.alarm_panel_code)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

// --- Previews ---

private class PreviewAlarmSource(private val entity: HaEntity) : EntityControlSource {
    override fun entity(entityId: String): Flow<HaEntity?> = flowOf(entity)

    override suspend fun call(entityId: String, call: ServiceCall): Result<Unit> = Result.success(Unit)
}

@Composable
private fun AlarmPanelPreviewFrame(state: String, attributes: String, typedCode: String = "") {
    val entity = HaEntity(
        entityId = "alarm_control_panel.home",
        state = state,
        attributes = Json.parseToJsonElement(attributes) as JsonObject,
        lastChanged = "2026-09-14T00:00:00+00:00",
    )
    val scope = rememberCoroutineScope()
    val controller = remember {
        AlarmPanelController(entity.entityId, PreviewAlarmSource(entity), scope, initialEntity = entity).apply {
            typedCode.forEach(::onDigit)
        }
    }
    HAKioskTheme {
        TilePanelSurface(title = "Alarma", onDismiss = {}) { AlarmPanelContent(controller) }
    }
}

@Preview(showBackground = true, widthDp = 520, heightDp = 760)
@Composable
private fun AlarmPanelNumberKeypadPreview() {
    AlarmPanelPreviewFrame("disarmed", """{"supported_features":55,"code_format":"number"}""", typedCode = "123")
}

@Preview(showBackground = true, widthDp = 520, heightDp = 420)
@Composable
private fun AlarmPanelTextCodePreview() {
    AlarmPanelPreviewFrame("armed_away", """{"supported_features":3,"code_format":"text"}""")
}

@Preview(showBackground = true, widthDp = 520, heightDp = 360)
@Composable
private fun AlarmPanelNoCodePreview() {
    AlarmPanelPreviewFrame("disarmed", """{"supported_features":3}""")
}

@Preview(showBackground = true, widthDp = 520, heightDp = 360)
@Composable
private fun AlarmPanelTriggeredPreview() {
    AlarmPanelPreviewFrame("triggered", """{"supported_features":3,"code_format":"number","code_arm_required":false}""")
}
