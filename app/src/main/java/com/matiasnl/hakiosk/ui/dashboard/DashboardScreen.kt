package com.matiasnl.hakiosk.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import com.matiasnl.hakiosk.ui.dashboard.edit.AddTileModal
import com.matiasnl.hakiosk.ui.dashboard.edit.EditTileModal
import com.matiasnl.hakiosk.ui.dashboard.edit.GridSettingsModal
import com.matiasnl.hakiosk.ui.dashboard.edit.PreviewTile
import com.matiasnl.hakiosk.ui.dashboard.edit.ViewListItem
import com.matiasnl.hakiosk.ui.dashboard.edit.ViewsModal
import com.matiasnl.hakiosk.ui.dashboard.grid.DashboardGrid
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPacker
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPlacement
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileDetailsHost
import com.matiasnl.hakiosk.ui.dashboard.tiles.DomainTileBehaviors
import com.matiasnl.hakiosk.data.dashboard.TileTapAction
import androidx.compose.runtime.saveable.rememberSaveable
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileSummary
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileSummaryBackground
import com.matiasnl.hakiosk.ui.dashboard.tiles.entityTileGestures
import com.matiasnl.hakiosk.ui.dashboard.tiles.climate.ClimateTileContent
import com.matiasnl.hakiosk.ui.dashboard.tiles.climate.ClimateQuickAdjustContent
import com.matiasnl.hakiosk.ui.dashboard.tiles.climate.ClimateSetpoint
import com.matiasnl.hakiosk.ui.dashboard.tiles.climate.ClimateSetpointState
import com.matiasnl.hakiosk.ui.dashboard.tiles.climate.rememberClimateSetpointState
import com.matiasnl.hakiosk.data.dashboard.TileStyle
import com.matiasnl.hakiosk.data.dashboard.CameraTileOptions
import com.matiasnl.hakiosk.data.ha.domain.HvacAction
import com.matiasnl.hakiosk.data.ha.domain.HvacMode
import com.matiasnl.hakiosk.ui.dashboard.tiles.light.rememberBrightnessSwipeState
import com.matiasnl.hakiosk.ui.dashboard.tiles.summaryTileColors
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.matiasnl.hakiosk.data.ha.domain.AlarmPanelState
import androidx.compose.material3.CardColors
import com.matiasnl.hakiosk.ui.dashboard.tiles.summaryStateText
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

/** Renders the picture of camera [entityId] per the tile's [options] (null: defaults); works only while [active]. */
typealias CameraThumbnailSlot = @Composable (entityId: String, options: CameraTileOptions?, active: Boolean, modifier: Modifier) -> Unit

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onOpenSettings: () -> Unit,
    /** [stream] is the tile's go2rtc stream for full screen, or null for Home Assistant's own stream. */
    onOpenCamera: (entityId: String, label: String, stream: String?) -> Unit,
    cameraThumbnail: CameraThumbnailSlot,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val currentOnOpenCamera by rememberUpdatedState(onOpenCamera)
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var editingTileId by remember { mutableStateOf<String?>(null) }
    var showAddTile by remember { mutableStateOf(false) }
    var showGridSettings by remember { mutableStateOf(false) }
    var showViews by remember { mutableStateOf(false) }
    val addTilePickerState by viewModel.addTilePicker.uiState.collectAsStateWithLifecycle()
    val detailsRequest by viewModel.detailsRequest.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.openCameraEvents.collect { currentOnOpenCamera(it.entityId, it.label, it.stream) }
    }

    LaunchedEffect(viewModel) {
        viewModel.errorEvents.collect { error ->
            snackbarHostState.showSnackbar(
                context.getString(R.string.dashboard_service_call_error, error.label, error.message),
            )
        }
    }

    // Defensive: if edit mode ends (Listo/Cancelar) any modal state left open closes with it.
    LaunchedEffect(uiState.isEditing) {
        if (!uiState.isEditing) {
            editingTileId = null
            showAddTile = false
            showGridSettings = false
            showViews = false
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
        onTileLongPress = viewModel::onTileLongPress,
        onTileBrightnessChange = viewModel::onTileBrightnessChange,
        onTileClimateSetpoint = viewModel::onTileClimateSetpoint,
        onTileClimateTurnOn = viewModel::onTileClimateTurnOn,
        onPageSettled = viewModel::onPageSettled,
        onViewLinkClick = viewModel::onViewLinkClick,
        onSelectEditingView = viewModel::selectEditingView,
        onDragActiveChange = viewModel::setDragActive,
        onOpenSettings = onOpenSettings,
        onEnterEdit = viewModel::enterEditMode,
        onRequestCancelEdit = requestCancelEdit,
        onDoneEdit = viewModel::doneEditMode,
        onMoveTile = viewModel::moveEditTile,
        onEditTile = { tileId -> editingTileId = tileId },
        onAddTile = {
            viewModel.addTilePicker.reset()
            showAddTile = true
        },
        onOpenGridSettings = { showGridSettings = true },
        onOpenViews = { showViews = true },
        onUserActivity = viewModel::onUserActivity,
        cameraThumbnail = cameraThumbnail,
    )

    detailsRequest?.let { request ->
        TileDetailsHost(
            request = request,
            source = viewModel.entityControls,
            onUserActivity = viewModel::onUserActivity,
            onDismiss = viewModel::dismissDetails,
        )
    }

    if (showViews) {
        ViewsModal(
            views = uiState.pages.map { ViewListItem(it.viewId, it.name) },
            editingViewId = uiState.viewId,
            onAddView = viewModel::addView,
            onRenameView = viewModel::renameView,
            onRemoveView = viewModel::removeView,
            onMoveView = viewModel::moveView,
            onSelectView = viewModel::selectEditingView,
            inactivityReturnMinutes = uiState.inactivityReturnMinutes,
            onInactivityReturnChange = viewModel::setInactivityReturnMinutes,
            onDismiss = { showViews = false },
        )
    }

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
            onApply = { label, colSpan, rowSpan, tapAction, style ->
                viewModel.setEditTileLabel(tileId, label)
                viewModel.resizeEditTile(tileId, colSpan, rowSpan)
                tapAction?.let { viewModel.setEditTileTapAction(tileId, it) }
                style?.let { viewModel.setEditTileStyle(tileId, it) }
                editingTileId = null
            },
            onRemove = {
                viewModel.removeEditTile(tileId)
                editingTileId = null
            },
            onDismiss = { editingTileId = null },
        )
    }

    if (showAddTile) {
        AddTileModal(
            pickerState = addTilePickerState,
            onQueryChange = viewModel.addTilePicker::onQueryChange,
            onDomainFilterChange = viewModel.addTilePicker::onDomainFilterChange,
            onFloorFilterChange = viewModel.addTilePicker::onFloorFilterChange,
            onAreaFilterChange = viewModel.addTilePicker::onAreaFilterChange,
            onPickEntity = viewModel::addEntityTile,
            onPickSpacer = viewModel::addSpacerTile,
            linkTargets = uiState.linkTargets,
            onPickLink = viewModel::addLinkTile,
            onDismiss = { showAddTile = false },
        )
    }

    if (showGridSettings) {
        GridSettingsModal(
            grid = uiState.grid,
            previewTiles = uiState.editablePreviewTiles(),
            onApply = { grid ->
                viewModel.setEditGrid(grid)
                showGridSettings = false
            },
            onDismiss = { showGridSettings = false },
        )
    }
}

/** The working copy's real tiles (never the "＋" placeholder) as [PreviewTile]s, in order. */
private fun DashboardUiState.editablePreviewTiles(): List<PreviewTile> =
    tiles.filterNot { it is AddTileUiState }.map { tile ->
        PreviewTile(colSpan = tile.colSpan, rowSpan = tile.rowSpan, isSpacer = tile is SpacerTileUiState)
    }

/** Resolves the per-tile-type title/label metadata and renders [EditTileModal], or nothing if the tile is gone. */
@Composable
private fun EditTileModalHost(
    uiState: DashboardUiState,
    tileId: String,
    /** [tapAction] and [style] are null when the tile's domain offers no such choice (nothing to write). */
    onApply: (label: String?, colSpan: Int, rowSpan: Int, tapAction: TileTapAction?, style: TileStyle?) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val editableTiles = uiState.tiles.filterNot { it is AddTileUiState }
    val index = editableTiles.indexOfFirst { it.id == tileId }
    val tile = editableTiles.getOrNull(index) ?: return
    val entityTile = tile as? DashboardTileUiState
    // Modal-local like the label and size: it only reaches the working copy through Aplicar.
    var tapAction by rememberSaveable(tileId) { mutableStateOf(entityTile?.tapAction ?: TileTapAction.DEFAULT) }
    var style by rememberSaveable(tileId) { mutableStateOf(entityTile?.style ?: TileStyle.DEFAULT) }
    val behavior = entityTile?.let { DomainTileBehaviors.forDomain(it.domain) }
    val domainSections = behavior
        ?.editSections(tapAction, { tapAction = it }, style, { style = it })
        .orEmpty()
    val previewTiles = uiState.editablePreviewTiles()
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
        onApply = { label, colSpan, rowSpan ->
            onApply(
                label,
                colSpan,
                rowSpan,
                tapAction.takeIf { behavior?.offersTapActionChoice == true },
                style.takeIf { behavior?.offersStyleChoice == true },
            )
        },
        onRemove = onRemove,
        onDismiss = onDismiss,
        domainSections = domainSections,
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
    onTileLongPress: (DashboardTileUiState) -> Unit,
    onTileBrightnessChange: (DashboardTileUiState, percent: Int) -> Unit,
    onTileClimateSetpoint: (DashboardTileUiState, ClimateSetpoint) -> Unit,
    onTileClimateTurnOn: (DashboardTileUiState) -> Unit,
    onPageSettled: (viewId: String) -> Unit,
    onViewLinkClick: (ViewLinkTileUiState) -> Unit,
    onSelectEditingView: (viewId: String) -> Unit,
    onDragActiveChange: (active: Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onEnterEdit: () -> Unit,
    onRequestCancelEdit: () -> Unit,
    onDoneEdit: () -> Unit,
    onMoveTile: (fromIndex: Int, toIndex: Int) -> Unit,
    onEditTile: (tileId: String) -> Unit,
    onAddTile: () -> Unit,
    onOpenGridSettings: () -> Unit,
    onOpenViews: () -> Unit,
    onUserActivity: () -> Unit,
    cameraThumbnail: CameraThumbnailSlot,
) {
    // The pager is only built once the layout and the last opened view are known, so it starts on
    // the right page instead of flashing the first one.
    val pagerState = if (uiState.isLoaded && uiState.pages.isNotEmpty()) {
        val latestPages by rememberUpdatedState(uiState.pages)
        rememberPagerState(initialPage = uiState.currentPage) { latestPages.size }.also { state ->
            PagerSync(state, uiState.currentPage, pages = { latestPages }, onPageSettled = onPageSettled)
        }
    } else {
        null
    }

    val latestOnUserActivity by rememberUpdatedState(onUserActivity)
    Scaffold(
        // Any touch anywhere on the dashboard restarts the inactivity countdown. Observed in the
        // Initial pass and never consumed, so taps, swipes and drags behave exactly as before.
        modifier = Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    latestOnUserActivity()
                }
            }
        },
        topBar = {
            TopAppBar(
                title = { DashboardTitle(uiState, pagerState) },
                actions = {
                    if (uiState.isEditing) {
                        TextButton(onClick = onRequestCancelEdit) { Text(stringResource(R.string.dashboard_edit_cancel)) }
                        TextButton(onClick = onOpenViews) { Text(stringResource(R.string.dashboard_edit_views)) }
                        TextButton(onClick = onOpenGridSettings) { Text(stringResource(R.string.dashboard_edit_grid)) }
                        TextButton(onClick = onDoneEdit) { Text(stringResource(R.string.dashboard_edit_done)) }
                    } else {
                        TextButton(onClick = onEnterEdit) { Text(stringResource(R.string.dashboard_edit_mode)) }
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
            if (uiState.isEditing && uiState.pages.size > 1) {
                EditViewChips(
                    pages = uiState.pages,
                    editedPage = uiState.currentPage,
                    onSelect = onSelectEditingView,
                )
            }
            if (pagerState == null) return@Column

            val isConnected = uiState.connectionState is HaConnectionState.Connected
            // One scroll position per view, kept while its page is out of composition.
            val scrollStates = remember { HashMap<String, ScrollState>() }
            HorizontalPager(
                state = pagerState,
                // Swiping is off for the whole edit session: a horizontal move would otherwise be
                // grabbed by the pager before the grid's long press lifts a tile.
                userScrollEnabled = !uiState.isEditing,
                key = { index -> uiState.pages.getOrNull(index)?.viewId ?: index },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .alpha(if (isConnected || uiState.isEditing) 1f else 0.6f),
            ) { index ->
                val page = uiState.pages.getOrNull(index) ?: return@HorizontalPager
                // Read inside cells only, so a settle recomposes camera cells rather than the page.
                val isSettled = remember(pagerState, index) { derivedStateOf { pagerState.settledPage == index } }
                DashboardPage(
                    page = page,
                    isEditing = uiState.isEditing,
                    isEditedPage = uiState.isEditing && index == uiState.currentPage,
                    isSettled = isSettled,
                    scrollState = scrollStates.getOrPut(page.viewId) { ScrollState(0) },
                    onTileClick = onTileClick,
                    onTileLongPress = onTileLongPress,
                    onTileBrightnessChange = onTileBrightnessChange,
                    onTileClimateSetpoint = onTileClimateSetpoint,
                    onTileClimateTurnOn = onTileClimateTurnOn,
                    onViewLinkClick = onViewLinkClick,
                    onEnterEdit = onEnterEdit,
                    onMoveTile = onMoveTile,
                    onDragActiveChange = onDragActiveChange,
                    onEditTile = onEditTile,
                    onAddTile = onAddTile,
                    cameraThumbnail = cameraThumbnail,
                )
            }
            if (uiState.pages.size > 1 && !uiState.isEditing) {
                PageIndicator(pagerState = pagerState, pageCount = uiState.pages.size)
            }
        }
    }
}

/**
 * Keeps the pager and the ViewModel's current page in step: scrolls (animated) to [currentPage]
 * whenever it changes, and reports every settled page back through [onPageSettled]. A user swipe
 * reports its settle, which makes the ViewModel's current page match, so the two never fight.
 */
@Composable
private fun PagerSync(
    pagerState: PagerState,
    currentPage: Int,
    pages: () -> List<DashboardPageUi>,
    onPageSettled: (viewId: String) -> Unit,
) {
    val latestOnPageSettled by rememberUpdatedState(onPageSettled)
    LaunchedEffect(pagerState, currentPage) {
        if (currentPage !in 0 until pagerState.pageCount) return@LaunchedEffect
        if (pagerState.settledPage != currentPage || pagerState.targetPage != currentPage) {
            pagerState.animateScrollToPage(currentPage)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            pages().getOrNull(page)?.let { latestOnPageSettled(it.viewId) }
        }
    }
}

/** "Editando…" while editing; the current view's name with 2+ views; the app name otherwise. */
@Composable
private fun DashboardTitle(uiState: DashboardUiState, pagerState: PagerState?) {
    val text = when {
        uiState.isEditing -> stringResource(R.string.dashboard_edit_mode_title)
        pagerState != null && uiState.pages.size > 1 ->
            uiState.pages.getOrNull(pagerState.currentPage)?.name ?: stringResource(R.string.dashboard_title)
        else -> stringResource(R.string.dashboard_title)
    }
    Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** One view: its grid, or the empty-dashboard hint when it has no tiles (outside edit mode). */
@Composable
private fun DashboardPage(
    page: DashboardPageUi,
    isEditing: Boolean,
    isEditedPage: Boolean,
    isSettled: State<Boolean>,
    scrollState: ScrollState,
    onTileClick: (DashboardTileUiState) -> Unit,
    onTileLongPress: (DashboardTileUiState) -> Unit,
    onTileBrightnessChange: (DashboardTileUiState, percent: Int) -> Unit,
    onTileClimateSetpoint: (DashboardTileUiState, ClimateSetpoint) -> Unit,
    onTileClimateTurnOn: (DashboardTileUiState) -> Unit,
    onViewLinkClick: (ViewLinkTileUiState) -> Unit,
    onEnterEdit: () -> Unit,
    onMoveTile: (fromIndex: Int, toIndex: Int) -> Unit,
    onDragActiveChange: (active: Boolean) -> Unit,
    onEditTile: (tileId: String) -> Unit,
    onAddTile: () -> Unit,
    cameraThumbnail: CameraThumbnailSlot,
) {
    if (page.tiles.isEmpty()) {
        // While editing only the edited page has content (its "＋"); a neighbour shown mid-scroll stays blank.
        if (!isEditing) EmptyDashboard(onAddTiles = onEnterEdit, modifier = Modifier.fillMaxSize())
        return
    }
    DashboardGrid(
        items = page.tiles,
        itemKey = { it.id },
        packing = page.packing,
        visibleRows = page.grid.rows,
        modifier = Modifier.fillMaxSize(),
        scrollState = scrollState,
        // Real tiles (spacers and view links included) are draggable; the trailing "＋" isn't.
        draggableCount = if (isEditedPage) page.tiles.count { it !is AddTileUiState } else 0,
        onMove = if (isEditedPage) onMoveTile else null,
        onDragActiveChange = if (isEditedPage) onDragActiveChange else null,
    ) { tile, placement, isVisible ->
        // Cameras poll only when on screen AND on the settled page: never on a neighbour during or after a swipe.
        val isActive = isVisible && isSettled.value
        if (isEditing) {
            EditModeTileCell(
                tile = tile,
                placement = placement,
                isVisible = isActive,
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
                        isVisible = isActive,
                        onClick = { onTileClick(tile) },
                        thumbnail = cameraThumbnail,
                    )
                } else {
                    DashboardTileCard(
                        tile = tile,
                        placement = placement,
                        onClick = { onTileClick(tile) },
                        onLongClick = { onTileLongPress(tile) },
                        onBrightnessChange = { percent -> onTileBrightnessChange(tile, percent) },
                        onClimateSetpoint = { setpoint -> onTileClimateSetpoint(tile, setpoint) },
                        onClimateTurnOn = { onTileClimateTurnOn(tile) },
                    )
                }
                is SpacerTileUiState -> Box(Modifier) // Nothing outside edit mode.
                is ViewLinkTileUiState -> ViewLinkTileCard(
                    tile = tile,
                    placement = placement,
                    onClick = { onViewLinkClick(tile) },
                )
                is AddTileUiState -> Box(Modifier) // Never appears outside edit mode.
            }
        }
    }
}

/**
 * Edit mode's way to change views (swiping is off while editing): one chip per view of the working
 * copy, the edited one selected. A tap asks the ViewModel to edit that view, which it refuses mid-drag.
 */
@Composable
private fun EditViewChips(
    pages: List<DashboardPageUi>,
    editedPage: Int,
    onSelect: (viewId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        pages.forEachIndexed { index, page ->
            FilterChip(
                selected = index == editedPage,
                onClick = { if (index != editedPage) onSelect(page.viewId) },
                label = { Text(page.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** Dots under the pager, the current one highlighted. Only shown with 2+ views. */
@Composable
private fun PageIndicator(pagerState: PagerState, pageCount: Int, modifier: Modifier = Modifier) {
    val current = pagerState.currentPage
    val description = stringResource(R.string.dashboard_page_indicator, current + 1, pageCount)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    ),
            )
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

/** Shown outside edit mode when the view has no tiles; its action enters edit mode, where "＋" adds tiles. */
@Composable
private fun EmptyDashboard(onAddTiles: () -> Unit, modifier: Modifier = Modifier) {
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
        Button(onClick = onAddTiles, modifier = Modifier.fillMaxWidth()) {
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

/**
 * Entity tile outside edit mode. A tap runs [onClick] when actionable; a long press runs [onLongClick]
 * when the tile has a details panel. Drag-to-reorder only exists in edit mode (on the grid, not here),
 * so the long press never competes with it; the pager and vertical scroll consume a moving pointer
 * before the long-press timeout, which cancels the press as with any clickable.
 */
@Composable
private fun DashboardTileCard(
    tile: DashboardTileUiState,
    placement: GridPlacement,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onBrightnessChange: (percent: Int) -> Unit,
    onClimateSetpoint: (ClimateSetpoint) -> Unit,
    onClimateTurnOn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimmed = tile.isMissing || tile.isUnavailable
    val light = (tile.summary as? TileSummary.Light)?.takeIf { it.supportsBrightness && !dimmed }
    val brightnessSwipe = light?.let {
        rememberBrightnessSwipeState(
            confirmedPercent = if (it.isOn) it.brightnessPercent?.toFloat() ?: 100f else 0f,
            onCommit = onBrightnessChange,
        )
    }
    // While swiped, the tile shows the swiped brightness (text, fill and on/off colors) until HA confirms it.
    val heldPercent = brightnessSwipe?.heldPercent
    val summary = if (light != null && heldPercent != null) {
        light.copy(isOn = heldPercent > 0, brightnessPercent = heldPercent.takeIf { it > 0 })
    } else {
        tile.summary
    }
    val quickClimate = (tile.summary as? TileSummary.Climate)?.takeIf { tile.style == TileStyle.QUICK_ADJUST && !dimmed }
    val climateSetpoint = quickClimate?.let { rememberClimateSetpointState(it, onClimateSetpoint) }
    Card(
        modifier = modifier
            .fillMaxSize()
            .entityTileGestures(
                isActionable = tile.isActionable,
                hasDetails = tile.hasDetails,
                onClick = onClick,
                onLongClick = onLongClick,
                brightnessSwipe = brightnessSwipe,
                reverseSwipeDirection = LocalLayoutDirection.current == LayoutDirection.Rtl,
            ),
        colors = entityTileColors(tile, summary, dimmed),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!dimmed) TileSummaryBackground(summary, Modifier.matchParentSize())
            DashboardTileContent(tile, summary, placement, climateSetpoint, onClimateTurnOn.takeIf { quickClimate != null })
        }
    }
}

/** A summary's own colors (e.g. an alarm's state color) when the tile is live, else the on/off colors. */
@Composable
private fun entityTileColors(tile: DashboardTileUiState, summary: TileSummary, dimmed: Boolean): CardColors {
    val summaryColors = if (dimmed) null else summaryTileColors(summary)
    return CardDefaults.cardColors(
        containerColor = summaryColors?.container ?: tileContainerColor(dimmed, tile.isOn),
        contentColor = summaryColors?.content ?: tileContentColor(dimmed, tile.isOn),
    )
}

/**
 * [climateSetpoint] and [onClimateTurnOn] drive a quick-adjust climate tile's buttons; null in edit mode,
 * where that style still shows but its buttons are disabled.
 */
@Composable
private fun DashboardTileContent(
    tile: DashboardTileUiState,
    summary: TileSummary,
    placement: GridPlacement,
    climateSetpoint: ClimateSetpointState? = null,
    onClimateTurnOn: (() -> Unit)? = null,
) {
    if (summary is TileSummary.Climate && summary.hvacMode != null && !tile.isMissing && !tile.isUnavailable) {
        val wide = placement.colSpan >= 2
        if (tile.style == TileStyle.QUICK_ADJUST) {
            ClimateQuickAdjustContent(
                label = tile.label,
                summary = summary,
                wide = wide,
                labelStyle = labelStyle(placement),
                setpoint = climateSetpoint,
                onTurnOn = onClimateTurnOn,
            )
        } else {
            ClimateTileContent(label = tile.label, summary = summary, wide = wide, labelStyle = labelStyle(placement))
        }
        return
    }
    val stateText = when {
        tile.isMissing -> stringResource(R.string.dashboard_state_missing)
        tile.isUnavailable -> stringResource(R.string.dashboard_state_unavailable)
        else -> summaryStateText(summary) ?: when {
            tile.unitOfMeasurement != null -> "${tile.stateValue} ${tile.unitOfMeasurement}"
            else -> tile.stateValue.orEmpty()
        }
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
            thumbnail(tile.entityId, tile.camera, isVisible, Modifier.matchParentSize())
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

/** Link to another view: a tap goes to its page. A link whose target view is gone looks disabled and ignores taps. */
@Composable
private fun ViewLinkTileCard(
    tile: ViewLinkTileUiState,
    placement: GridPlacement,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxSize()
            .alpha(if (tile.hasTarget) 1f else 0.5f)
            .then(if (tile.hasTarget) Modifier.clickable(onClick = onClick) else Modifier),
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
        Text(
            text = stringResource(
                if (tile.hasTarget) R.string.dashboard_view_link_hint else R.string.dashboard_view_link_missing,
            ),
            style = stateStyle(placement),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
                val colors = entityTileColors(tile, tile.summary, dimmed)
                EditableTileShell(
                    onClick = { onEditTile(tile.id) },
                    containerColor = colors.containerColor,
                    contentColor = colors.contentColor,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Static in edit mode: a light's brightness fill, but never the alarm's animated pulse.
                        if (!dimmed && tile.summary is TileSummary.Light) {
                            TileSummaryBackground(tile.summary, Modifier.matchParentSize())
                        }
                        DashboardTileContent(tile, tile.summary, placement)
                    }
                }
            }
        }
    }
}

/**
 * Wraps [content] with a highlighted outline and a corner edit button; the whole tile is also
 * tappable. Long-press-and-drag to reorder is handled by the grid around it.
 */
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
    summary: TileSummary = TileSummary.Default,
) = DashboardTileUiState(
    summary = summary,
    hasDetails = summary != TileSummary.Default,
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

private fun previewPage(viewId: String, name: String, grid: DashboardGridSettings, tiles: List<DashboardTileUi>) =
    DashboardPageUi(
        viewId = viewId,
        name = name,
        grid = grid,
        tiles = tiles,
        packing = GridPacker().pack(grid.columns, tiles, { it.colSpan }, { it.rowSpan }),
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
                isLoaded = true,
                pages = listOf(previewPage("main", "Principal", grid, tiles)),
                connectionState = HaConnectionState.Connected,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onTileClick = {},
            onTileLongPress = {},
            onTileBrightnessChange = { _, _ -> },
            onTileClimateSetpoint = { _, _ -> },
            onTileClimateTurnOn = {},
            onPageSettled = {},
            onViewLinkClick = {},
            onOpenSettings = {},
            onEnterEdit = {},
            onRequestCancelEdit = {},
            onDoneEdit = {},
            onMoveTile = { _, _ -> },
            onSelectEditingView = {},
            onDragActiveChange = {},
            onEditTile = {},
            onAddTile = {},
            onOpenGridSettings = {},
            onOpenViews = {},
            onUserActivity = {},
            cameraThumbnail = { _, _, _, modifier -> CameraThumbnailContent(image = null, modifier = modifier) },
        )
    }
}

@Preview(showBackground = true, widthDp = 900, heightDp = 600)
@Composable
private fun DashboardSmartTilesPreview() {
    val tiles = listOf(
        previewEntity("light.living", "Living", "on", isOn = true, colSpan = 2, rowSpan = 2, summary = TileSummary.Light(true, 60, Color.hsv(210f, 0.8f, 1f))),
        previewEntity("light.hall", "Pasillo", "on", isOn = true, summary = TileSummary.Light(true, 15)),
        previewEntity("light.desk", "Escritorio", "on", isOn = true, summary = TileSummary.Light(true, 80, Color.hsv(30f, 0.6f, 1f))),
        previewEntity("light.lamp", "Lámpara", "on", isOn = true, summary = TileSummary.Light(true, null)),
        previewEntity("light.bedroom", "Dormitorio", "off", summary = TileSummary.Light(false, null)),
        previewEntity("switch.coffee_maker", "Cafetera", "off"),
        previewEntity(
            "climate.living", "Aire del living", "cool", colSpan = 2,
            summary = TileSummary.Climate(HvacMode.COOL, HvacAction.COOLING, currentTemperature = 24.5, targetTemperature = 22.0),
        ),
        previewEntity(
            "climate.bedroom", "Dormitorio", "heat",
            summary = TileSummary.Climate(HvacMode.HEAT, HvacAction.IDLE, currentTemperature = 22.5, targetTemperature = 21.0),
        ),
        previewEntity("climate.office", "Escritorio", "off", summary = TileSummary.Climate(HvacMode.OFF, currentTemperature = 19.0)),
        previewEntity("alarm_control_panel.home", "Alarma", "disarmed", summary = TileSummary.Alarm(AlarmPanelState.DISARMED)),
        previewEntity("alarm_control_panel.garage", "Garage", "armed_away", summary = TileSummary.Alarm(AlarmPanelState.ARMED_AWAY)),
        previewEntity("alarm_control_panel.shed", "Galpón", "arming", summary = TileSummary.Alarm(AlarmPanelState.ARMING)),
        previewEntity("alarm_control_panel.office", "Oficina", "triggered", summary = TileSummary.Alarm(AlarmPanelState.TRIGGERED)),
    )
    val grid = DashboardGridSettings(columns = 4, rows = 3)
    HAKioskTheme {
        DashboardContent(
            uiState = DashboardUiState(
                isLoaded = true,
                pages = listOf(previewPage("main", "Principal", grid, tiles)),
                connectionState = HaConnectionState.Connected,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onTileClick = {},
            onTileLongPress = {},
            onTileBrightnessChange = { _, _ -> },
            onTileClimateSetpoint = { _, _ -> },
            onTileClimateTurnOn = {},
            onPageSettled = {},
            onViewLinkClick = {},
            onOpenSettings = {},
            onEnterEdit = {},
            onRequestCancelEdit = {},
            onDoneEdit = {},
            onMoveTile = { _, _ -> },
            onSelectEditingView = {},
            onDragActiveChange = {},
            onEditTile = {},
            onAddTile = {},
            onOpenGridSettings = {},
            onOpenViews = {},
            onUserActivity = {},
            cameraThumbnail = { _, _, _, modifier -> CameraThumbnailContent(image = null, modifier = modifier) },
        )
    }
}

@Preview(showBackground = true, widthDp = 900, heightDp = 600)
@Composable
private fun DashboardMultiViewPreview() {
    val grid = DashboardGridSettings(columns = 4, rows = 3)
    val pages = listOf(
        previewPage("main", "Principal", grid, listOf(previewEntity("light.living_room", "Living room", "on", isOn = true))),
        previewPage(
            "upstairs",
            "Planta alta",
            grid,
            listOf(
                previewEntity("light.bedroom", "Dormitorio", "off", colSpan = 2),
                previewEntity("sensor.upstairs_temperature", "Temperatura", "21", unit = "°C"),
                ViewLinkTileUiState("back", targetViewId = "main", label = "Principal", targetViewName = "Principal"),
                ViewLinkTileUiState("stale", targetViewId = "gone", label = null),
            ),
        ),
        previewPage("garden", "Jardín", grid, emptyList()),
    )
    HAKioskTheme {
        DashboardContent(
            uiState = DashboardUiState(
                isLoaded = true,
                pages = pages,
                currentPage = 1,
                connectionState = HaConnectionState.Connected,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onTileClick = {},
            onTileLongPress = {},
            onTileBrightnessChange = { _, _ -> },
            onTileClimateSetpoint = { _, _ -> },
            onTileClimateTurnOn = {},
            onPageSettled = {},
            onViewLinkClick = {},
            onOpenSettings = {},
            onEnterEdit = {},
            onRequestCancelEdit = {},
            onDoneEdit = {},
            onMoveTile = { _, _ -> },
            onSelectEditingView = {},
            onDragActiveChange = {},
            onEditTile = {},
            onAddTile = {},
            onOpenGridSettings = {},
            onOpenViews = {},
            onUserActivity = {},
            cameraThumbnail = { _, _, _, modifier -> CameraThumbnailContent(image = null, modifier = modifier) },
        )
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 700)
@Composable
private fun DashboardEmptyPreview() {
    HAKioskTheme {
        DashboardContent(
            uiState = DashboardUiState(
                isLoaded = true,
                pages = listOf(DashboardPageUi(viewId = "main", name = "Principal")),
                connectionState = HaConnectionState.Disconnected("timeout", 5_000),
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onTileClick = {},
            onTileLongPress = {},
            onTileBrightnessChange = { _, _ -> },
            onTileClimateSetpoint = { _, _ -> },
            onTileClimateTurnOn = {},
            onPageSettled = {},
            onViewLinkClick = {},
            onOpenSettings = {},
            onEnterEdit = {},
            onRequestCancelEdit = {},
            onDoneEdit = {},
            onMoveTile = { _, _ -> },
            onSelectEditingView = {},
            onDragActiveChange = {},
            onEditTile = {},
            onAddTile = {},
            onOpenGridSettings = {},
            onOpenViews = {},
            onUserActivity = {},
            cameraThumbnail = { _, _, _, modifier -> CameraThumbnailContent(image = null, modifier = modifier) },
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
                isLoaded = true,
                pages = listOf(previewPage("main", "Principal", grid, tiles)),
                connectionState = HaConnectionState.Connected,
                isEditing = true,
                isDirty = true,
                linkTargets = listOf(com.matiasnl.hakiosk.ui.dashboard.edit.LinkTargetOption("v2", "Planta alta")),
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onTileClick = {},
            onTileLongPress = {},
            onTileBrightnessChange = { _, _ -> },
            onTileClimateSetpoint = { _, _ -> },
            onTileClimateTurnOn = {},
            onPageSettled = {},
            onViewLinkClick = {},
            onOpenSettings = {},
            onEnterEdit = {},
            onRequestCancelEdit = {},
            onDoneEdit = {},
            onMoveTile = { _, _ -> },
            onSelectEditingView = {},
            onDragActiveChange = {},
            onEditTile = {},
            onAddTile = {},
            onOpenGridSettings = {},
            onOpenViews = {},
            onUserActivity = {},
            cameraThumbnail = { _, _, _, modifier -> CameraThumbnailContent(image = null, modifier = modifier) },
        )
    }
}
