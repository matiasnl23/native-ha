package com.matiasnl.hakiosk.ui.dashboard.tiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.dashboard.CameraTileOptions
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

/** Whichever digits are in [raw], as the refresh interval typed into the field; empty means "use the app default". */
internal fun parseRefreshSecondsInput(raw: String): Int? = raw.filter(Char::isDigit).toIntOrNull()

/** Whether [seconds] is below [CameraTileOptions.MIN_THUMBNAIL_REFRESH_SECONDS] and needs an inline warning. */
internal fun isRefreshTooLow(seconds: Int?): Boolean =
    seconds != null && seconds < CameraTileOptions.MIN_THUMBNAIL_REFRESH_SECONDS

/** Whether [value] should show as "Otro…" (a manual entry): non-null and not one of [knownStreams]. */
internal fun isManualStream(value: String?, knownStreams: List<String>): Boolean =
    value != null && !knownStreams.contains(value)

/**
 * Per-tile camera options for the edit modal: the full-screen stream, and the thumbnail's own stream
 * (while live) or refresh interval. [entityId] loads [LocalCameraStreamCatalog] once per composition
 * (i.e. once per modal opening); [options] and [onOptionsChange] are the modal-local snapshot, same as
 * every other edit-modal section — nothing reaches the working copy until "Aplicar".
 */
@Composable
fun CameraOptionsSection(
    entityId: String,
    options: CameraTileOptions,
    onOptionsChange: (CameraTileOptions) -> Unit,
) {
    val catalog = LocalCameraStreamCatalog.current
    var streams by remember(entityId) { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember(entityId) { mutableStateOf(true) }
    var failed by remember(entityId) { mutableStateOf(false) }

    LaunchedEffect(entityId) {
        loading = true
        failed = false
        catalog(entityId).fold(
            onSuccess = { result ->
                streams = result
                failed = result.isEmpty()
            },
            onFailure = { failed = true },
        )
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.camera_options_title), style = MaterialTheme.typography.titleSmall)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.camera_option_focus_stream), style = MaterialTheme.typography.labelLarge)
            CameraStreamPicker(
                value = options.focusStream,
                knownStreams = streams,
                loading = loading,
                failed = failed,
                onValueChange = { onOptionsChange(options.copy(focusStream = it)) },
            )
            Text(
                text = stringResource(R.string.camera_option_focus_stream_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.camera_option_thumbnail_title), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.camera_option_live_switch))
                Switch(
                    checked = options.thumbnailLive,
                    onCheckedChange = { onOptionsChange(options.copy(thumbnailLive = it)) },
                )
            }
            Text(
                text = stringResource(R.string.camera_option_live_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (options.thumbnailLive) {
                CameraStreamPicker(
                    value = options.thumbnailStream,
                    knownStreams = streams,
                    loading = loading,
                    failed = failed,
                    onValueChange = { onOptionsChange(options.copy(thumbnailStream = it)) },
                )
            } else {
                RefreshSecondsField(
                    entityId = entityId,
                    seconds = options.thumbnailRefreshSeconds,
                    onSecondsChange = { onOptionsChange(options.copy(thumbnailRefreshSeconds = it)) },
                )
            }
        }

        Text(
            text = stringResource(R.string.camera_option_snapshot_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RefreshSecondsField(
    entityId: String,
    seconds: Int?,
    onSecondsChange: (Int?) -> Unit,
) {
    var text by rememberSaveable(entityId) { mutableStateOf(seconds?.toString().orEmpty()) }
    val parsed = parseRefreshSecondsInput(text)
    val tooLow = isRefreshTooLow(parsed)
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit)
            text = digits
            onSecondsChange(parseRefreshSecondsInput(digits))
        },
        label = { Text(stringResource(R.string.camera_option_refresh_seconds)) },
        placeholder = { Text(stringResource(R.string.camera_option_refresh_seconds_placeholder)) },
        singleLine = true,
        isError = tooLow,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = {
            Text(
                if (tooLow) {
                    stringResource(R.string.camera_option_refresh_seconds_too_low, CameraTileOptions.MIN_THUMBNAIL_REFRESH_SECONDS)
                } else {
                    stringResource(R.string.camera_option_refresh_seconds_hint)
                },
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Picks a go2rtc stream name: "Predeterminado de Home Assistant" (null), one of [knownStreams], or
 * "Otro…" to type one manually. A [value] outside [knownStreams] still shows selected, as manual.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraStreamPicker(
    value: String?,
    knownStreams: List<String>,
    loading: Boolean,
    failed: Boolean,
    onValueChange: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // null defers to whether `value` is a manual entry; set explicitly when the user picks from the menu.
    var manualOverride by remember { mutableStateOf<Boolean?>(null) }
    val manualMode = manualOverride ?: isManualStream(value, knownStreams)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = when {
                    manualMode -> stringResource(R.string.camera_stream_manual)
                    value == null -> stringResource(R.string.camera_stream_default)
                    else -> value
                },
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.camera_stream_default)) },
                    onClick = {
                        manualOverride = false
                        onValueChange(null)
                        expanded = false
                    },
                )
                knownStreams.forEach { stream ->
                    DropdownMenuItem(
                        text = { Text(stream) },
                        onClick = {
                            manualOverride = false
                            onValueChange(stream)
                            expanded = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.camera_stream_manual)) },
                    onClick = {
                        manualOverride = true
                        if (!isManualStream(value, knownStreams)) onValueChange("")
                        expanded = false
                    },
                )
            }
        }
        when {
            loading -> Text(stringResource(R.string.camera_stream_loading), style = MaterialTheme.typography.bodySmall)
            failed -> Text(
                text = stringResource(R.string.camera_stream_load_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (manualMode) {
            OutlinedTextField(
                value = value.orEmpty(),
                onValueChange = onValueChange,
                label = { Text(stringResource(R.string.camera_stream_manual_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 420)
@Composable
private fun CameraOptionsSectionLoadedPreview() {
    HAKioskTheme {
        CameraOptionsSectionPreviewHost(
            initial = CameraTileOptions(focusStream = "main"),
            streams = listOf("main", "sub"),
        )
    }
}

@Preview(showBackground = true, widthDp = 420)
@Composable
private fun CameraOptionsSectionManualPreview() {
    HAKioskTheme {
        CameraOptionsSectionPreviewHost(
            initial = CameraTileOptions(focusStream = "front_door_sub"),
            streams = emptyList(),
            failCatalog = true,
        )
    }
}

@Preview(showBackground = true, widthDp = 420)
@Composable
private fun CameraOptionsSectionLivePreview() {
    HAKioskTheme {
        CameraOptionsSectionPreviewHost(
            initial = CameraTileOptions(focusStream = "main", thumbnailLive = true, thumbnailStream = "sub"),
            streams = listOf("main", "sub"),
        )
    }
}

@Composable
private fun CameraOptionsSectionPreviewHost(
    initial: CameraTileOptions,
    streams: List<String>,
    failCatalog: Boolean = false,
) {
    var options by remember { mutableStateOf(initial) }
    val catalog: CameraStreamCatalog = {
        if (failCatalog) Result.failure(IllegalStateException("preview")) else Result.success(streams)
    }
    CompositionLocalProvider(LocalCameraStreamCatalog provides catalog) {
        CameraOptionsSection(entityId = "camera.front_door", options = options, onOptionsChange = { options = it })
    }
}
