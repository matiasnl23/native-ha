package com.matiasnl.hakiosk.ui.viewsettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.matiasnl.hakiosk.data.dashboard.CELL_RANGE
import com.matiasnl.hakiosk.data.dashboard.DashboardGrid
import com.matiasnl.hakiosk.data.dashboard.DashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.TileContent
import com.matiasnl.hakiosk.data.dashboard.setGrid
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPacker
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPacking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A tile as drawn in the grid preview: only its size and whether it's an (invisible) spacer. */
data class PreviewTile(val colSpan: Int, val rowSpan: Int, val isSpacer: Boolean)

data class ViewSettingsUiState(
    val isLoading: Boolean = true,
    /** False when the view no longer exists (deleted while this screen was open in the back stack). */
    val viewExists: Boolean = true,
    val viewName: String = "",
    /** Working copy; only persisted by [ViewSettingsViewModel.onDone]. */
    val grid: DashboardGrid = DashboardGrid(),
    val previewTiles: List<PreviewTile> = emptyList(),
    /** [previewTiles] packed with the working [grid]'s columns. */
    val packing: GridPacking = GridPacking.Empty,
    val isSaving: Boolean = false,
    /** Set once Done finished saving or Cancel was pressed: the screen should close. */
    val isFinished: Boolean = false,
) {
    val canDecreaseColumns: Boolean get() = grid.columns > CELL_RANGE.first
    val canIncreaseColumns: Boolean get() = grid.columns < CELL_RANGE.last
    val canDecreaseRows: Boolean get() = grid.rows > CELL_RANGE.first
    val canIncreaseRows: Boolean get() = grid.rows < CELL_RANGE.last
}

/**
 * Edits one view's visible columns/rows on a working copy, with a live packing preview of its tiles.
 * Nothing is written until [onDone]; [onCancel] just closes.
 */
class ViewSettingsViewModel(
    private val viewId: String,
    private val layoutStore: DashboardLayoutStore,
) : ViewModel() {

    private val packer = GridPacker()
    private val _uiState = MutableStateFlow(ViewSettingsUiState())
    val uiState: StateFlow<ViewSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Snapshot once: later layout changes must not overwrite the user's working copy.
            val view = layoutStore.layout.first().views.firstOrNull { it.id == viewId }
            _uiState.update { state ->
                if (view == null) {
                    state.copy(isLoading = false, viewExists = false)
                } else {
                    val tiles = view.tiles.map { PreviewTile(it.colSpan, it.rowSpan, it.content is TileContent.Spacer) }
                    state.copy(
                        isLoading = false,
                        viewName = view.name,
                        grid = view.grid,
                        previewTiles = tiles,
                        packing = pack(view.grid.columns, tiles),
                    )
                }
            }
        }
    }

    fun onColumnsChange(delta: Int) = updateGrid { it.copy(columns = (it.columns + delta).coerceIn(CELL_RANGE)) }

    fun onRowsChange(delta: Int) = updateGrid { it.copy(rows = (it.rows + delta).coerceIn(CELL_RANGE)) }

    fun onDone() {
        val state = _uiState.value
        if (state.isSaving || state.isFinished) return
        if (state.isLoading || !state.viewExists) {
            onCancel()
            return
        }
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            layoutStore.update { it.setGrid(viewId, state.grid) }
            _uiState.update { it.copy(isSaving = false, isFinished = true) }
        }
    }

    fun onCancel() {
        _uiState.update { if (it.isSaving) it else it.copy(isFinished = true) }
    }

    private fun updateGrid(transform: (DashboardGrid) -> DashboardGrid) {
        _uiState.update { state ->
            if (state.isLoading || state.isSaving) return@update state
            val grid = transform(state.grid)
            if (grid == state.grid) return@update state
            val packing = if (grid.columns == state.grid.columns) state.packing else pack(grid.columns, state.previewTiles)
            state.copy(grid = grid, packing = packing)
        }
    }

    private fun pack(columns: Int, tiles: List<PreviewTile>): GridPacking =
        packer.pack(columns, tiles, { it.colSpan }, { it.rowSpan })

    companion object {
        fun factory(viewId: String, layoutStore: DashboardLayoutStore) = viewModelFactory {
            initializer { ViewSettingsViewModel(viewId, layoutStore) }
        }
    }
}
