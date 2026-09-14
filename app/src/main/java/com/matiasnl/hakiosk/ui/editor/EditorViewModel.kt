package com.matiasnl.hakiosk.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.matiasnl.hakiosk.data.dashboard.DashboardConfigStore
import com.matiasnl.hakiosk.data.dashboard.DashboardTile
import com.matiasnl.hakiosk.data.ha.HaArea
import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.HaFloor
import com.matiasnl.hakiosk.data.ha.HaRegistry
import com.matiasnl.hakiosk.data.ha.HaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    /** Name of the entity's area, or null if it isn't assigned to one. */
    val areaName: String? = null,
    /** Name of the floor the entity's area belongs to, or null if the area has no floor. */
    val floorName: String? = null,
)

data class EditorUiState(
    val currentTiles: List<EditorTileRow> = emptyList(),
    val availableEntities: List<EditorAvailableRow> = emptyList(),
    val domains: List<String> = emptyList(),
    /** Floors known to Home Assistant, in registry order. Empty hides the floor filter row. */
    val floors: List<HaFloor> = emptyList(),
    /** Areas selectable given the current [floorFilter] (all areas when no floor is selected). */
    val areas: List<HaArea> = emptyList(),
    val query: String = "",
    val domainFilter: String? = null,
    val floorFilter: String? = null,
    /** An area id, [EditorViewModel.NO_AREA_ID], or null for "all areas". */
    val areaFilter: String? = null,
    /** False until the stored tile list has finished loading once. */
    val isLoaded: Boolean = false,
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
    private val _floorFilter = MutableStateFlow<String?>(null)
    private val _areaFilter = MutableStateFlow<String?>(null)
    private val _isLoaded = MutableStateFlow(false)

    /**
     * Edits applied to [_workingTiles] before [dashboardConfigStore] finished its initial load.
     * Replayed on top of the loaded list once it arrives so they aren't lost, without discarding
     * whatever was already stored on disk.
     */
    private val pendingEditsBeforeLoad = mutableListOf<(List<DashboardTile>) -> List<DashboardTile>>()

    init {
        viewModelScope.launch {
            val loaded = dashboardConfigStore.tiles.first()
            _workingTiles.value = pendingEditsBeforeLoad.fold(loaded) { tiles, edit -> edit(tiles) }
            pendingEditsBeforeLoad.clear()
            _isLoaded.value = true
        }
    }

    /** Search/domain/floor/area filters, combined into one value so [registryLookup] and the tile
     * flows only need to be joined with a single additional flow below. */
    private data class Filters(
        val query: String,
        val domainFilter: String?,
        val floorFilter: String?,
        val areaFilter: String?,
    )

    private val filters: Flow<Filters> = combine(
        _query,
        _domainFilter,
        _floorFilter,
        _areaFilter,
    ) { query, domainFilter, floorFilter, areaFilter ->
        Filters(query, domainFilter, floorFilter, areaFilter)
    }

    /** Precomputed area/floor lookups, recomputed only when [HaRepository.registry] itself emits
     * (not on every unrelated filter/search change), since the registry can hold many entries. */
    private val registryLookup: Flow<RegistryLookup> = haRepository.registry.map { registry ->
        RegistryLookup(
            registry = registry,
            areaById = registry.areas.associateBy { it.areaId },
            floorById = registry.floors.associateBy { it.floorId },
        )
    }

    val uiState: StateFlow<EditorUiState> = combine(
        _workingTiles,
        haRepository.entities,
        filters,
        registryLookup,
        _isLoaded,
    ) { tiles, entities, filters, lookup, isLoaded ->
        buildUiState(tiles, entities, filters, lookup, isLoaded)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUiState())

    /** Applies [edit] to the working tiles now, and again once a pending initial load arrives. */
    private fun editWorkingTiles(edit: (List<DashboardTile>) -> List<DashboardTile>) {
        _workingTiles.update(edit)
        if (!_isLoaded.value) {
            pendingEditsBeforeLoad += edit
        }
    }

    private fun buildUiState(
        tiles: List<DashboardTile>,
        entities: Map<String, HaEntity>,
        filters: Filters,
        lookup: RegistryLookup,
        isLoaded: Boolean,
    ): EditorUiState {
        val registry = lookup.registry
        val tileIds = tiles.map { it.entityId }.toSet()
        val currentTiles = tiles.map { tile ->
            EditorTileRow(
                entityId = tile.entityId,
                friendlyName = entities[tile.entityId]?.friendlyName ?: tile.entityId,
                label = tile.label,
            )
        }
        val normalizedQuery = filters.query.trim().lowercase()
        val areaFilter = filters.areaFilter
        val floorFilter = filters.floorFilter
        val availableEntities = entities.values
            .asSequence()
            .filter { filters.domainFilter == null || it.domain == filters.domainFilter }
            .filter {
                normalizedQuery.isEmpty() ||
                    it.friendlyName.lowercase().contains(normalizedQuery) ||
                    it.entityId.lowercase().contains(normalizedQuery)
            }
            .filter { entity ->
                when (areaFilter) {
                    null -> true
                    NO_AREA_ID -> registry.entityAreas[entity.entityId] == null
                    else -> registry.entityAreas[entity.entityId] == areaFilter
                }
            }
            .filter { entity ->
                if (floorFilter == null) return@filter true
                val areaId = registry.entityAreas[entity.entityId] ?: return@filter false
                lookup.areaById[areaId]?.floorId == floorFilter
            }
            .sortedBy { it.friendlyName.lowercase() }
            .map { entity ->
                val area = registry.entityAreas[entity.entityId]?.let { lookup.areaById[it] }
                val floor = area?.floorId?.let { lookup.floorById[it] }
                EditorAvailableRow(
                    entityId = entity.entityId,
                    friendlyName = entity.friendlyName,
                    domain = entity.domain,
                    alreadyAdded = entity.entityId in tileIds,
                    areaName = area?.name,
                    floorName = floor?.name,
                )
            }
            .toList()
        val domains = entities.values.map { it.domain }.distinct().sorted()
        val visibleAreas = if (floorFilter != null) {
            registry.areas.filter { it.floorId == floorFilter }
        } else {
            registry.areas
        }

        return EditorUiState(
            currentTiles = currentTiles,
            availableEntities = availableEntities,
            domains = domains,
            floors = registry.floors,
            areas = visibleAreas,
            query = filters.query,
            domainFilter = filters.domainFilter,
            floorFilter = floorFilter,
            areaFilter = areaFilter,
            isLoaded = isLoaded,
        )
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun onDomainFilterChange(domain: String?) {
        _domainFilter.value = domain
    }

    fun onFloorFilterChange(floorId: String?) {
        _floorFilter.value = floorId
        val currentAreaFilter = _areaFilter.value ?: return
        if (floorId == null) return
        if (currentAreaFilter == NO_AREA_ID) {
            _areaFilter.value = null
            return
        }
        val area = haRepository.registry.value.areas.firstOrNull { it.areaId == currentAreaFilter }
        if (area == null || area.floorId != floorId) {
            _areaFilter.value = null
        }
    }

    fun onAreaFilterChange(areaId: String?) {
        _areaFilter.value = areaId
    }

    fun addTile(entityId: String) {
        editWorkingTiles { tiles ->
            if (tiles.any { it.entityId == entityId }) tiles else tiles + DashboardTile(entityId)
        }
    }

    fun removeTile(entityId: String) {
        editWorkingTiles { tiles -> tiles.filterNot { it.entityId == entityId } }
    }

    fun setLabel(entityId: String, label: String) {
        val trimmed = label.trim()
        editWorkingTiles { tiles ->
            tiles.map { if (it.entityId == entityId) it.copy(label = trimmed.ifEmpty { null }) else it }
        }
    }

    fun moveUp(entityId: String) {
        editWorkingTiles { tiles -> tiles.moved(entityId, -1) }
    }

    fun moveDown(entityId: String) {
        editWorkingTiles { tiles -> tiles.moved(entityId, +1) }
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
        /** Sentinel [EditorUiState.areaFilter] value selecting entities with no assigned area. */
        const val NO_AREA_ID = "__no_area__"

        fun factory(haRepository: HaRepository, dashboardConfigStore: DashboardConfigStore) = viewModelFactory {
            initializer { EditorViewModel(haRepository, dashboardConfigStore) }
        }
    }
}

/** [HaRegistry] plus its area/floor id lookups, recomputed only when the registry changes. */
private data class RegistryLookup(
    val registry: HaRegistry,
    val areaById: Map<String, HaArea>,
    val floorById: Map<String, HaFloor>,
)
