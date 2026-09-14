package com.matiasnl.hakiosk.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.matiasnl.hakiosk.data.dashboard.DashboardGrid
import com.matiasnl.hakiosk.data.dashboard.DashboardIdProvider
import com.matiasnl.hakiosk.data.dashboard.DashboardLayout
import com.matiasnl.hakiosk.data.dashboard.DashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.DashboardView
import com.matiasnl.hakiosk.data.dashboard.DashboardViewPreferencesStore
import com.matiasnl.hakiosk.data.dashboard.InMemoryDashboardViewPreferencesStore
import com.matiasnl.hakiosk.data.dashboard.TileContent
import com.matiasnl.hakiosk.data.dashboard.UuidDashboardIdProvider
import com.matiasnl.hakiosk.data.dashboard.resolveViewId
import com.matiasnl.hakiosk.data.ha.HaConnectionState
import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.HaRepository
import com.matiasnl.hakiosk.ui.dashboard.edit.DashboardEditController
import com.matiasnl.hakiosk.ui.dashboard.edit.DashboardEditState
import com.matiasnl.hakiosk.ui.dashboard.edit.LinkTargetOption
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPacker
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPacking
import com.matiasnl.hakiosk.data.dashboard.TileTapAction
import com.matiasnl.hakiosk.ui.dashboard.tiles.DomainTileBehavior
import com.matiasnl.hakiosk.ui.dashboard.tiles.DomainTileBehaviors
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileSummary
import com.matiasnl.hakiosk.ui.dashboard.tiles.EntityControlSource
import com.matiasnl.hakiosk.ui.dashboard.tiles.HaRepositoryControlSource
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileAction
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileDetailsRequest
import kotlinx.coroutines.flow.asStateFlow
import com.matiasnl.hakiosk.ui.picker.EntityPickerState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Quiet time after the last page settle before the last opened view is written to disk. */
const val LAST_VIEW_SAVE_DEBOUNCE_MILLIS = 1_500L

private const val MILLIS_PER_MINUTE = 60_000L

/** Upper bound on how long Listo waits for the store to echo the persisted layout back. */
private const val PERSIST_ECHO_TIMEOUT_MILLIS = 2_000L

/** The service a tap action calls, or null if it doesn't call one. */
private fun serviceFor(action: TileAction): String? = when (action) {
    TileAction.TOGGLE -> "toggle"
    TileAction.TURN_ON -> "turn_on"
    TileAction.NONE, TileAction.OPEN_CAMERA, TileAction.OPEN_DETAILS -> null
}

/** One cell-occupying item of the dashboard grid, in the view's tile order. Spans are as stored (unclipped). */
sealed interface DashboardTileUi {
    val id: String
    val colSpan: Int
    val rowSpan: Int
}

/** An entity button, already joined with its live entity state. */
data class DashboardTileUiState(
    override val id: String,
    val entityId: String,
    val label: String,
    val domain: String,
    val stateValue: String?,
    val unitOfMeasurement: String?,
    val isOn: Boolean,
    val isUnavailable: Boolean,
    /** True when no matching entity is known yet (removed upstream, or state not synced). */
    val isMissing: Boolean,
    /** False for read-only domains (sensors, binary_sensors) and unavailable cameras: tapping them is a no-op. */
    val isActionable: Boolean,
    override val colSpan: Int = 1,
    override val rowSpan: Int = 1,
    /** The tile's stored label override, or null if it falls back to [defaultLabel]. Edit-mode only. */
    val rawLabel: String? = null,
    /** [label] without any override applied: the entity's friendly name, or the raw entity id. Edit-mode only. */
    val defaultLabel: String = label,
    /** The tile's stored tap preference. */
    val tapAction: TileTapAction = TileTapAction.DEFAULT,
    /** True when a long press (outside edit mode) opens a details panel: the domain has one and the entity exists. */
    val hasDetails: Boolean = false,
    /** Domain-specific summary (e.g. a light's brightness); [TileSummary.Default] shows the raw state. */
    val summary: TileSummary = TileSummary.Default,
) : DashboardTileUi

/** Empty cells that separate groups of tiles. */
data class SpacerTileUiState(
    override val id: String,
    override val colSpan: Int = 1,
    override val rowSpan: Int = 1,
) : DashboardTileUi

/** Link to another view. [label] is the tile's override or the target view's name; null if neither is known. */
data class ViewLinkTileUiState(
    override val id: String,
    val targetViewId: String,
    val label: String?,
    override val colSpan: Int = 1,
    override val rowSpan: Int = 1,
    /** The tile's stored label override, or null if it falls back to [targetViewName]. */
    val rawLabel: String? = null,
    /** The target view's own name, or null if it's unknown (a stale/missing view id). */
    val targetViewName: String? = null,
) : DashboardTileUi {
    /** False when the target view doesn't exist: tapping is a no-op and the tile looks disabled. */
    val hasTarget: Boolean get() = targetViewName != null
}

/**
 * The trailing "＋" tile shown only while editing, appended after the working copy's real tiles.
 * Always 1×1 and never part of any [DashboardLayout] — it exists purely to open the add-tile modal.
 */
data class AddTileUiState(
    override val id: String = ID,
    override val colSpan: Int = 1,
    override val rowSpan: Int = 1,
) : DashboardTileUi {
    companion object {
        const val ID = "__add_tile__"
    }
}

/** One view of the (stored or working) layout, rendered as one pager page. */
data class DashboardPageUi(
    val viewId: String,
    val name: String,
    val grid: DashboardGrid = DashboardGrid(),
    /** All the view's tiles in order; while editing, the edited page also ends with the "＋" tile. */
    val tiles: List<DashboardTileUi> = emptyList(),
    /** Placements index-aligned with [tiles], packed into [grid]'s columns. Same instance until this view's structure changes. */
    val packing: GridPacking = GridPacking.Empty,
)

data class DashboardUiState(
    /**
     * True once both the layout and the last opened view are known. The screen only builds its pager
     * then, so it starts on [currentPage] instead of flashing the first page.
     */
    val isLoaded: Boolean = false,
    /** One page per view, in view order. Empty until the layout loads. */
    val pages: List<DashboardPageUi> = emptyList(),
    /**
     * Index into [pages] the dashboard should show: the edited view while editing, otherwise the
     * last settled/navigated view (falling back to the first one). The screen scrolls its pager here
     * whenever it changes.
     */
    val currentPage: Int = 0,
    val connectionState: HaConnectionState = HaConnectionState.Idle,
    /**
     * True once we've ever synced entities. After [HaRepository.stop] the connection goes back to
     * Idle but the last known entities (and this flag) stay, so the UI shouldn't treat that as an
     * error or an empty dashboard.
     */
    val hasEntities: Boolean = false,
    /** True while the dashboard renders the edit-mode working copy instead of the persisted layout. */
    val isEditing: Boolean = false,
    /** True once the working copy differs from what was persisted when edit mode was entered. */
    val isDirty: Boolean = false,
    /** Other views a "link to view" tile could target, for the add-tile modal. Empty outside edit mode. */
    val linkTargets: List<LinkTargetOption> = emptyList(),
    /** Minutes without touches before returning to the first view (outside edit mode); 0 = disabled. */
    val inactivityReturnMinutes: Int = 0,
) {
    /** The page at [currentPage] (the edited view while editing), or null before the layout loads. */
    val currentPageUi: DashboardPageUi? get() = pages.getOrNull(currentPage)

    /** Id of the current page's view, null until the layout loads. */
    val viewId: String? get() = currentPageUi?.viewId

    /** The current page's grid settings. */
    val grid: DashboardGrid get() = currentPageUi?.grid ?: DashboardGrid()

    /** The current page's tiles (the edited view's, "＋" included, while editing). */
    val tiles: List<DashboardTileUi> get() = currentPageUi?.tiles.orEmpty()

    /** The current page's packing. */
    val packing: GridPacking get() = currentPageUi?.packing ?: GridPacking.Empty
}

/** A camera tile was tapped: the screen should open its focus view. */
data class OpenCameraEvent(val entityId: String, val label: String)

/** A tile's service call failed; [message] is the repository's error message shown verbatim. */
data class DashboardActionError(val label: String, val message: String)

/** A tile summary and the entity instance it was derived from. */
private class CachedSummary(val entity: HaEntity, val summary: TileSummary)

/** The view the dashboard is on outside edit mode; [viewId] null = "whatever the first view is". */
private data class ViewSelection(val viewId: String?)

/** A view plus its packing, cached per view id so only views whose structure changed get re-packed. */
private data class PageStructure(val view: DashboardView, val showAddTile: Boolean, val packing: GridPacking)

/** Layout-derived part of the state: only recomputed (and re-packed) when the layout, selection or edit state changes, not on entity updates. */
private data class LayoutStructure(
    val isLoaded: Boolean,
    val pages: List<PageStructure>,
    val currentPage: Int,
    val viewNames: Map<String, String>,
    val isEditing: Boolean,
    val isDirty: Boolean,
    val linkTargets: List<LinkTargetOption>,
) {
    companion object {
        val NotLoaded = LayoutStructure(false, emptyList(), 0, emptyMap(), false, false, emptyList())
    }
}

/**
 * Shows every view of the dashboard as a page: all its tiles in order (entity tiles joined with live
 * entity state, spacers and view links), its grid settings and the dense packing of the tiles. Maps
 * taps on entity tiles to Home Assistant service calls.
 *
 * **Current view.** Outside edit mode the current page is the last one the pager settled on
 * ([onPageSettled]) or navigated to. It starts at the persisted last opened view (see
 * [DashboardViewPreferencesStore]); settles are written back, debounced.
 *
 * Also owns edit mode: [enterEditMode] snapshots the current layout into a working copy (via
 * [editController]) that [uiState] renders instead of the persisted layout until [doneEditMode]
 * persists it or [cancelEditMode] discards it. Entity tile taps are no-ops while editing — see
 * [onTileClick] — since a tap on a tile then opens its edit modal instead (a screen-level concern).
 */
@OptIn(FlowPreview::class)
class DashboardViewModel(
    private val haRepository: HaRepository,
    private val dashboardLayoutStore: DashboardLayoutStore,
    idProvider: DashboardIdProvider = UuidDashboardIdProvider,
    private val viewPreferencesStore: DashboardViewPreferencesStore = InMemoryDashboardViewPreferencesStore(),
    lastViewSaveDebounceMillis: Long = LAST_VIEW_SAVE_DEBOUNCE_MILLIS,
    /** Monotonic milliseconds for the inactivity timer; must match the main dispatcher's `delay` timeline. */
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : ViewModel() {

    private val _errorEvents = MutableSharedFlow<DashboardActionError>(extraBufferCapacity = 1)

    /** Emits when a tile's service call just failed, for the screen to show as a snackbar. */
    val errorEvents: SharedFlow<DashboardActionError> = _errorEvents.asSharedFlow()

    private val _openCameraEvents = MutableSharedFlow<OpenCameraEvent>(extraBufferCapacity = 1)

    /** Emits when a camera tile was tapped, for the screen to navigate to the camera view. */
    val openCameraEvents: SharedFlow<OpenCameraEvent> = _openCameraEvents.asSharedFlow()

    private val _detailsRequest = MutableStateFlow<TileDetailsRequest?>(null)

    /** The tile whose details panel is open, or null. Only ever set outside edit mode. */
    val detailsRequest: StateFlow<TileDetailsRequest?> = _detailsRequest.asStateFlow()

    /** What details panels read and call; they subscribe to their one entity only while open. */
    val entityControls: EntityControlSource = HaRepositoryControlSource(haRepository)

    /** Only used from the sequential structure flow below. */
    private val packer = GridPacker()

    /** Per view id; only touched from the sequential structure flow. */
    private val pageCache = HashMap<String, PageStructure>()

    /** Last emitted page per view id, reused when equal so unchanged pages keep their instance; only touched from [uiState]'s transform. */
    private val lastPages = HashMap<String, DashboardPageUi>()

    /**
     * Tile summaries by entity id, from the previous [uiState] build and the one in progress. A summary
     * is recomputed only when its entity instance changed (the repository replaces only changed
     * entities), and only for entities some tile shows. Only touched from [uiState]'s transform.
     */
    private var summaryCache = HashMap<String, CachedSummary>()
    private var nextSummaryCache = HashMap<String, CachedSummary>()

    private val editController = DashboardEditController(dashboardLayoutStore, idProvider)

    /**
     * Latest known persisted layout, kept eagerly so [enterEditMode] can snapshot it even before the
     * screen has subscribed to [uiState]. Null until the store's first emission: snapshotting a
     * placeholder instead would let Listo overwrite the user's real layout with an empty one.
     */
    private val layoutState: StateFlow<DashboardLayout?> = dashboardLayoutStore.layout
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Null until the persisted last view has been read. */
    private val selection = MutableStateFlow<ViewSelection?>(null)

    /** The kiosk inactivity setting as persisted (0 until read). */
    private val inactivityReturnMinutes: StateFlow<Int> = viewPreferencesStore.preferences
        .map { it.inactivityReturnMinutes }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** True while the edited page's grid has a tile lifted; main thread only. */
    private var isDragActive = false

    /** View id the preferences store holds (or is about to), to skip redundant writes. */
    private var persistedLastViewId: String? = null

    private val lastViewSaveRequests = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Ids of the working copy's edited-view entity tiles, for the add-tile modal's [EntityPicker]. */
    private val editingEntityIds = editController.state
        .map { edit -> edit.editingView?.tiles.orEmpty().mapNotNullTo(HashSet()) { (it.content as? TileContent.Entity)?.entityId } }
        .distinctUntilChanged()

    /** Entity search/filter state for the "Entidad" option of the add-tile modal. */
    val addTilePicker = EntityPickerState(
        scope = viewModelScope,
        haRepository = haRepository,
        alreadyAddedIds = editingEntityIds,
    )

    private val structure = combine(layoutState, editController.state, selection) { stored, edit, selection ->
        buildStructure(stored, edit, selection)
    }.distinctUntilChanged()

    val uiState: StateFlow<DashboardUiState> = combine(
        structure,
        haRepository.entities,
        haRepository.connectionState,
        inactivityReturnMinutes,
    ) { structure, entities, connectionState, inactivityMinutes ->
        val pages = structure.pages.map { page -> reuseIfEqual(page.toUi(structure.viewNames, entities)) }
        rotateSummaryCache()
        DashboardUiState(
            isLoaded = structure.isLoaded,
            pages = pages,
            currentPage = structure.currentPage,
            connectionState = connectionState,
            hasEntities = entities.isNotEmpty(),
            isEditing = structure.isEditing,
            isDirty = structure.isDirty,
            linkTargets = structure.linkTargets,
            inactivityReturnMinutes = inactivityMinutes,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    private val inactivityTimer = InactivityTimer(viewModelScope, clock, onTimeout = ::returnToFirstView)

    init {
        // Runs only outside edit mode with the setting on; leaving edit mode starts the countdown over.
        viewModelScope.launch {
            combine(inactivityReturnMinutes, editController.state) { minutes, edit -> if (edit.isEditing) 0 else minutes }
                .distinctUntilChanged()
                .collect { minutes -> inactivityTimer.start(minutes * MILLIS_PER_MINUTE) }
        }
        viewModelScope.launch {
            val lastViewId = viewPreferencesStore.preferences.first().lastViewId
            persistedLastViewId = lastViewId
            if (selection.value == null) selection.value = ViewSelection(lastViewId)
        }
        viewModelScope.launch {
            lastViewSaveRequests.debounce(lastViewSaveDebounceMillis).collect { viewId ->
                if (viewId != persistedLastViewId) {
                    persistedLastViewId = viewId
                    viewPreferencesStore.setLastViewId(viewId)
                }
            }
        }
    }

    /**
     * The pager settled on [viewId]'s page (a swipe or a programmatic scroll finished). Makes it the
     * current view and schedules persisting it as the last opened view. Ignored while editing (the
     * edited view drives the pager then) and for ids that aren't in the stored layout.
     */
    fun onPageSettled(viewId: String) {
        if (editController.state.value.isEditing) return
        val layout = layoutState.value ?: return
        if (layout.views.none { it.id == viewId }) return
        select(viewId)
    }

    /**
     * Snapshots the current layout into a working copy and enters edit mode on the current view.
     * No-op until the stored layout has loaded.
     */
    fun enterEditMode() {
        // Re-entering would re-snapshot the stored layout and silently drop the working copy.
        if (editController.state.value.isEditing) return
        val layout = layoutState.value ?: return
        val viewId = resolveViewId(layout, selection.value?.viewId) ?: return
        _detailsRequest.value = null
        editController.enter(layout, viewId)
    }

    /** Discards the working copy without writing anything; stays on the edited view if it still exists. */
    fun cancelEditMode() {
        val edit = editController.state.value
        val original = edit.original
        val editingViewId = edit.editingViewId
        if (original != null && editingViewId != null && original.views.any { it.id == editingViewId }) {
            select(editingViewId)
        }
        isDragActive = false
        editController.cancel()
    }

    /** Persists the working copy in one store update, exits edit mode and stays on the edited view. */
    fun doneEditMode() {
        val editingViewId = editController.state.value.takeIf { it.isEditing }?.editingViewId ?: return
        isDragActive = false
        viewModelScope.launch {
            // Selecting first means no frame falls back to another page once the edit state resets.
            select(editingViewId)
            editController.done(
                awaitPersisted = { working ->
                    withTimeoutOrNull(PERSIST_ECHO_TIMEOUT_MILLIS) { layoutState.first { it == working } }
                },
            )
        }
    }

    /**
     * Edits another view of the working copy; the screen scrolls the pager to it. Refused while a
     * tile drag is in progress, so a drag never continues on a different view's grid.
     */
    fun selectEditingView(viewId: String) {
        if (isDragActive) return
        editController.selectView(viewId)
    }

    /** The edited page's grid reports when a tile is lifted (true) and dropped (false). */
    fun setDragActive(active: Boolean) {
        isDragActive = active && editController.state.value.isEditing
    }

    // --- View management (working copy only; Cancelar discards it all) ---

    /** Appends a view and edits it (the pager follows). Blank names are refused. */
    fun addView(name: String) {
        editController.addView(name)
    }

    fun renameView(viewId: String, name: String) = editController.renameView(viewId, name)

    /** Removes a view and the links to it; never the last one. Deleting the edited view edits its neighbour. */
    fun removeView(viewId: String) = editController.removeView(viewId)

    /** Reorders views; the current page follows the edited view to its new index. */
    fun moveView(fromIndex: Int, toIndex: Int) = editController.moveView(fromIndex, toIndex)

    fun addEntityTile(entityId: String) = editController.addEntityTile(entityId)

    fun addSpacerTile() = editController.addSpacerTile()

    fun addLinkTile(targetViewId: String) = editController.addLinkTile(targetViewId)

    fun setEditTileLabel(tileId: String, label: String?) = editController.setLabel(tileId, label)

    fun resizeEditTile(tileId: String, colSpan: Int, rowSpan: Int) = editController.resizeTile(tileId, colSpan, rowSpan)

    /** Working copy only, like every edit-modal field: persisted by Listo, discarded by Cancelar. */
    fun setEditTileTapAction(tileId: String, tapAction: TileTapAction) = editController.setTapAction(tileId, tapAction)

    fun removeEditTile(tileId: String) = editController.removeTile(tileId)

    fun setEditGrid(grid: DashboardGrid) = editController.setGrid(grid)

    /** Reorders the working copy; indices are into the real tiles (the trailing "＋" tile is ignored). */
    fun moveEditTile(fromIndex: Int, toIndex: Int) = editController.moveTile(fromIndex, toIndex)

    fun onTileClick(tile: DashboardTileUiState) {
        if (uiState.value.isEditing) return
        val action = DomainTileBehaviors.forDomain(tile.domain).resolveTap(tile.tapAction)
        if (action == TileAction.OPEN_CAMERA) {
            if (tile.isActionable) _openCameraEvents.tryEmit(OpenCameraEvent(tile.entityId, tile.label))
            return
        }
        if (action == TileAction.OPEN_DETAILS) {
            openDetails(tile)
            return
        }
        val service = serviceFor(action) ?: return
        if (tile.isMissing) return
        viewModelScope.launch {
            val result = haRepository.callService(tile.domain, service, tile.entityId)
            if (result.isFailure) {
                val message = result.exceptionOrNull()?.message ?: tile.entityId
                _errorEvents.tryEmit(DashboardActionError(tile.label, message))
            }
        }
    }

    /**
     * A long press on an entity tile outside edit mode: opens its details panel if its domain has one.
     * In edit mode a long press drags the tile instead (handled by the grid), so this is a no-op then.
     */
    fun onTileLongPress(tile: DashboardTileUiState) {
        if (editController.state.value.isEditing) return
        openDetails(tile)
    }

    /** Closes the open details panel, if any. */
    fun dismissDetails() {
        _detailsRequest.value = null
    }

    private fun openDetails(tile: DashboardTileUiState) {
        if (!tile.hasDetails || tile.isMissing) return
        _detailsRequest.value = TileDetailsRequest(tile.id, tile.entityId, tile.label, tile.domain)
    }

    /**
     * A "link to view" tile was tapped: makes its target the current view, which the screen animates
     * the pager to. No-op while editing (a tap then opens the tile's edit modal) and when the target
     * view doesn't exist.
     */
    fun onViewLinkClick(tile: ViewLinkTileUiState) {
        if (editController.state.value.isEditing) return
        val layout = layoutState.value ?: return
        if (layout.views.none { it.id == tile.targetViewId }) return
        select(tile.targetViewId)
    }

    /** Any pointer event on the dashboard: restarts the inactivity countdown. Cheap enough for every event. */
    fun onUserActivity() = inactivityTimer.onActivity()

    /** Persists the kiosk inactivity setting right away (it's a preference, not part of the working copy). */
    fun setInactivityReturnMinutes(minutes: Int) {
        viewModelScope.launch { viewPreferencesStore.setInactivityReturnMinutes(minutes) }
    }

    private fun returnToFirstView() {
        if (editController.state.value.isEditing) return
        // An unattended kiosk shouldn't keep a panel (possibly an alarm keypad) open.
        _detailsRequest.value = null
        val layout = layoutState.value ?: return
        val firstViewId = layout.views.firstOrNull()?.id ?: return
        if (resolveViewId(layout, selection.value?.viewId) != firstViewId) select(firstViewId)
    }

    private fun select(viewId: String) {
        selection.value = ViewSelection(viewId)
        lastViewSaveRequests.tryEmit(viewId)
    }

    private fun buildStructure(stored: DashboardLayout?, edit: DashboardEditState, selection: ViewSelection?): LayoutStructure {
        val layout = edit.working ?: stored ?: return LayoutStructure.NotLoaded
        val currentViewId = if (edit.isEditing) edit.editingViewId else resolveViewId(layout, selection?.viewId)
        val pages = layout.views.map { view ->
            pageStructure(view, showAddTile = edit.isEditing && view.id == edit.editingViewId)
        }
        if (pageCache.size > pages.size) pageCache.keys.retainAll(layout.views.mapTo(HashSet()) { it.id })
        return LayoutStructure(
            isLoaded = selection != null,
            pages = pages,
            currentPage = layout.views.indexOfFirst { it.id == currentViewId }.coerceAtLeast(0),
            viewNames = layout.views.associate { it.id to it.name },
            isEditing = edit.isEditing,
            isDirty = edit.isDirty,
            linkTargets = edit.linkTargets,
        )
    }

    /** Re-packs [view] only if it (or whether it shows the "＋" tile) changed since the last build. */
    private fun pageStructure(view: DashboardView, showAddTile: Boolean): PageStructure {
        val cached = pageCache[view.id]
        if (cached != null && cached.showAddTile == showAddTile && cached.view == view) return cached
        val realCount = view.tiles.size
        val totalCount = realCount + if (showAddTile) 1 else 0
        val packing = packer.pack(
            view.grid.columns,
            totalCount,
            colSpanOf = { i -> if (i < realCount) view.tiles[i].colSpan else 1 },
            rowSpanOf = { i -> if (i < realCount) view.tiles[i].rowSpan else 1 },
        )
        return PageStructure(view, showAddTile, packing).also { pageCache[view.id] = it }
    }

    private fun PageStructure.toUi(viewNames: Map<String, String>, entities: Map<String, HaEntity>): DashboardPageUi {
        val tiles = view.tiles.map { tile ->
            when (val content = tile.content) {
                is TileContent.Entity -> content.toUiState(tile.id, tile.colSpan, tile.rowSpan, entities)
                is TileContent.Spacer -> SpacerTileUiState(tile.id, tile.colSpan, tile.rowSpan)
                is TileContent.ViewLink -> ViewLinkTileUiState(
                    id = tile.id,
                    targetViewId = content.targetViewId,
                    label = content.label ?: viewNames[content.targetViewId],
                    colSpan = tile.colSpan,
                    rowSpan = tile.rowSpan,
                    rawLabel = content.label,
                    targetViewName = viewNames[content.targetViewId],
                )
            }
        }
        return DashboardPageUi(
            viewId = view.id,
            name = view.name,
            grid = view.grid,
            tiles = if (showAddTile) tiles + AddTileUiState() else tiles,
            packing = packing,
        )
    }

    private fun summaryFor(behavior: DomainTileBehavior, entity: HaEntity): TileSummary {
        val id = entity.entityId
        val cached = nextSummaryCache[id]?.takeIf { it.entity === entity }
            ?: summaryCache[id]?.takeIf { it.entity === entity }
            ?: CachedSummary(entity, behavior.summarize(entity))
        nextSummaryCache[id] = cached
        return cached.summary
    }

    /** Keeps this build's summaries for the next one and drops the rest (entities no tile shows anymore). */
    private fun rotateSummaryCache() {
        val previous = summaryCache
        summaryCache = nextSummaryCache
        nextSummaryCache = previous.also { it.clear() }
    }

    /** Keeps the previous instance of an unchanged page, so the screen can skip recomposing it. */
    private fun reuseIfEqual(page: DashboardPageUi): DashboardPageUi {
        val previous = lastPages[page.viewId]
        if (previous == page) return previous
        lastPages[page.viewId] = page
        return page
    }

    private fun TileContent.Entity.toUiState(
        id: String,
        colSpan: Int,
        rowSpan: Int,
        entities: Map<String, HaEntity>,
    ): DashboardTileUiState {
        val entity = entities[entityId]
        val domain = entityId.substringBefore('.')
        val fallbackLabel = entity?.friendlyName ?: entityId
        val behavior = DomainTileBehaviors.forDomain(domain)
        val action = behavior.resolveTap(tapAction)
        return DashboardTileUiState(
            id = id,
            entityId = entityId,
            label = label ?: fallbackLabel,
            domain = domain,
            stateValue = entity?.state,
            unitOfMeasurement = entity?.unitOfMeasurement,
            isOn = entity?.state == "on",
            isUnavailable = entity?.isUnavailable == true,
            isMissing = entity == null,
            isActionable = entity != null && when (action) {
                TileAction.TOGGLE, TileAction.TURN_ON -> true
                TileAction.OPEN_CAMERA -> !entity.isUnavailable
                TileAction.OPEN_DETAILS -> behavior.details != null
                TileAction.NONE -> false
            },
            colSpan = colSpan,
            rowSpan = rowSpan,
            rawLabel = label,
            defaultLabel = fallbackLabel,
            tapAction = tapAction,
            hasDetails = entity != null && behavior.details != null,
            summary = if (entity != null) summaryFor(behavior, entity) else TileSummary.Default,
        )
    }

    companion object {
        fun factory(
            haRepository: HaRepository,
            dashboardLayoutStore: DashboardLayoutStore,
            viewPreferencesStore: DashboardViewPreferencesStore,
        ) = viewModelFactory {
            initializer {
                DashboardViewModel(haRepository, dashboardLayoutStore, viewPreferencesStore = viewPreferencesStore)
            }
        }
    }
}
