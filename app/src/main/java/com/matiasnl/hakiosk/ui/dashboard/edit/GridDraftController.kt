package com.matiasnl.hakiosk.ui.dashboard.edit

import com.matiasnl.hakiosk.data.dashboard.CELL_RANGE
import com.matiasnl.hakiosk.data.dashboard.DashboardGrid
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPacker
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPacking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class GridDraftState(val columns: Int, val rows: Int, val packing: GridPacking) {
    val canDecreaseColumns: Boolean get() = columns > CELL_RANGE.first
    val canIncreaseColumns: Boolean get() = columns < CELL_RANGE.last
    val canDecreaseRows: Boolean get() = rows > CELL_RANGE.first
    val canIncreaseRows: Boolean get() = rows < CELL_RANGE.last
}

/**
 * Local (non-persisted) draft of a view's grid size, for the grid-settings modal that opens from
 * inside edit mode. Unlike the old `ViewSettingsViewModel` this never talks to the store: the modal
 * that owns one of these already has the working copy's grid and tiles in memory (edit mode loaded
 * them synchronously), and applying just calls back into [com.matiasnl.hakiosk.ui.dashboard.DashboardViewModel.setEditGrid].
 *
 * [previewTiles] is fixed for the controller's lifetime (the modal is recreated if the working
 * copy's tile list changes while it's open, which can't happen today since no other UI can touch it
 * at the same time).
 */
class GridDraftController(private val previewTiles: List<PreviewTile>, initialGrid: DashboardGrid) {
    private val packer = GridPacker()
    private val _state = MutableStateFlow(build(initialGrid.columns.coerceIn(CELL_RANGE), initialGrid.rows.coerceIn(CELL_RANGE)))
    val state: StateFlow<GridDraftState> = _state.asStateFlow()

    fun onColumnsChange(delta: Int) {
        _state.update { current ->
            val columns = (current.columns + delta).coerceIn(CELL_RANGE)
            if (columns == current.columns) current else build(columns, current.rows)
        }
    }

    fun onRowsChange(delta: Int) {
        _state.update { current ->
            val rows = (current.rows + delta).coerceIn(CELL_RANGE)
            if (rows == current.rows) current else current.copy(rows = rows)
        }
    }

    fun currentGrid(): DashboardGrid = state.value.let { DashboardGrid(columns = it.columns, rows = it.rows) }

    private fun build(columns: Int, rows: Int): GridDraftState =
        GridDraftState(columns = columns, rows = rows, packing = packer.pack(columns, previewTiles, { it.colSpan }, { it.rowSpan }))
}
