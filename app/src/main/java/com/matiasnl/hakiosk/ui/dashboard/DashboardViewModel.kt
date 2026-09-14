package com.matiasnl.hakiosk.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.matiasnl.hakiosk.data.dashboard.DashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.TileContent
import com.matiasnl.hakiosk.data.ha.HaConnectionState
import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.HaRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
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

/** One rendered dashboard button, already joined with its live entity state. */
data class DashboardTileUiState(
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
)

data class DashboardUiState(
    val tiles: List<DashboardTileUiState> = emptyList(),
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

/**
 * Joins the first view's entity tiles with live entity state and maps taps to Home Assistant service
 * calls. Spacer and view-link tiles are ignored for now: none exist yet (nothing creates them before
 * a later stage), and only entity tiles render as buttons today.
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

    val uiState: StateFlow<DashboardUiState> = combine(
        dashboardLayoutStore.layout,
        haRepository.entities,
        haRepository.connectionState,
    ) { layout, entities, connectionState ->
        val entityTiles = layout.views.firstOrNull()?.tiles.orEmpty()
            .mapNotNull { tile -> (tile.content as? TileContent.Entity)?.toUiState(entities) }
        DashboardUiState(
            tiles = entityTiles,
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

    private fun TileContent.Entity.toUiState(entities: Map<String, HaEntity>): DashboardTileUiState {
        val entity = entities[entityId]
        val domain = entityId.substringBefore('.')
        return DashboardTileUiState(
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
        )
    }

    companion object {
        fun factory(haRepository: HaRepository, dashboardLayoutStore: DashboardLayoutStore) = viewModelFactory {
            initializer { DashboardViewModel(haRepository, dashboardLayoutStore) }
        }
    }
}
