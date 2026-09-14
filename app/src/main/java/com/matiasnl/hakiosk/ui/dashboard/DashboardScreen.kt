package com.matiasnl.hakiosk.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.dashboard.DashboardGrid as DashboardGridSettings
import com.matiasnl.hakiosk.data.ha.HaConnectionState
import com.matiasnl.hakiosk.ui.camera.CameraThumbnailContent
import com.matiasnl.hakiosk.ui.dashboard.grid.DashboardGrid
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPacker
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPlacement
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

/** Renders the camera snapshot of [entityId]; polls only while [active]. */
typealias CameraThumbnailSlot = @Composable (entityId: String, active: Boolean, modifier: Modifier) -> Unit

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onOpenEditor: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCamera: (entityId: String, label: String) -> Unit,
    cameraThumbnail: CameraThumbnailSlot,
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
    cameraThumbnail: CameraThumbnailSlot,
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
                DashboardGrid(
                    items = uiState.tiles,
                    itemKey = { it.id },
                    packing = uiState.packing,
                    visibleRows = uiState.grid.rows,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isConnected) 1f else 0.6f),
                ) { tile, placement, isVisible ->
                    when (tile) {
                        is DashboardTileUiState -> if (tile.domain == "camera") {
                            CameraTileCard(
                                tile = tile,
                                placement = placement,
                                isVisible = isVisible,
                                onClick = { onTileClick(tile) },
                                thumbnail = cameraThumbnail,
                            )
                        } else {
                            DashboardTileCard(tile = tile, placement = placement, onClick = { onTileClick(tile) })
                        }
                        is SpacerTileUiState -> Box(Modifier) // Nothing outside edit mode.
                        is ViewLinkTileUiState -> ViewLinkTileCard(tile = tile, placement = placement)
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

/** Tiles spanning at least 2×2 cells get bigger text. */
private fun GridPlacement.isLarge(): Boolean = colSpan >= 2 && rowSpan >= 2

@Composable
private fun labelStyle(placement: GridPlacement): TextStyle =
    if (placement.isLarge()) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium

@Composable
private fun stateStyle(placement: GridPlacement): TextStyle =
    if (placement.isLarge()) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium

@Composable
private fun DashboardTileCard(
    tile: DashboardTileUiState,
    placement: GridPlacement,
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
            .fillMaxSize()
            .then(if (tile.isActionable) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = tile.label,
                style = labelStyle(placement),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = stateText, style = stateStyle(placement), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Camera tile: snapshot thumbnail with the name overlaid. Unavailable or missing cameras show the
 * placeholder only (no polling of a camera that can't answer); offscreen ones ([isVisible] false)
 * keep their last image but stop polling.
 */
@Composable
private fun CameraTileCard(
    tile: DashboardTileUiState,
    placement: GridPlacement,
    isVisible: Boolean,
    onClick: () -> Unit,
    thumbnail: CameraThumbnailSlot,
    modifier: Modifier = Modifier,
) {
    val dimmed = tile.isMissing || tile.isUnavailable
    Card(
        modifier = modifier
            .fillMaxSize()
            .alpha(if (dimmed) 0.5f else 1f)
            .then(if (tile.isActionable) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (dimmed) {
                CameraThumbnailContent(image = null, modifier = Modifier.matchParentSize())
            } else {
                thumbnail(tile.entityId, isVisible, Modifier.matchParentSize())
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
                    style = labelStyle(placement),
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

/** Link to another view. Not interactive yet (view navigation arrives with multiple views). */
@Composable
private fun ViewLinkTileCard(
    tile: ViewLinkTileUiState,
    placement: GridPlacement,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = tile.label ?: stringResource(R.string.dashboard_view_link_fallback),
                style = labelStyle(placement),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = stringResource(R.string.dashboard_view_link_hint), style = stateStyle(placement))
        }
    }
}

private fun previewEntity(
    id: String,
    label: String,
    stateValue: String?,
    isOn: Boolean = false,
    unit: String? = null,
    isMissing: Boolean = false,
    colSpan: Int = 1,
    rowSpan: Int = 1,
) = DashboardTileUiState(
    id = id,
    entityId = id,
    label = label,
    domain = id.substringBefore('.'),
    stateValue = stateValue,
    unitOfMeasurement = unit,
    isOn = isOn,
    isUnavailable = false,
    isMissing = isMissing,
    isActionable = !isMissing && id.substringBefore('.') != "sensor",
    colSpan = colSpan,
    rowSpan = rowSpan,
)

@Preview(showBackground = true, widthDp = 900, heightDp = 600)
@Composable
private fun DashboardPreview() {
    val tiles = listOf(
        previewEntity("light.living_room", "Living room", "on", isOn = true, colSpan = 2, rowSpan = 2),
        previewEntity("sensor.outdoor_temperature", "Outdoor temperature", "18.5", unit = "°C"),
        previewEntity("switch.coffee_maker", "Coffee maker", "off"),
        SpacerTileUiState("spacer"),
        previewEntity("camera.front_door", "Front door", "idle", colSpan = 2),
        ViewLinkTileUiState("link", targetViewId = "v2", label = "Planta alta"),
        previewEntity("light.gone", "Removed bulb", null, isMissing = true),
    )
    val grid = DashboardGridSettings(columns = 4, rows = 3)
    HAKioskTheme {
        DashboardContent(
            uiState = DashboardUiState(
                viewId = "main",
                grid = grid,
                tiles = tiles,
                packing = GridPacker().pack(grid.columns, tiles, { it.colSpan }, { it.rowSpan }),
                connectionState = HaConnectionState.Connected,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onTileClick = {},
            onOpenEditor = {},
            onOpenSettings = {},
            cameraThumbnail = { _, _, modifier -> CameraThumbnailContent(image = null, modifier = modifier) },
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
            cameraThumbnail = { _, _, modifier -> CameraThumbnailContent(image = null, modifier = modifier) },
        )
    }
}
