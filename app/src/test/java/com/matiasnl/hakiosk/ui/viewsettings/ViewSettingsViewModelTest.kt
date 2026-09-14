package com.matiasnl.hakiosk.ui.viewsettings

import com.matiasnl.hakiosk.data.dashboard.DashboardGrid
import com.matiasnl.hakiosk.data.dashboard.DashboardLayout
import com.matiasnl.hakiosk.data.dashboard.DashboardTile
import com.matiasnl.hakiosk.data.dashboard.DashboardView
import com.matiasnl.hakiosk.data.dashboard.InMemoryDashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.TileContent
import com.matiasnl.hakiosk.ui.MainDispatcherRule
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPlacement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ViewSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val layout = DashboardLayout(
        views = listOf(
            DashboardView(id = "other", name = "Otra", grid = DashboardGrid(columns = 2, rows = 2)),
            DashboardView(
                id = "main",
                name = "Principal",
                grid = DashboardGrid(columns = 4, rows = 3),
                tiles = listOf(
                    DashboardTile("a", TileContent.Entity("light.a"), colSpan = 2),
                    DashboardTile("b", TileContent.Spacer),
                    DashboardTile("c", TileContent.Entity("light.c"), colSpan = 2),
                ),
            ),
        ),
    )

    private fun viewModel(store: InMemoryDashboardLayoutStore = InMemoryDashboardLayoutStore(layout), viewId: String = "main") =
        ViewSettingsViewModel(viewId, store)

    @Test
    fun `loads the view's grid, name and packed preview`() {
        val state = viewModel().uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.viewExists)
        assertEquals("Principal", state.viewName)
        assertEquals(DashboardGrid(4, 3), state.grid)
        assertEquals(
            listOf(PreviewTile(2, 1, false), PreviewTile(1, 1, true), PreviewTile(2, 1, false)),
            state.previewTiles,
        )
        assertEquals(listOf(GridPlacement(0, 0, 2, 1), GridPlacement(0, 2, 1, 1), GridPlacement(1, 0, 2, 1)), state.packing.placements)
    }

    @Test
    fun `steppers change the working copy and repack on column changes`() {
        val vm = viewModel()
        vm.onColumnsChange(+1)
        vm.onRowsChange(-1)

        val state = vm.uiState.value
        assertEquals(DashboardGrid(columns = 5, rows = 2), state.grid)
        assertEquals(5, state.packing.columns)
        assertEquals(listOf(GridPlacement(0, 0, 2, 1), GridPlacement(0, 2, 1, 1), GridPlacement(0, 3, 2, 1)), state.packing.placements)
    }

    @Test
    fun `steppers clamp to the cell range`() {
        val vm = viewModel()
        repeat(20) { vm.onColumnsChange(-1) }
        repeat(20) { vm.onRowsChange(+1) }

        val state = vm.uiState.value
        assertEquals(DashboardGrid(columns = 1, rows = 12), state.grid)
        assertFalse(state.canDecreaseColumns)
        assertTrue(state.canIncreaseColumns)
        assertTrue(state.canDecreaseRows)
        assertFalse(state.canIncreaseRows)
        // One column: spans clipped, everything stacked.
        assertEquals(3, state.packing.totalRows)

        repeat(20) { vm.onColumnsChange(+1) }
        assertEquals(12, vm.uiState.value.grid.columns)
        assertFalse(vm.uiState.value.canIncreaseColumns)
    }

    @Test
    fun `cancel does not persist the working copy`() {
        val store = InMemoryDashboardLayoutStore(layout)
        val vm = viewModel(store)
        vm.onColumnsChange(+2)

        vm.onCancel()

        assertTrue(vm.uiState.value.isFinished)
        assertEquals(layout, store.layout.value)
    }

    @Test
    fun `done persists only the edited view's grid and finishes`() {
        val store = InMemoryDashboardLayoutStore(layout)
        val vm = viewModel(store)
        vm.onColumnsChange(+2)
        vm.onRowsChange(+1)

        vm.onDone()

        assertTrue(vm.uiState.value.isFinished)
        val views = store.layout.value.views
        assertEquals(DashboardGrid(columns = 6, rows = 4), views.single { it.id == "main" }.grid)
        assertEquals(DashboardGrid(columns = 2, rows = 2), views.single { it.id == "other" }.grid)
        assertEquals(layout.views[1].tiles, views.single { it.id == "main" }.tiles)
    }

    @Test
    fun `later layout changes do not overwrite the working copy`() = runTest {
        val store = InMemoryDashboardLayoutStore(layout)
        val vm = viewModel(store)
        vm.onColumnsChange(+1)

        store.update { current -> current.copy(views = current.views.map { it.copy(name = "Renombrada") }) }

        assertEquals(5, vm.uiState.value.grid.columns)
        assertEquals("Principal", vm.uiState.value.viewName)
    }

    @Test
    fun `missing view is flagged and done closes without writing`() {
        val store = InMemoryDashboardLayoutStore(layout)
        val vm = viewModel(store, viewId = "gone")
        assertFalse(vm.uiState.value.viewExists)

        vm.onColumnsChange(+1)
        vm.onDone()

        assertTrue(vm.uiState.value.isFinished)
        assertEquals(layout, store.layout.value)
    }
}
