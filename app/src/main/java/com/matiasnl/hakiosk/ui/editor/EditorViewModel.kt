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
import com.matiasnl.hakiosk.data.ha.HaRepository
import com.matiasnl.hakiosk.ui.picker.EntityIndex
import com.matiasnl.hakiosk.ui.picker.EntityPickerState
import com.matiasnl.hakiosk.ui.picker.entityIndexFlow
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

data class EditorUiState(
    val currentTiles: List<EditorTileRow> = emptyList(),
    /** False until the stored tile list has finished loading once. */
    val isLoaded: Boolean = false,
)

/**
 * Drives the dashboard editor: a working copy of the whole layout (not written to
 * [dashboardLayoutStore] until [save]). Only the first view's entity tiles are editable for now;
 * other views and non-entity tiles (spacers, view links) are carried through [save] untouched,
 * since nothing creates them yet.
 *
 * Entity search/filtering to add a tile is delegated to [picker]; this class only tracks which
 * entity ids are already tiles (so the picker can flag them) and applies picks to the layout.
 */
class EditorViewModel(
    private val haRepository: HaRepository,
    private val dashboardLayoutStore: DashboardLayoutStore,
    private val idProvider: DashboardIdProvider = UuidDashboardIdProvider,
) : ViewModel() {

    private val _workingLayout = MutableStateFlow(defaultDashboardLayout())
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

    /** Ids of the first view's entity tiles, kept in sync with [_workingLayout] for [picker]. */
    private val currentEntityIds: Flow<Set<String>> = _workingLayout
        .map { layout ->
            layout.views.firstOrNull()?.tiles.orEmpty()
                .mapNotNullTo(HashSet()) { (it.content as? TileContent.Entity)?.entityId }
        }
        .distinctUntilChanged()

    /** Entity search/filter state for the "add entities" section of [com.matiasnl.hakiosk.ui.editor.EditorScreen]. */
    val picker = EntityPickerState(
        scope = viewModelScope,
        haRepository = haRepository,
        alreadyAddedIds = currentEntityIds,
    )

    /** Same rebuild-only-on-add/remove/rename index [picker] uses, for the current tiles' friendly names. */
    private val entityIndex: Flow<EntityIndex> = entityIndexFlow(haRepository.entities)

    val uiState: StateFlow<EditorUiState> = combine(
        _workingLayout,
        entityIndex,
        _isLoaded,
    ) { layout, index, isLoaded ->
        buildUiState(layout, index, isLoaded)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUiState())

    /** Applies [edit] to the working layout now, and again once a pending initial load arrives. */
    private fun editWorkingLayout(edit: (DashboardLayout) -> DashboardLayout) {
        _workingLayout.update(edit)
        if (!_isLoaded.value) {
            pendingEditsBeforeLoad += edit
        }
    }

    private fun buildUiState(layout: DashboardLayout, index: EntityIndex, isLoaded: Boolean): EditorUiState {
        val entityTiles = layout.views.firstOrNull()?.tiles.orEmpty()
            .mapNotNull { tile -> (tile.content as? TileContent.Entity) }
        val currentTiles = entityTiles.map { entity ->
            EditorTileRow(
                entityId = entity.entityId,
                friendlyName = index.byId[entity.entityId]?.friendlyName ?: entity.entityId,
                label = entity.label,
            )
        }
        return EditorUiState(currentTiles = currentTiles, isLoaded = isLoaded)
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
        fun factory(haRepository: HaRepository, dashboardLayoutStore: DashboardLayoutStore) = viewModelFactory {
            initializer { EditorViewModel(haRepository, dashboardLayoutStore) }
        }
    }
}
