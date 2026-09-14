package com.matiasnl.hakiosk.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.matiasnl.hakiosk.data.dashboard.DashboardGrid
import com.matiasnl.hakiosk.data.dashboard.DashboardLayout
import com.matiasnl.hakiosk.data.dashboard.DashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.DashboardView
import com.matiasnl.hakiosk.data.dashboard.TileContent
import com.matiasnl.hakiosk.data.ha.HaConnectionState
import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.HaRepository
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPacker
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPacking
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
) : DashboardTileUi

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
)

/** A camera tile was tapped: the screen should open its focus view. */
data class OpenCameraEvent(val entityId: String, val label: String)

/** A tile's service call failed; [message] is the repository's error message shown verbatim. */
data class DashboardActionError(val label: String, val message: String)

/** Layout-derived part of the state: only recomputed (and re-packed) when the layout changes, not on entity updates. */
private data class ViewStructure(
    val view: DashboardView?,
    val viewNames: Map<String, String>,
    val packing: GridPacking,
)

/**
 * Shows the first view: all its tiles in order (entity tiles joined with live entity state, spacers
 * and view links), its grid settings and the dense packing of the tiles. Maps taps on entity tiles to
 * Home Assistant service calls.
 */
class DashboardViewModel(
    private val haRepository: HaRepository,
    dashboardLayoutStore: DashboardLayoutStore,
) : ViewModel() {

    private val _errorEvents = MutableSharedFlow<DashboardActionError>(extraBufferCapacity = 1)

    /** Emits when a tile's service call just failed, for the screen to show as a snackbar. */
    val errorEvents: SharedFlow<DashboardActionError> = _errorEvents.asSharedFlow()

    private val _openCameraEvents = MutableSharedFlow<OpenCameraEvent>(extraBufferCapacity = 1)

    /** Emits when a camera tile was tapped, for the screen to navigate to the camera view. */
    val openCameraEvents: SharedFlow<OpenCameraEvent> = _openCameraEvents.asSharedFlow()

    /** Only used from the sequential layout flow below. */
    private val packer = GridPacker()

    private val structure = dashboardLayoutStore.layout
        .distinctUntilChanged()
        .map { layout -> layout.toStructure() }

    val uiState: StateFlow<DashboardUiState> = combine(
        structure,
        haRepository.entities,
        haRepository.connectionState,
    ) { structure, entities, connectionState ->
        val view = structure.view
        DashboardUiState(
            viewId = view?.id,
            grid = view?.grid ?: DashboardGrid(),
            tiles = view?.tiles.orEmpty().map { tile ->
                when (val content = tile.content) {
                    is TileContent.Entity -> content.toUiState(tile.id, tile.colSpan, tile.rowSpan, entities)
                    is TileContent.Spacer -> SpacerTileUiState(tile.id, tile.colSpan, tile.rowSpan)
                    is TileContent.ViewLink -> ViewLinkTileUiState(
                        id = tile.id,
                        targetViewId = content.targetViewId,
                        label = content.label ?: structure.viewNames[content.targetViewId],
                        colSpan = tile.colSpan,
                        rowSpan = tile.rowSpan,
                    )
                }
            },
            packing = structure.packing,
            connectionState = connectionState,
            hasEntities = entities.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun onTileClick(tile: DashboardTileUiState) {
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

    private fun DashboardLayout.toStructure(): ViewStructure {
        val view = views.firstOrNull()
        val packing = view?.let { packer.pack(it.grid.columns, it.tiles, { t -> t.colSpan }, { t -> t.rowSpan }) }
            ?: GridPacking.Empty
        return ViewStructure(view = view, viewNames = views.associate { it.id to it.name }, packing = packing)
    }

    private fun TileContent.Entity.toUiState(
        id: String,
        colSpan: Int,
        rowSpan: Int,
        entities: Map<String, HaEntity>,
    ): DashboardTileUiState {
        val entity = entities[entityId]
        val domain = entityId.substringBefore('.')
        return DashboardTileUiState(
            id = id,
            entityId = entityId,
            label = label ?: entity?.friendlyName ?: entityId,
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
        )
    }

    companion object {
        fun factory(haRepository: HaRepository, dashboardLayoutStore: DashboardLayoutStore) = viewModelFactory {
            initializer { DashboardViewModel(haRepository, dashboardLayoutStore) }
        }
    }
}
