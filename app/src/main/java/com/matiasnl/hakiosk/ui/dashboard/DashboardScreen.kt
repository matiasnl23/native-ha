package com.matiasnl.hakiosk.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.matiasnl.hakiosk.ui.dashboard.edit.EditTileModal
import com.matiasnl.hakiosk.ui.dashboard.edit.PreviewTile
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
    onOpenViewSettings: (viewId: String) -> Unit,
    onOpenCamera: (entityId: String, label: String) -> Unit,
    cameraThumbnail: CameraThumbnailSlot,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val currentOnOpenCamera by rememberUpdatedState(onOpenCamera)
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var editingTileId by remember { mutableStateOf<String?>(null) }

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

    // Back while editing = Cancelar, with a confirmation dialog if the working copy is dirty.
    val requestCancelEdit: () -> Unit = {
        if (uiState.isDirty) showDiscardConfirm = true else viewModel.cancelEditMode()
    }
    BackHandler(enabled = uiState.isEditing, onBack = requestCancelEdit)

    DashboardContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onTileClick = viewModel::onTileClick,
        onOpenEditor = onOpenEditor,
        onOpenSettings = onOpenSettings,
        onOpenViewSettings = onOpenViewSettings,
        onEnterEdit = viewModel::enterEditMode,
        onRequestCancelEdit = requestCancelEdit,
        onDoneEdit = viewModel::doneEditMode,
        onEditTile = { tileId -> editingTileId = tileId },
        // Opening the add-tile/grid-settings modals is wired in as each one is built.
        onAddTile = {},
        onOpenGridSettings = {},
        cameraThumbnail = cameraThumbnail,
    )

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.dashboard_edit_discard_title)) },
            text = { Text(stringResource(R.string.dashboard_edit_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirm = false
                        viewModel.cancelEditMode()
                    },
                ) {
                    Text(stringResource(R.string.dashboard_edit_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.dashboard_edit_discard_keep_editing))
                }
            },
        )
    }

    editingTileId?.let { tileId ->
        EditTileModalHost(
            uiState = uiState,
            tileId = tileId,
            onApply = { label, colSpan, rowSpan ->
                viewModel.setEditTileLabel(tileId, label)
                viewModel.resizeEditTile(tileId, colSpan, rowSpan)
                editingTileId = null
            },
            onRemove = {
                viewModel.removeEditTile(tileId)
                editingTileId = null
            },
            onDismiss = { editingTileId = null },
        )
    }
}

/** Resolves the per-tile-type title/label metadata and renders [EditTileModal], or nothing if the tile is gone. */
@Composable
private fun EditTileModalHost(
    uiState: DashboardUiState,
    tileId: String,
    onApply: (label: String?, colSpan: Int, rowSpan: Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val editableTiles = uiState.tiles.filterNot { it is AddTileUiState }
    val index = editableTiles.indexOfFirst { it.id == tileId }
    val tile = editableTiles.getOrNull(index) ?: return
    val previewTiles = editableTiles.map { t ->
        PreviewTile(colSpan = t.colSpan, rowSpan = t.rowSpan, isSpacer = t is SpacerTileUiState)
    }
    val viewLinkFallback = stringResource(R.string.dashboard_view_link_fallback)
    val (title, showLabelField, initialLabel, labelPlaceholder) = when (tile) {
        is SpacerTileUiState -> EditModalMeta(stringResource(R.string.dashboard_spacer_label), false, "", "")
        is ViewLinkTileUiState -> {
            val targetName = tile.targetViewName ?: viewLinkFallback
            EditModalMeta(stringResource(R.string.edit_tile_link_title, targetName), true, tile.rawLabel.orEmpty(), targetName)
        }
        is DashboardTileUiState -> EditModalMeta(tile.label, true, tile.rawLabel.orEmpty(), tile.defaultLabel)
        is AddTileUiState -> return
    }

    EditTileModal(
        title = title,
        showLabelField = showLabelField,
        initialLabel = initialLabel,
        labelPlaceholder = labelPlaceholder,
        grid = uiState.grid,
        tiles = previewTiles,
        editingIndex = index,
        onApply = onApply,
        onRemove = onRemove,
        onDismiss = onDismiss,
    )
}

private data class EditModalMeta(
    val title: String,
    val showLabelField: Boolean,
    val initialLabel: String,
    val labelPlaceholder: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardContent(
    uiState: DashboardUiState,
    snackbarHostState: SnackbarHostState,
    onTileClick: (DashboardTileUiState) -> Unit,
    onOpenEditor: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenViewSettings: (viewId: String) -> Unit,
    onEnterEdit: () -> Unit,
    onRequestCancelEdit: () -> Unit,
    onDoneEdit: () -> Unit,
    onEditTile: (tileId: String) -> Unit,
    onAddTile: () -> Unit,
    onOpenGridSettings: () -> Unit,
    cameraThumbnail: CameraThumbnailSlot,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (uiState.isEditing) R.string.dashboard_edit_mode_title else R.string.dashboard_title,
                        ),
                    )
                },
                actions = {
                    if (uiState.isEditing) {
                        TextButton(onClick = onRequestCancelEdit) { Text(stringResource(R.string.dashboard_edit_cancel)) }
                        TextButton(onClick = onOpenGridSettings) { Text(stringResource(R.string.dashboard_edit_grid)) }
                        TextButton(onClick = onDoneEdit) { Text(stringResource(R.string.dashboard_edit_done)) }
                    } else {
                        uiState.viewId?.let { viewId ->
                            TextButton(onClick = { onOpenViewSettings(viewId) }) {
                                Text(stringResource(R.string.dashboard_view_settings))
                            }
                        }
                        TextButton(onClick = onEnterEdit) { Text(stringResource(R.string.dashboard_edit_mode)) }
                        TextButton(onClick = onOpenEditor) { Text(stringResource(R.string.dashboard_edit)) }
                        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.dashboard_settings)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (!uiState.isEditing) {
                ConnectionBanner(
                    state = uiState.connectionState,
                    hasEntities = uiState.hasEntities,
                    onOpenSettings = onOpenSettings,
                )
            }

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
                        .alpha(if (isConnected || uiState.isEditing) 1f else 0.6f),
                ) { tile, placement, isVisible ->
                    if (uiState.isEditing) {
                        EditModeTileCell(
                            tile = tile,
                            placement = placement,
                            isVisible = isVisible,
                            onEditTile = onEditTile,
                            onAddTile = onAddTile,
                            cameraThumbnail = cameraThumbnail,
                        )
                    } else {
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
                            is AddTileUiState -> Box(Modifier) // Never appears outside edit mode.
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

/** Tiles spanning at least 2×2 cells get bigger text. */
private fun GridPlacement.isLarge(): Boolean = colSpan >= 2 && rowSpan >= 2

@Composable
private fun labelStyle(placement: GridPlacement): TextStyle =
    if (placement.isLarge()) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium

@Composable
private fun stateStyle(placement: GridPlacement): TextStyle =
    if (placement.isLarge()) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium

@Composable
private fun tileContainerColor(dimmed: Boolean, isOn: Boolean): Color = when {
    dimmed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    isOn -> MaterialTheme.colorScheme.primaryContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun tileContentColor(dimmed: Boolean, isOn: Boolean): Color = when {
    dimmed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    isOn -> MaterialTheme.colorScheme.onPrimaryContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun DashboardTileCard(
    tile: DashboardTileUiState,
    placement: GridPlacement,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimmed = tile.isMissing || tile.isUnavailable
    Card(
        modifier = modifier
            .fillMaxSize()
            .then(if (tile.isActionable) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = tileContainerColor(dimmed, tile.isOn),
            contentColor = tileContentColor(dimmed, tile.isOn),
        ),
    ) {
        DashboardTileContent(tile, placement)
    }
}

@Composable
private fun DashboardTileContent(tile: DashboardTileUiState, placement: GridPlacement) {
    val stateText = when {
        tile.isMissing -> stringResource(R.string.dashboard_state_missing)
        tile.isUnavailable -> stringResource(R.string.dashboard_state_unavailable)
        tile.unitOfMeasurement != null -> "${tile.stateValue} ${tile.unitOfMeasurement}"
        else -> tile.stateValue.orEmpty()
    }
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
        CameraTileContent(tile, placement, isVisible, thumbnail)
    }
}

@Composable
private fun CameraTileContent(
    tile: DashboardTileUiState,
    placement: GridPlacement,
    isVisible: Boolean,
    thumbnail: CameraThumbnailSlot,
) {
    val dimmed = tile.isMissing || tile.isUnavailable
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

/** Link to another view. Not interactive yet outside edit mode (view navigation arrives with multiple views). */
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
        ViewLinkTileContent(tile, placement)
    }
}

@Composable
private fun ViewLinkTileContent(tile: ViewLinkTileUiState, placement: GridPlacement) {
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

// --- Edit mode: same tile visuals as above, plus an edit affordance, a highlighted outline and,
// appended after the real tiles, the "＋" tile that opens the add-tile modal. ---

@Composable
private fun EditModeTileCell(
    tile: DashboardTileUi,
    placement: GridPlacement,
    isVisible: Boolean,
    onEditTile: (tileId: String) -> Unit,
    onAddTile: () -> Unit,
    cameraThumbnail: CameraThumbnailSlot,
) {
    when (tile) {
        is AddTileUiState -> AddTileCard(onClick = onAddTile)
        is SpacerTileUiState -> EditableTileShell(onClick = { onEditTile(tile.id) }) {
            SpacerEditContent()
        }
        is ViewLinkTileUiState -> EditableTileShell(
            onClick = { onEditTile(tile.id) },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            ViewLinkTileContent(tile, placement)
        }
        is DashboardTileUiState -> {
            val dimmed = tile.isMissing || tile.isUnavailable
            if (tile.domain == "camera") {
                EditableTileShell(onClick = { onEditTile(tile.id) }) {
                    CameraTileContent(tile, placement, isVisible, cameraThumbnail)
                }
            } else {
                EditableTileShell(
                    onClick = { onEditTile(tile.id) },
                    containerColor = tileContainerColor(dimmed, tile.isOn),
                    contentColor = tileContentColor(dimmed, tile.isOn),
                ) {
                    DashboardTileContent(tile, placement)
                }
            }
        }
    }
}

/** Wraps [content] with a highlighted outline and a corner edit button; the whole tile is also tappable. */
@Composable
private fun EditableTileShell(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxSize().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            EditPencilBadge(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
    }
}

@Composable
private fun EditPencilBadge(modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.size(28.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text("✎", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Empty in normal use; while editing it shows a dashed outline and label so it isn't mistaken for a gap. */
@Composable
private fun SpacerEditContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.dashboard_spacer_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** Trailing 1×1 tile shown only while editing; opens the add-tile modal, never persisted. */
@Composable
private fun AddTileCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.dashboard_add_tile)
    Card(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary,
        ),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "+", style = MaterialTheme.typography.displaySmall)
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
            onOpenViewSettings = {},
            onEnterEdit = {},
            onRequestCancelEdit = {},
            onDoneEdit = {},
            onEditTile = {},
            onAddTile = {},
            onOpenGridSettings = {},
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
            onOpenViewSettings = {},
            onEnterEdit = {},
            onRequestCancelEdit = {},
            onDoneEdit = {},
            onEditTile = {},
            onAddTile = {},
            onOpenGridSettings = {},
            cameraThumbnail = { _, _, modifier -> CameraThumbnailContent(image = null, modifier = modifier) },
        )
    }
}

@Preview(showBackground = true, widthDp = 900, heightDp = 600)
@Composable
private fun DashboardEditModePreview() {
    val tiles = listOf(
        previewEntity("light.living_room", "Living room", "on", isOn = true, colSpan = 2, rowSpan = 2),
        previewEntity("sensor.outdoor_temperature", "Outdoor temperature", "18.5", unit = "°C"),
        SpacerTileUiState("spacer"),
        previewEntity("camera.front_door", "Front door", "idle", colSpan = 2),
        ViewLinkTileUiState("link", targetViewId = "v2", label = "Planta alta"),
        AddTileUiState(),
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
                isEditing = true,
                isDirty = true,
                linkTargets = listOf(com.matiasnl.hakiosk.ui.dashboard.edit.LinkTargetOption("v2", "Planta alta")),
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onTileClick = {},
            onOpenEditor = {},
            onOpenSettings = {},
            onOpenViewSettings = {},
            onEnterEdit = {},
            onRequestCancelEdit = {},
            onDoneEdit = {},
            onEditTile = {},
            onAddTile = {},
            onOpenGridSettings = {},
            cameraThumbnail = { _, _, modifier -> CameraThumbnailContent(image = null, modifier = modifier) },
        )
    }
}
