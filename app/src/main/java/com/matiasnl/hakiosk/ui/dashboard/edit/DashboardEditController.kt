package com.matiasnl.hakiosk.ui.dashboard.edit

import com.matiasnl.hakiosk.data.dashboard.CELL_RANGE
import com.matiasnl.hakiosk.data.dashboard.DashboardGrid
import com.matiasnl.hakiosk.data.dashboard.DashboardIdProvider
import com.matiasnl.hakiosk.data.dashboard.DashboardLayout
import com.matiasnl.hakiosk.data.dashboard.DashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.DashboardView
import com.matiasnl.hakiosk.data.dashboard.TileContent
import com.matiasnl.hakiosk.data.dashboard.UuidDashboardIdProvider
import com.matiasnl.hakiosk.data.dashboard.addTile
import com.matiasnl.hakiosk.data.dashboard.moveTile
import com.matiasnl.hakiosk.data.dashboard.newDashboardTile
import com.matiasnl.hakiosk.data.dashboard.removeTile
import com.matiasnl.hakiosk.data.dashboard.setGrid
import com.matiasnl.hakiosk.data.dashboard.updateTile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A candidate target for a "link to view" tile: every view except the one being edited. */
data class LinkTargetOption(val viewId: String, val name: String)

/**
 * Edit-mode snapshot of the dashboard: a working copy of the whole layout plus which view is being
 * edited. Outside edit mode every field is null/false/empty.
 */
data class DashboardEditState(
    val isEditing: Boolean = false,
    val editingViewId: String? = null,
    /** Layout as last persisted, captured by [DashboardEditController.enter]. Null outside edit mode. */
    val original: DashboardLayout? = null,
    /** Layout edited so far; only persisted by [DashboardEditController.done]. Null outside edit mode. */
    val working: DashboardLayout? = null,
) {
    val isDirty: Boolean get() = working != null && working != original

    /** The view being edited, or null outside edit mode. */
    val editingView: DashboardView? get() = working?.views?.firstOrNull { it.id == editingViewId }

    /** Other views a "link to view" tile in [editingView] could target, in the working copy's view order. */
    val linkTargets: List<LinkTargetOption>
        get() = working?.views.orEmpty()
            .filter { it.id != editingViewId }
            .map { LinkTargetOption(it.id, it.name) }
}

/**
 * Owns the working copy of the dashboard layout while the dashboard is in edit mode.
 *
 * [enter] snapshots the layout passed to it (the dashboard shows only its first view for now, so the
 * caller decides which view id is being edited). Every mutator below applies to the working copy
 * only; nothing reaches [layoutStore] until [done], which writes the working copy in a single
 * [DashboardLayoutStore.update] call and exits edit mode. [cancel] discards the working copy without
 * writing anything.
 *
 * Drag & drop reorder goes through [moveTile]: the owning ViewModel's repack-on-layout-change
 * plumbing (keyed on the working copy, not on entity updates) redraws the new order with no other
 * wiring. Hit-testing lives in the grid (`GridDragMath`), not here.
 */
class DashboardEditController(
    private val layoutStore: DashboardLayoutStore,
    private val idProvider: DashboardIdProvider = UuidDashboardIdProvider,
) {
    private val _state = MutableStateFlow(DashboardEditState())
    val state: StateFlow<DashboardEditState> = _state.asStateFlow()

    /** Snapshots [layout] as the working copy and enters edit mode on [viewId]. */
    fun enter(layout: DashboardLayout, viewId: String) {
        _state.value = DashboardEditState(isEditing = true, editingViewId = viewId, original = layout, working = layout)
    }

    /** Discards the working copy and exits edit mode. */
    fun cancel() {
        _state.value = DashboardEditState()
    }

    /** Persists the working copy in one store update and exits edit mode. No-op outside edit mode. */
    suspend fun done() {
        val working = _state.value.working ?: return
        layoutStore.update { working }
        _state.value = DashboardEditState()
    }

    fun addEntityTile(entityId: String) = addTile(TileContent.Entity(entityId))

    fun addSpacerTile() = addTile(TileContent.Spacer)

    fun addLinkTile(targetViewId: String) = addTile(TileContent.ViewLink(targetViewId))

    private fun addTile(content: TileContent) = mutate { layout, viewId ->
        layout.addTile(viewId, newDashboardTile(content, idProvider = idProvider))
    }

    /** A blank/null [label] clears the override (falls back to the entity/view's own name). */
    fun setLabel(tileId: String, label: String?) = mutate { layout, viewId ->
        val trimmed = label?.trim()?.ifEmpty { null }
        layout.updateTile(viewId, tileId) { tile ->
            when (val content = tile.content) {
                is TileContent.Entity -> tile.copy(content = content.copy(label = trimmed))
                is TileContent.ViewLink -> tile.copy(content = content.copy(label = trimmed))
                TileContent.Spacer -> tile
            }
        }
    }

    /**
     * Resizes a tile. [rowSpan] is clamped to [CELL_RANGE] by the underlying pure op; [colSpan] is
     * clamped here to the editing view's current column count (the store itself allows up to 12, but
     * a tile wider than the view can never be fully visible).
     */
    fun resizeTile(tileId: String, colSpan: Int, rowSpan: Int) = mutate { layout, viewId ->
        val columns = layout.views.firstOrNull { it.id == viewId }?.grid?.columns ?: CELL_RANGE.last
        val clampedColSpan = colSpan.coerceIn(1, columns.coerceIn(CELL_RANGE))
        layout.updateTile(viewId, tileId) { it.copy(colSpan = clampedColSpan, rowSpan = rowSpan) }
    }

    fun removeTile(tileId: String) = mutate { layout, viewId -> layout.removeTile(viewId, tileId) }

    fun setGrid(grid: DashboardGrid) = mutate { layout, viewId -> layout.setGrid(viewId, grid) }

    /**
     * Moves the tile at [fromIndex] to [toIndex] in the edited view's real tiles (the synthetic "＋"
     * tile is not part of the working copy, so it never counts). An out-of-range [fromIndex] (e.g. the
     * "＋" tile's index) is ignored; [toIndex] is clamped to the last real tile. Moving a tile onto its
     * own index leaves the working copy untouched, so it doesn't mark the session dirty.
     */
    fun moveTile(fromIndex: Int, toIndex: Int) = mutate { layout, viewId ->
        val tiles = layout.views.firstOrNull { it.id == viewId }?.tiles.orEmpty()
        if (fromIndex !in tiles.indices || toIndex.coerceIn(0, tiles.lastIndex) == fromIndex) {
            layout
        } else {
            layout.moveTile(viewId, fromIndex, toIndex)
        }
    }

    private fun mutate(transform: (DashboardLayout, viewId: String) -> DashboardLayout) {
        _state.update { state ->
            val working = state.working ?: return@update state
            val viewId = state.editingViewId ?: return@update state
            state.copy(working = transform(working, viewId))
        }
    }
}
