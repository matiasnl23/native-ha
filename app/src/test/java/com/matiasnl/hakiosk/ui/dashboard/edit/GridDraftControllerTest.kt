package com.matiasnl.hakiosk.ui.dashboard.edit

import com.matiasnl.hakiosk.data.dashboard.DashboardGrid
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GridDraftControllerTest {

    private val tiles = listOf(PreviewTile(2, 1), PreviewTile(1, 1, isSpacer = true), PreviewTile(2, 1))

    @Test
    fun `starts from the initial grid and packs the preview tiles`() {
        val controller = GridDraftController(tiles, DashboardGrid(columns = 4, rows = 3))

        val state = controller.state.value
        assertEquals(4, state.columns)
        assertEquals(3, state.rows)
        assertEquals(
            listOf(GridPlacement(0, 0, 2, 1), GridPlacement(0, 2, 1, 1), GridPlacement(1, 0, 2, 1)),
            state.packing.placements,
        )
    }

    @Test
    fun `column changes repack, row changes don't`() {
        val controller = GridDraftController(tiles, DashboardGrid(columns = 4, rows = 3))
        val packingBeforeRowChange = controller.state.value.packing

        controller.onRowsChange(-1)
        assertSame(packingBeforeRowChange, controller.state.value.packing)
        assertEquals(2, controller.state.value.rows)

        controller.onColumnsChange(+1)
        assertEquals(5, controller.state.value.columns)
        assertEquals(
            listOf(GridPlacement(0, 0, 2, 1), GridPlacement(0, 2, 1, 1), GridPlacement(0, 3, 2, 1)),
            controller.state.value.packing.placements,
        )
    }

    @Test
    fun `steppers clamp to the cell range`() {
        val controller = GridDraftController(tiles, DashboardGrid(columns = 1, rows = 1))
        repeat(5) { controller.onColumnsChange(-1) }
        repeat(5) { controller.onRowsChange(-1) }

        assertEquals(1, controller.state.value.columns)
        assertEquals(1, controller.state.value.rows)
        assertFalse(controller.state.value.canDecreaseColumns)
        assertFalse(controller.state.value.canDecreaseRows)

        repeat(20) { controller.onColumnsChange(+1) }
        repeat(20) { controller.onRowsChange(+1) }
        assertEquals(12, controller.state.value.columns)
        assertEquals(12, controller.state.value.rows)
        assertTrue(controller.state.value.canDecreaseColumns)
        assertFalse(controller.state.value.canIncreaseColumns)
    }

    @Test
    fun `currentGrid reflects the latest columns and rows`() {
        val controller = GridDraftController(tiles, DashboardGrid(columns = 4, rows = 3))
        controller.onColumnsChange(+2)
        controller.onRowsChange(-1)

        assertEquals(DashboardGrid(columns = 6, rows = 2), controller.currentGrid())
    }
}
