package com.matiasnl.hakiosk.ui.picker

import com.matiasnl.hakiosk.data.ha.HaArea
import com.matiasnl.hakiosk.data.ha.HaFloor
import com.matiasnl.hakiosk.data.ha.HaRegistry
import com.matiasnl.hakiosk.data.ha.HaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One entity available to pick, with whether it's already added wherever [EntityPickerState] is used. */
data class EntityPickerRow(
    val entityId: String,
    val friendlyName: String,
    val domain: String,
    val alreadyAdded: Boolean,
    /** Name of the entity's area, or null if it isn't assigned to one. */
    val areaName: String? = null,
    /** Name of the floor the entity's area belongs to, or null if the area has no floor. */
    val floorName: String? = null,
)

data class EntityPickerUiState(
    /** First [EntityPickerState.MAX_RESULTS] matches, sorted by name. */
    val results: List<EntityPickerRow> = emptyList(),
    /** How many entities match the search and filters; larger than [results] when truncated. */
    val totalMatches: Int = 0,
    val domains: List<String> = emptyList(),
    /** Floors known to Home Assistant, in registry order. Empty hides the floor filter row. */
    val floors: List<HaFloor> = emptyList(),
    /** Areas selectable given the current [floorFilter] (all areas when no floor is selected). */
    val areas: List<HaArea> = emptyList(),
    val query: String = "",
    val domainFilter: String? = null,
    val floorFilter: String? = null,
    /** An area id, [EntityPickerState.NO_AREA_ID], or null for "all areas". */
    val areaFilter: String? = null,
)

/**
 * Search/filter state for picking an entity: today the "add entities" section of the dashboard
 * editor, and from stage 3 of the dashboard rework an "add tile" modal too. Deliberately not a
 * `ViewModel`, so a modal owned by another screen's `ViewModel` can hold one directly, scoped to
 * that `ViewModel`'s [CoroutineScope].
 *
 * Real installs have thousands of entities whose states change every second, so this works on a
 * lightweight [EntityIndex] that is only rebuilt when entities are added, removed or renamed (see
 * [entityIndexFlow]), and shows at most [MAX_RESULTS] matches: the caller narrows them with search
 * and filters.
 *
 * @param alreadyAddedIds ids to mark [EntityPickerRow.alreadyAdded]; owned by the caller (e.g. the
 * dashboard editor's working set of tile entity ids) so this class has no opinion on where entities
 * end up once picked.
 */
class EntityPickerState(
    scope: CoroutineScope,
    private val haRepository: HaRepository,
    alreadyAddedIds: Flow<Set<String>>,
) {
    private val _query = MutableStateFlow("")
    private val _domainFilter = MutableStateFlow<String?>(null)
    private val _floorFilter = MutableStateFlow<String?>(null)
    private val _areaFilter = MutableStateFlow<String?>(null)

    /** Search/domain/floor/area filters, combined into one value so the ui state joins a single flow. */
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

    /** Precomputed area/floor lookups, recomputed only when [HaRepository.registry] itself emits. */
    private val registryLookup: Flow<RegistryLookup> = haRepository.registry.map { registry ->
        RegistryLookup(
            registry = registry,
            areaById = registry.areas.associateBy { it.areaId },
            floorById = registry.floors.associateBy { it.floorId },
        )
    }

    private val entityIndex: Flow<EntityIndex> = entityIndexFlow(haRepository.entities)

    val uiState: StateFlow<EntityPickerUiState> = combine(
        entityIndex,
        filters,
        registryLookup,
        alreadyAddedIds,
    ) { index, filters, lookup, addedIds ->
        buildUiState(index, filters, lookup, addedIds)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), EntityPickerUiState())

    private fun buildUiState(
        index: EntityIndex,
        filters: Filters,
        lookup: RegistryLookup,
        addedIds: Set<String>,
    ): EntityPickerUiState {
        val registry = lookup.registry
        val query = filters.query.trim().lowercase()
        val matches = index.items.asSequence().filter { it.matches(query, filters, lookup) }
        val totalMatches = matches.count()
        val results = matches
            .take(MAX_RESULTS)
            .map { item ->
                val area = registry.entityAreas[item.entityId]?.let { lookup.areaById[it] }
                val floor = area?.floorId?.let { lookup.floorById[it] }
                EntityPickerRow(
                    entityId = item.entityId,
                    friendlyName = item.friendlyName,
                    domain = item.domain,
                    alreadyAdded = item.entityId in addedIds,
                    areaName = area?.name,
                    floorName = floor?.name,
                )
            }
            .toList()
        val visibleAreas = if (filters.floorFilter != null) {
            registry.areas.filter { it.floorId == filters.floorFilter }
        } else {
            registry.areas
        }

        return EntityPickerUiState(
            results = results,
            totalMatches = totalMatches,
            domains = index.domains,
            floors = registry.floors,
            areas = visibleAreas,
            query = filters.query,
            domainFilter = filters.domainFilter,
            floorFilter = filters.floorFilter,
            areaFilter = filters.areaFilter,
        )
    }

    /** [query] is already trimmed and lowercased. */
    private fun EntityIndexItem.matches(query: String, filters: Filters, lookup: RegistryLookup): Boolean {
        if (filters.domainFilter != null && domain != filters.domainFilter) return false
        if (query.isNotEmpty() && query !in nameKey && query !in idKey) return false
        val areaId = lookup.registry.entityAreas[entityId]
        when (filters.areaFilter) {
            null -> Unit
            NO_AREA_ID -> if (areaId != null) return false
            else -> if (areaId != filters.areaFilter) return false
        }
        if (filters.floorFilter != null) {
            if (areaId == null || lookup.areaById[areaId]?.floorId != filters.floorFilter) return false
        }
        return true
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

    /** Clears the query and all filters. Call when a modal that owns this state is reopened. */
    fun reset() {
        _query.value = ""
        _domainFilter.value = null
        _floorFilter.value = null
        _areaFilter.value = null
    }

    companion object {
        /** Sentinel [EntityPickerUiState.areaFilter] value selecting entities with no assigned area. */
        const val NO_AREA_ID = "__no_area__"

        /** Rows shown in the results list; the rest is reached by narrowing the search or filters. */
        const val MAX_RESULTS = 50
    }
}

/** [HaRegistry] plus its area/floor id lookups, recomputed only when the registry changes. */
private data class RegistryLookup(
    val registry: HaRegistry,
    val areaById: Map<String, HaArea>,
    val floorById: Map<String, HaFloor>,
)
