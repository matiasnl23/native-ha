package com.matiasnl.hakiosk.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.ha.HaConnectionState
import com.matiasnl.hakiosk.ui.camera.CameraThumbnailContent
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onOpenEditor: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCamera: (entityId: String, label: String) -> Unit,
    cameraThumbnail: @Composable (entityId: String, modifier: Modifier) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val currentOnOpenCamera by rememberUpdatedState(onOpenCamera)

    LaunchedEffect(viewModel) {
        viewModel.openCameraEvents.collect { currentOnOpenCamera(it.entityId, it.label) }
    }

    LaunchedEffect(viewModel) {
        viewModel.errorEvents.collect { error ->
            snackbarHostState.showSnackbar(
                context.getString(R.string.dashboard_service_call_error, error.label, error.message),
            )
        }
    }

    DashboardContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onTileClick = viewModel::onTileClick,
        onOpenEditor = onOpenEditor,
        onOpenSettings = onOpenSettings,
        cameraThumbnail = cameraThumbnail,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardContent(
    uiState: DashboardUiState,
    snackbarHostState: SnackbarHostState,
    onTileClick: (DashboardTileUiState) -> Unit,
    onOpenEditor: () -> Unit,
    onOpenSettings: () -> Unit,
    cameraThumbnail: @Composable (entityId: String, modifier: Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dashboard_title)) },
                actions = {
                    TextButton(onClick = onOpenEditor) { Text(stringResource(R.string.dashboard_edit)) }
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.dashboard_settings)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            ConnectionBanner(
                state = uiState.connectionState,
                hasEntities = uiState.hasEntities,
                onOpenSettings = onOpenSettings,
            )

            if (uiState.tiles.isEmpty()) {
                EmptyDashboard(onOpenEditor = onOpenEditor, modifier = Modifier.fillMaxSize())
            } else {
                val isConnected = uiState.connectionState is HaConnectionState.Connected
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isConnected) 1f else 0.6f),
                ) {
                    items(uiState.tiles, key = { it.entityId }) { tile ->
                        if (tile.domain == "camera") {
                            CameraTileCard(tile = tile, onClick = { onTileClick(tile) }, thumbnail = cameraThumbnail)
                        } else {
                            DashboardTileCard(tile = tile, onClick = { onTileClick(tile) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionBanner(
    state: HaConnectionState,
    hasEntities: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Idle also happens right after stop() (e.g. the activity paused): if we already have entities
    // from a previous sync, that's not an error worth interrupting the kiosk view for.
    if (state == HaConnectionState.Idle && hasEntities) return

    val text = when (state) {
        HaConnectionState.Connected -> return
        HaConnectionState.Idle -> stringResource(R.string.connection_idle)
        HaConnectionState.Connecting -> stringResource(R.string.connection_connecting)
        is HaConnectionState.AuthFailed -> stringResource(R.string.connection_auth_failed, state.message)
        is HaConnectionState.Disconnected -> stringResource(R.string.connection_disconnected, state.message)
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (state is HaConnectionState.AuthFailed) {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.connection_auth_failed_action))
                }
            }
        }
    }
}

@Composable
private fun EmptyDashboard(onOpenEditor: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.dashboard_empty_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.dashboard_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onOpenEditor, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dashboard_empty_action))
        }
    }
}

@Composable
private fun DashboardTileCard(
    tile: DashboardTileUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimmed = tile.isMissing || tile.isUnavailable
    val containerColor = when {
        dimmed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        tile.isOn -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (dimmed) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    } else if (tile.isOn) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val stateText = when {
        tile.isMissing -> stringResource(R.string.dashboard_state_missing)
        tile.isUnavailable -> stringResource(R.string.dashboard_state_unavailable)
        tile.unitOfMeasurement != null -> "${tile.stateValue} ${tile.unitOfMeasurement}"
        else -> tile.stateValue.orEmpty()
    }

    Card(
        modifier = modifier
            .heightIn(min = 120.dp)
            .fillMaxWidth()
            .then(if (tile.isActionable) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = tile.label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = stateText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Camera tile: snapshot thumbnail with the name overlaid. Same sizing as the other tiles. Unavailable
 * or missing cameras show the placeholder only (no polling of a camera that can't answer).
 */
@Composable
private fun CameraTileCard(
    tile: DashboardTileUiState,
    onClick: () -> Unit,
    thumbnail: @Composable (entityId: String, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimmed = tile.isMissing || tile.isUnavailable
    Card(
        modifier = modifier
            .heightIn(min = 120.dp)
            .fillMaxWidth()
            .alpha(if (dimmed) 0.5f else 1f)
            .then(if (tile.isActionable) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)) {
            if (dimmed) {
                CameraThumbnailContent(image = null, modifier = Modifier.matchParentSize())
            } else {
                thumbnail(tile.entityId, Modifier.matchParentSize())
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = tile.label,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (dimmed) {
                    Text(
                        text = stringResource(
                            if (tile.isMissing) R.string.dashboard_state_missing else R.string.dashboard_state_unavailable,
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 700)
@Composable
private fun DashboardPreview() {
    HAKioskTheme {
        DashboardContent(
            uiState = DashboardUiState(
                tiles = listOf(
                    DashboardTileUiState(
                        entityId = "light.living_room",
                        label = "Living room",
                        domain = "light",
                        stateValue = "on",
                        unitOfMeasurement = null,
                        isOn = true,
                        isUnavailable = false,
                        isMissing = false,
                        isActionable = true,
                    ),
                    DashboardTileUiState(
                        entityId = "sensor.outdoor_temperature",
                        label = "Outdoor temperature",
                        domain = "sensor",
                        stateValue = "18.5",
                        unitOfMeasurement = "°C",
                        isOn = false,
                        isUnavailable = false,
                        isMissing = false,
                        isActionable = false,
                    ),
                    DashboardTileUiState(
                        entityId = "switch.coffee_maker",
                        label = "Coffee maker",
                        domain = "switch",
                        stateValue = "off",
                        unitOfMeasurement = null,
                        isOn = false,
                        isUnavailable = false,
                        isMissing = false,
                        isActionable = true,
                    ),
                    DashboardTileUiState(
                        entityId = "camera.front_door",
                        label = "Front door",
                        domain = "camera",
                        stateValue = "idle",
                        unitOfMeasurement = null,
                        isOn = false,
                        isUnavailable = false,
                        isMissing = false,
                        isActionable = true,
                    ),
                    DashboardTileUiState(
                        entityId = "light.gone",
                        label = "Removed bulb",
                        domain = "light",
                        stateValue = null,
                        unitOfMeasurement = null,
                        isOn = false,
                        isUnavailable = false,
                        isMissing = true,
                        isActionable = false,
                    ),
                ),
                connectionState = HaConnectionState.Connected,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onTileClick = {},
            onOpenEditor = {},
            onOpenSettings = {},
            cameraThumbnail = { _, modifier -> CameraThumbnailContent(image = null, modifier = modifier) },
        )
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 700)
@Composable
private fun DashboardEmptyPreview() {
    HAKioskTheme {
        DashboardContent(
            uiState = DashboardUiState(connectionState = HaConnectionState.Disconnected("timeout", 5_000)),
            snackbarHostState = remember { SnackbarHostState() },
            onTileClick = {},
            onOpenEditor = {},
            onOpenSettings = {},
            cameraThumbnail = { _, modifier -> CameraThumbnailContent(image = null, modifier = modifier) },
        )
    }
}
