package com.matiasnl.hakiosk.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.matiasnl.hakiosk.data.dashboard.DashboardConfigStore
import com.matiasnl.hakiosk.data.dashboard.DashboardTile
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
    /** False for read-only domains (sensors, binary_sensors, cameras): tapping them is a no-op. */
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

/** A tile's service call failed; [message] is the repository's error message shown verbatim. */
data class DashboardActionError(val label: String, val message: String)

/** Joins the configured tiles with live entity state and maps taps to Home Assistant service calls. */
class DashboardViewModel(
    private val haRepository: HaRepository,
    dashboardConfigStore: DashboardConfigStore,
) : ViewModel() {

    private val _errorEvents = MutableSharedFlow<DashboardActionError>(extraBufferCapacity = 1)

    /** Emits when a tile's service call just failed, for the screen to show as a snackbar. */
    val errorEvents: SharedFlow<DashboardActionError> = _errorEvents.asSharedFlow()

    val uiState: StateFlow<DashboardUiState> = combine(
        dashboardConfigStore.tiles,
        haRepository.entities,
        haRepository.connectionState,
    ) { tiles, entities, connectionState ->
        DashboardUiState(
            tiles = tiles.map { it.toUiState(entities) },
            connectionState = connectionState,
            hasEntities = entities.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun onTileClick(tile: DashboardTileUiState) {
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

    private fun DashboardTile.toUiState(entities: Map<String, HaEntity>): DashboardTileUiState {
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
            isActionable = entity != null && serviceFor(domain) != null,
        )
    }

    companion object {
        fun factory(haRepository: HaRepository, dashboardConfigStore: DashboardConfigStore) = viewModelFactory {
            initializer { DashboardViewModel(haRepository, dashboardConfigStore) }
        }
    }
}
