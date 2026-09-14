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
import com.matiasnl.hakiosk.data.dashboard.TileContent
import com.matiasnl.hakiosk.data.dashboard.UuidDashboardIdProvider
import com.matiasnl.hakiosk.data.ha.HaConnectionState
import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.HaRepository
import com.matiasnl.hakiosk.ui.dashboard.edit.DashboardEditController
import com.matiasnl.hakiosk.ui.dashboard.edit.DashboardEditState
import com.matiasnl.hakiosk.ui.dashboard.edit.LinkTargetOption
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPacker
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPacking
import com.matiasnl.hakiosk.ui.picker.EntityPickerState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Domains whose tap toggles the entity. */
private val TOGGLE_DOMAINS = setOf("light", "switch", "fan", "input_boolean", "automation")

/** Domains whose tap fires `turn_on` (running a scene/script is not really "on/off"). */
private val TURN_ON_DOMAINS = setOf("scene", "script")

/** Camera tiles open the full-screen camera view instead of calling a service. */
private const val CAMERA_DOMAIN = "camera"

/** The service a tap on this domain should call, or null if the tile is display-only. */
private fun serviceFor(domain: String): String? = when (domain) {
    in TOGGLE_DOMAINS -> "toggle"
    in TURN_ON_DOMAINS -> "turn_on"
    else -> null
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
    /** The tile's stored label override, or null if it falls back to [targetViewName]. Edit-mode only. */
    val rawLabel: String? = null,
    /** The target view's own name, or null if it's unknown (a stale/missing view id). Edit-mode only. */
    val targetViewName: String? = null,
) : DashboardTileUi

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

data class DashboardUiState(
    /** Id of the view being shown, null until the layout loads. */
    val viewId: String? = null,
    val grid: DashboardGrid = DashboardGrid(),
    val tiles: List<DashboardTileUi> = emptyList(),
    /** Placements index-aligned with [tiles], packed into [grid]'s columns. Same instance until the layout changes. */
    val packing: GridPacking = GridPacking.Empty,
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
)

/** A camera tile was tapped: the screen should open its focus view. */
data class OpenCameraEvent(val entityId: String, val label: String)

/** A tile's service call failed; [message] is the repository's error message shown verbatim. */
data class DashboardActionError(val label: String, val message: String)

/** Layout-derived part of the state: only recomputed (and re-packed) when the layout or edit state changes, not on entity updates. */
private data class ViewStructure(
    val view: DashboardView?,
    val viewNames: Map<String, String>,
    val packing: GridPacking,
    val isEditing: Boolean,
    val isDirty: Boolean,
    val linkTargets: List<LinkTargetOption>,
    /** True once a trailing "＋" placeholder was folded into [packing] (edit mode only). */
    val showAddTile: Boolean,
)

/**
 * Shows the first view: all its tiles in order (entity tiles joined with live entity state, spacers
 * and view links), its grid settings and the dense packing of the tiles. Maps taps on entity tiles to
 * Home Assistant service calls.
 *
 * Also owns edit mode: [enterEditMode] snapshots the current layout into a working copy (via
 * [editController]) that [uiState] renders instead of the persisted layout until [doneEditMode]
 * persists it or [cancelEditMode] discards it. Entity tile taps are no-ops while editing — see
 * [onTileClick] — since a tap on a tile then opens its edit modal instead (a screen-level concern).
 */
class DashboardViewModel(
    private val haRepository: HaRepository,
    private val dashboardLayoutStore: DashboardLayoutStore,
    idProvider: DashboardIdProvider = UuidDashboardIdProvider,
) : ViewModel() {

    private val _errorEvents = MutableSharedFlow<DashboardActionError>(extraBufferCapacity = 1)

    /** Emits when a tile's service call just failed, for the screen to show as a snackbar. */
    val errorEvents: SharedFlow<DashboardActionError> = _errorEvents.asSharedFlow()

    private val _openCameraEvents = MutableSharedFlow<OpenCameraEvent>(extraBufferCapacity = 1)

    /** Emits when a camera tile was tapped, for the screen to navigate to the camera view. */
    val openCameraEvents: SharedFlow<OpenCameraEvent> = _openCameraEvents.asSharedFlow()

    /** Only used from the sequential layout flow below. */
    private val packer = GridPacker()

    private val editController = DashboardEditController(dashboardLayoutStore, idProvider)

    /**
     * Latest known persisted layout, kept eagerly so [enterEditMode] can snapshot it even before the
     * screen has subscribed to [uiState]. Null until the store's first emission: snapshotting a
     * placeholder instead would let Listo overwrite the user's real layout with an empty one.
     */
    private val layoutState: StateFlow<DashboardLayout?> = dashboardLayoutStore.layout
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Ids of the working copy's current-view entity tiles, for the add-tile modal's [EntityPicker]. */
    private val editingEntityIds = editController.state
        .map { edit -> edit.editingView?.tiles.orEmpty().mapNotNullTo(HashSet()) { (it.content as? TileContent.Entity)?.entityId } }
        .distinctUntilChanged()

    /** Entity search/filter state for the "Entidad" option of the add-tile modal. */
    val addTilePicker = EntityPickerState(
        scope = viewModelScope,
        haRepository = haRepository,
        alreadyAddedIds = editingEntityIds,
    )

    private val structure = combine(dashboardLayoutStore.layout, editController.state) { stored, edit ->
        buildStructure(stored, edit)
    }.distinctUntilChanged()

    val uiState: StateFlow<DashboardUiState> = combine(
        structure,
        haRepository.entities,
        haRepository.connectionState,
    ) { structure, entities, connectionState ->
        val view = structure.view
        val tiles = view?.tiles.orEmpty().map { tile ->
            when (val content = tile.content) {
                is TileContent.Entity -> content.toUiState(tile.id, tile.colSpan, tile.rowSpan, entities)
                is TileContent.Spacer -> SpacerTileUiState(tile.id, tile.colSpan, tile.rowSpan)
                is TileContent.ViewLink -> ViewLinkTileUiState(
                    id = tile.id,
                    targetViewId = content.targetViewId,
                    label = content.label ?: structure.viewNames[content.targetViewId],
                    colSpan = tile.colSpan,
                    rowSpan = tile.rowSpan,
                    rawLabel = content.label,
                    targetViewName = structure.viewNames[content.targetViewId],
                )
            }
        }
        DashboardUiState(
            viewId = view?.id,
            grid = view?.grid ?: DashboardGrid(),
            tiles = if (structure.showAddTile) tiles + AddTileUiState() else tiles,
            packing = structure.packing,
            connectionState = connectionState,
            hasEntities = entities.isNotEmpty(),
            isEditing = structure.isEditing,
            isDirty = structure.isDirty,
            linkTargets = structure.linkTargets,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    /**
     * Snapshots the first view of the current layout into a working copy and enters edit mode. No-op
     * until the stored layout has loaded.
     */
    fun enterEditMode() {
        val layout = layoutState.value ?: return
        val viewId = layout.views.firstOrNull()?.id ?: return
        editController.enter(layout, viewId)
    }

    /** Discards the working copy without writing anything. */
    fun cancelEditMode() = editController.cancel()

    /** Persists the working copy in one store update and exits edit mode. */
    fun doneEditMode() {
        viewModelScope.launch { editController.done() }
    }

    fun addEntityTile(entityId: String) = editController.addEntityTile(entityId)

    fun addSpacerTile() = editController.addSpacerTile()

    fun addLinkTile(targetViewId: String) = editController.addLinkTile(targetViewId)

    fun setEditTileLabel(tileId: String, label: String?) = editController.setLabel(tileId, label)

    fun resizeEditTile(tileId: String, colSpan: Int, rowSpan: Int) = editController.resizeTile(tileId, colSpan, rowSpan)

    fun removeEditTile(tileId: String) = editController.removeTile(tileId)

    fun setEditGrid(grid: DashboardGrid) = editController.setGrid(grid)

    /** Reorders the working copy; indices are into the real tiles (the trailing "＋" tile is ignored). */
    fun moveEditTile(fromIndex: Int, toIndex: Int) = editController.moveTile(fromIndex, toIndex)

    fun onTileClick(tile: DashboardTileUiState) {
        if (uiState.value.isEditing) return
        if (tile.domain == CAMERA_DOMAIN) {
            if (tile.isActionable) _openCameraEvents.tryEmit(OpenCameraEvent(tile.entityId, tile.label))
            return
        }
        val service = serviceFor(tile.domain) ?: return
        if (tile.isMissing) return
        viewModelScope.launch {
            val result = haRepository.callService(tile.domain, service, tile.entityId)
            if (result.isFailure) {
                val message = result.exceptionOrNull()?.message ?: tile.entityId
                _errorEvents.tryEmit(DashboardActionError(tile.label, message))
            }
        }
    }

    private fun buildStructure(stored: DashboardLayout, edit: DashboardEditState): ViewStructure {
        val layout = edit.working ?: stored
        val viewId = if (edit.isEditing) edit.editingViewId else layout.views.firstOrNull()?.id
        val view = layout.views.firstOrNull { it.id == viewId }
        val realCount = view?.tiles?.size ?: 0
        val showAddTile = edit.isEditing
        val totalCount = realCount + if (showAddTile) 1 else 0
        val packing = view?.let {
            packer.pack(
                it.grid.columns,
                totalCount,
                colSpanOf = { i -> if (i < realCount) it.tiles[i].colSpan else 1 },
                rowSpanOf = { i -> if (i < realCount) it.tiles[i].rowSpan else 1 },
            )
        } ?: GridPacking.Empty
        return ViewStructure(
            view = view,
            viewNames = layout.views.associate { it.id to it.name },
            packing = packing,
            isEditing = edit.isEditing,
            isDirty = edit.isDirty,
            linkTargets = edit.linkTargets,
            showAddTile = showAddTile,
        )
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
            isActionable = entity != null &&
                (serviceFor(domain) != null || (domain == CAMERA_DOMAIN && !entity.isUnavailable)),
            colSpan = colSpan,
            rowSpan = rowSpan,
            rawLabel = label,
            defaultLabel = fallbackLabel,
        )
    }

    companion object {
        fun factory(haRepository: HaRepository, dashboardLayoutStore: DashboardLayoutStore) = viewModelFactory {
            initializer { DashboardViewModel(haRepository, dashboardLayoutStore) }
        }
    }
}
