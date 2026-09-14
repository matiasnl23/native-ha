package com.matiasnl.hakiosk.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.matiasnl.hakiosk.data.dashboard.DashboardConfigStore
import com.matiasnl.hakiosk.data.dashboard.DashboardTile
import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.HaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A tile already on the dashboard, in its current order. */
data class EditorTileRow(
    val entityId: String,
    /** Friendly name from Home Assistant, or the raw entity id if the entity isn't known yet. */
    val friendlyName: String,
    val label: String?,
)

/** An entity available to add, with whether it is already a tile. */
data class EditorAvailableRow(
    val entityId: String,
    val friendlyName: String,
    val domain: String,
    val alreadyAdded: Boolean,
)

data class EditorUiState(
    val currentTiles: List<EditorTileRow> = emptyList(),
    val availableEntities: List<EditorAvailableRow> = emptyList(),
    val domains: List<String> = emptyList(),
    val query: String = "",
    val domainFilter: String? = null,
)

/**
 * Drives the dashboard editor: a working copy of the tile list (not written to
 * [dashboardConfigStore] until [save]) joined with every known entity for the "add" list.
 */
class EditorViewModel(
    private val haRepository: HaRepository,
    private val dashboardConfigStore: DashboardConfigStore,
) : ViewModel() {

    private val _workingTiles = MutableStateFlow<List<DashboardTile>>(emptyList())
    private val _query = MutableStateFlow("")
    private val _domainFilter = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            _workingTiles.value = dashboardConfigStore.tiles.first()
        }
    }

    val uiState: StateFlow<EditorUiState> = combine(
        _workingTiles,
        haRepository.entities,
        _query,
        _domainFilter,
    ) { tiles, entities, query, domainFilter ->
        buildUiState(tiles, entities, query, domainFilter)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUiState())

    private fun buildUiState(
        tiles: List<DashboardTile>,
        entities: Map<String, HaEntity>,
        query: String,
        domainFilter: String?,
    ): EditorUiState {
        val tileIds = tiles.map { it.entityId }.toSet()
        val currentTiles = tiles.map { tile ->
            EditorTileRow(
                entityId = tile.entityId,
                friendlyName = entities[tile.entityId]?.friendlyName ?: tile.entityId,
                label = tile.label,
            )
        }
        val normalizedQuery = query.trim().lowercase()
        val availableEntities = entities.values
            .asSequence()
            .filter { domainFilter == null || it.domain == domainFilter }
            .filter {
                normalizedQuery.isEmpty() ||
                    it.friendlyName.lowercase().contains(normalizedQuery) ||
                    it.entityId.lowercase().contains(normalizedQuery)
            }
            .sortedBy { it.friendlyName.lowercase() }
            .map { entity ->
                EditorAvailableRow(
                    entityId = entity.entityId,
                    friendlyName = entity.friendlyName,
                    domain = entity.domain,
                    alreadyAdded = entity.entityId in tileIds,
                )
            }
            .toList()
        val domains = entities.values.map { it.domain }.distinct().sorted()

        return EditorUiState(
            currentTiles = currentTiles,
            availableEntities = availableEntities,
            domains = domains,
            query = query,
            domainFilter = domainFilter,
        )
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun onDomainFilterChange(domain: String?) {
        _domainFilter.value = domain
    }

    fun addTile(entityId: String) {
        _workingTiles.update { tiles ->
            if (tiles.any { it.entityId == entityId }) tiles else tiles + DashboardTile(entityId)
        }
    }

    fun removeTile(entityId: String) {
        _workingTiles.update { tiles -> tiles.filterNot { it.entityId == entityId } }
    }

    fun setLabel(entityId: String, label: String) {
        val trimmed = label.trim()
        _workingTiles.update { tiles ->
            tiles.map { if (it.entityId == entityId) it.copy(label = trimmed.ifEmpty { null }) else it }
        }
    }

    fun moveUp(entityId: String) {
        _workingTiles.update { tiles -> tiles.moved(entityId, -1) }
    }

    fun moveDown(entityId: String) {
        _workingTiles.update { tiles -> tiles.moved(entityId, +1) }
    }

    private fun List<DashboardTile>.moved(entityId: String, delta: Int): List<DashboardTile> {
        val index = indexOfFirst { it.entityId == entityId }
        val target = index + delta
        if (index < 0 || target < 0 || target >= size) return this
        return toMutableList().apply {
            val item = removeAt(index)
            add(target, item)
        }
    }

    /** Persists the working tile list and invokes [onSaved]. */
    fun save(onSaved: () -> Unit) {
        viewModelScope.launch {
            dashboardConfigStore.setTiles(_workingTiles.value)
            onSaved()
        }
    }

    companion object {
        fun factory(haRepository: HaRepository, dashboardConfigStore: DashboardConfigStore) = viewModelFactory {
            initializer { EditorViewModel(haRepository, dashboardConfigStore) }
        }
    }
}
