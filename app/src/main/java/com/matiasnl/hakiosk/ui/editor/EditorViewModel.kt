package com.matiasnl.hakiosk.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.matiasnl.hakiosk.data.dashboard.DashboardIdProvider
import com.matiasnl.hakiosk.data.dashboard.DashboardLayout
import com.matiasnl.hakiosk.data.dashboard.DashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.DashboardView
import com.matiasnl.hakiosk.data.dashboard.TileContent
import com.matiasnl.hakiosk.data.dashboard.UuidDashboardIdProvider
import com.matiasnl.hakiosk.data.dashboard.addTile
import com.matiasnl.hakiosk.data.dashboard.defaultDashboardLayout
import com.matiasnl.hakiosk.data.dashboard.moveTile
import com.matiasnl.hakiosk.data.dashboard.newDashboardTile
import com.matiasnl.hakiosk.data.dashboard.removeTile
import com.matiasnl.hakiosk.data.dashboard.updateTile
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    /** First [EditorViewModel.MAX_AVAILABLE_RESULTS] matches, sorted by name. */
    val availableEntities: List<EditorAvailableRow> = emptyList(),
    /** How many entities match the search and filters; larger than [availableEntities] when truncated. */
    val totalMatches: Int = 0,
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
 * Drives the dashboard editor: a working copy of the whole layout (not written to
 * [dashboardLayoutStore] until [save]) joined with a search index of every known entity. Only the
 * first view's entity tiles are editable for now; other views and non-entity tiles (spacers, view
 * links) are carried through [save] untouched, since nothing creates them yet.
 *
 * Real installs have thousands of entities whose states change every second, so the editor works on
 * a lightweight [EntityIndex] that is only rebuilt when entities are added, removed or renamed, and
 * shows at most [MAX_AVAILABLE_RESULTS] matches: the user narrows them with search and filters.
 */
class EditorViewModel(
    private val haRepository: HaRepository,
    private val dashboardLayoutStore: DashboardLayoutStore,
    private val idProvider: DashboardIdProvider = UuidDashboardIdProvider,
) : ViewModel() {

    private val _workingLayout = MutableStateFlow(defaultDashboardLayout())
    private val _query = MutableStateFlow("")
    private val _domainFilter = MutableStateFlow<String?>(null)
    private val _floorFilter = MutableStateFlow<String?>(null)
    private val _areaFilter = MutableStateFlow<String?>(null)
    private val _isLoaded = MutableStateFlow(false)

    /**
     * Edits applied to [_workingLayout] before [dashboardLayoutStore] finished its initial load.
     * Replayed on top of the loaded layout once it arrives so they aren't lost, without discarding
     * whatever was already stored on disk. Each edit re-resolves the first view's id from whichever
     * layout it's applied to, since the placeholder [defaultDashboardLayout] above and the real
     * loaded layout have different view ids.
     */
    private val pendingEditsBeforeLoad = mutableListOf<(DashboardLayout) -> DashboardLayout>()

    init {
        viewModelScope.launch {
            val loaded = dashboardLayoutStore.layout.first()
            _workingLayout.value = pendingEditsBeforeLoad.fold(loaded) { layout, edit -> edit(layout) }
            pendingEditsBeforeLoad.clear()
            _isLoaded.value = true
        }
    }

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

    private var lastIndex = EntityIndex(emptyList())

    /** Emits a new index only when entities are added, removed or renamed; state changes are ignored. */
    private val entityIndex: Flow<EntityIndex> = haRepository.entities
        .map(::indexFor)
        .distinctUntilChanged { old, new -> old === new }

    val uiState: StateFlow<EditorUiState> = combine(
        _workingLayout,
        entityIndex,
        filters,
        registryLookup,
        _isLoaded,
    ) { layout, index, filters, lookup, isLoaded ->
        buildUiState(layout, index, filters, lookup, isLoaded)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUiState())

    /** Reuses the previous index (same instance) unless the entity set or a friendly name changed. */
    private fun indexFor(entities: Map<String, HaEntity>): EntityIndex {
        val previous = lastIndex
        val unchanged = previous.items.size == entities.size &&
            previous.items.all { item -> entities[item.entityId]?.friendlyName == item.friendlyName }
        if (unchanged) return previous
        val items = entities.values
            .map { EntityIndexItem(it.entityId, it.friendlyName, it.domain) }
            .sortedBy { it.nameKey }
        return EntityIndex(items).also { lastIndex = it }
    }

    /** Applies [edit] to the working layout now, and again once a pending initial load arrives. */
    private fun editWorkingLayout(edit: (DashboardLayout) -> DashboardLayout) {
        _workingLayout.update(edit)
        if (!_isLoaded.value) {
            pendingEditsBeforeLoad += edit
        }
    }

    private fun buildUiState(
        layout: DashboardLayout,
        index: EntityIndex,
        filters: Filters,
        lookup: RegistryLookup,
        isLoaded: Boolean,
    ): EditorUiState {
        val registry = lookup.registry
        val entityTiles = layout.views.firstOrNull()?.tiles.orEmpty()
            .mapNotNull { tile -> (tile.content as? TileContent.Entity) }
        val tileIds = entityTiles.mapTo(HashSet()) { it.entityId }
        val currentTiles = entityTiles.map { entity ->
            EditorTileRow(
                entityId = entity.entityId,
                friendlyName = index.byId[entity.entityId]?.friendlyName ?: entity.entityId,
                label = entity.label,
            )
        }
        val query = filters.query.trim().lowercase()
        val matches = index.items.asSequence().filter { it.matches(query, filters, lookup) }
        val totalMatches = matches.count()
        val availableEntities = matches
            .take(MAX_AVAILABLE_RESULTS)
            .map { item ->
                val area = registry.entityAreas[item.entityId]?.let { lookup.areaById[it] }
                val floor = area?.floorId?.let { lookup.floorById[it] }
                EditorAvailableRow(
                    entityId = item.entityId,
                    friendlyName = item.friendlyName,
                    domain = item.domain,
                    alreadyAdded = item.entityId in tileIds,
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

        return EditorUiState(
            currentTiles = currentTiles,
            availableEntities = availableEntities,
            totalMatches = totalMatches,
            domains = index.domains,
            floors = registry.floors,
            areas = visibleAreas,
            query = filters.query,
            domainFilter = filters.domainFilter,
            floorFilter = filters.floorFilter,
            areaFilter = filters.areaFilter,
            isLoaded = isLoaded,
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

    /** Appends a new entity tile to the first view, unless that entity is already a tile there. */
    fun addTile(entityId: String) {
        editWorkingLayout { layout ->
            val view = layout.views.firstOrNull() ?: return@editWorkingLayout layout
            val alreadyAdded = view.tiles.any { (it.content as? TileContent.Entity)?.entityId == entityId }
            if (alreadyAdded) return@editWorkingLayout layout
            layout.addTile(view.id, newDashboardTile(TileContent.Entity(entityId), idProvider = idProvider))
        }
    }

    fun removeTile(entityId: String) {
        editFirstViewEntityTile(entityId) { layout, view, tileId -> layout.removeTile(view.id, tileId) }
    }

    fun setLabel(entityId: String, label: String) {
        val trimmed = label.trim().ifEmpty { null }
        editFirstViewEntityTile(entityId) { layout, view, tileId ->
            layout.updateTile(view.id, tileId) { tile ->
                when (val content = tile.content) {
                    is TileContent.Entity -> tile.copy(content = content.copy(label = trimmed))
                    else -> tile
                }
            }
        }
    }

    fun moveUp(entityId: String) {
        moveEntityTile(entityId, delta = -1)
    }

    fun moveDown(entityId: String) {
        moveEntityTile(entityId, delta = +1)
    }

    private fun moveEntityTile(entityId: String, delta: Int) {
        editWorkingLayout { layout ->
            val view = layout.views.firstOrNull() ?: return@editWorkingLayout layout
            val index = view.tiles.indexOfFirst { (it.content as? TileContent.Entity)?.entityId == entityId }
            if (index < 0) return@editWorkingLayout layout
            layout.moveTile(view.id, index, index + delta)
        }
    }

    /** Finds the tile for [entityId] in the first view (if any) and applies [edit] to the layout. */
    private fun editFirstViewEntityTile(
        entityId: String,
        edit: (layout: DashboardLayout, view: DashboardView, tileId: String) -> DashboardLayout,
    ) {
        editWorkingLayout { layout ->
            val view = layout.views.firstOrNull() ?: return@editWorkingLayout layout
            val tileId = view.tiles.firstOrNull { (it.content as? TileContent.Entity)?.entityId == entityId }?.id
                ?: return@editWorkingLayout layout
            edit(layout, view, tileId)
        }
    }

    /** Persists the working layout and invokes [onSaved]. */
    fun save(onSaved: () -> Unit) {
        viewModelScope.launch {
            dashboardLayoutStore.update { _workingLayout.value }
            onSaved()
        }
    }

    companion object {
        /** Sentinel [EditorUiState.areaFilter] value selecting entities with no assigned area. */
        const val NO_AREA_ID = "__no_area__"

        /** Rows shown in the "add" list; the rest is reached by narrowing the search or filters. */
        const val MAX_AVAILABLE_RESULTS = 50

        fun factory(haRepository: HaRepository, dashboardLayoutStore: DashboardLayoutStore) = viewModelFactory {
            initializer { EditorViewModel(haRepository, dashboardLayoutStore) }
        }
    }
}

/** [HaRegistry] plus its area/floor id lookups, recomputed only when the registry changes. */
private data class RegistryLookup(
    val registry: HaRegistry,
    val areaById: Map<String, HaArea>,
    val floorById: Map<String, HaFloor>,
)

/** Search-relevant fields of one entity, with lowercase keys precomputed once per index build. */
private class EntityIndexItem(val entityId: String, val friendlyName: String, val domain: String) {
    val nameKey: String = friendlyName.lowercase()
    val idKey: String = entityId.lowercase()
}

/** Entities sorted by name, plus lookups derived from them. Compared by identity, never by content. */
private class EntityIndex(val items: List<EntityIndexItem>) {
    val byId: Map<String, EntityIndexItem> = items.associateBy { it.entityId }
    val domains: List<String> = items.mapTo(HashSet()) { it.domain }.sorted()
}
