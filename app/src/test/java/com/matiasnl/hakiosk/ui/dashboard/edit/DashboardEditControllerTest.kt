package com.matiasnl.hakiosk.ui.dashboard.edit

import com.matiasnl.hakiosk.data.dashboard.DashboardGrid
import com.matiasnl.hakiosk.data.dashboard.DashboardLayout
import com.matiasnl.hakiosk.data.dashboard.DashboardTile
import com.matiasnl.hakiosk.data.dashboard.DashboardView
import com.matiasnl.hakiosk.data.dashboard.FakeDashboardIdProvider
import com.matiasnl.hakiosk.data.dashboard.InMemoryDashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.TileContent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardEditControllerTest {

    private val layout = DashboardLayout(
        views = listOf(
            DashboardView(
                id = "main",
                name = "Principal",
                grid = DashboardGrid(columns = 3, rows = 2),
                tiles = listOf(
                    DashboardTile("a", TileContent.Entity("light.a"), colSpan = 1, rowSpan = 1),
                    DashboardTile("b", TileContent.Spacer),
                ),
            ),
            DashboardView(id = "other", name = "Otra"),
        ),
    )

    private fun controller(
        store: InMemoryDashboardLayoutStore = InMemoryDashboardLayoutStore(layout),
        idProvider: FakeDashboardIdProvider = FakeDashboardIdProvider(),
    ) = Triple(DashboardEditController(store, idProvider), store, idProvider)

    @Test
    fun `enter snapshots the layout into both original and working`() {
        val (controller, _, _) = controller()

        controller.enter(layout, "main")

        val state = controller.state.value
        assertTrue(state.isEditing)
        assertEquals("main", state.editingViewId)
        assertEquals(layout, state.original)
        assertEquals(layout, state.working)
        assertFalse(state.isDirty)
    }

    @Test
    fun `link targets exclude the current view`() {
        val (controller, _, _) = controller()
        controller.enter(layout, "main")

        assertEquals(listOf(LinkTargetOption("other", "Otra")), controller.state.value.linkTargets)
    }

    @Test
    fun `add entity, spacer and link tiles append to the working copy`() {
        val (controller, _, _) = controller()
        controller.enter(layout, "main")

        controller.addEntityTile("light.new")
        controller.addSpacerTile()
        controller.addLinkTile("other")

        val tiles = controller.state.value.editingView!!.tiles
        assertEquals(5, tiles.size)
        assertEquals(TileContent.Entity("light.new"), tiles[2].content)
        assertEquals(TileContent.Spacer, tiles[3].content)
        assertEquals(TileContent.ViewLink("other"), tiles[4].content)
        assertTrue(controller.state.value.isDirty)
    }

    @Test
    fun `set label overrides entity and link tiles, blank clears it back to default`() {
        val (controller, _, _) = controller()
        controller.enter(layout, "main")

        controller.setLabel("a", "Living room")
        assertEquals(
            TileContent.Entity("light.a", "Living room"),
            controller.state.value.editingView!!.tiles.first { it.id == "a" }.content,
        )

        controller.setLabel("a", "   ")
        assertEquals(
            TileContent.Entity("light.a", null),
            controller.state.value.editingView!!.tiles.first { it.id == "a" }.content,
        )
    }

    @Test
    fun `resize clamps width to the view's columns but not the store's cell range`() {
        val (controller, _, _) = controller()
        controller.enter(layout, "main")

        controller.resizeTile("a", colSpan = 8, rowSpan = 5)

        val tile = controller.state.value.editingView!!.tiles.first { it.id == "a" }
        assertEquals(3, tile.colSpan) // Clamped to the view's 3 columns, not the store's 12.
        assertEquals(5, tile.rowSpan) // Height isn't clamped to columns.
    }

    @Test
    fun `remove drops the tile from the working copy`() {
        val (controller, _, _) = controller()
        controller.enter(layout, "main")

        controller.removeTile("b")

        assertEquals(listOf("a"), controller.state.value.editingView!!.tiles.map { it.id })
    }

    @Test
    fun `grid change applies to the working copy's view only`() {
        val (controller, _, _) = controller()
        controller.enter(layout, "main")

        controller.setGrid(DashboardGrid(columns = 6, rows = 4))

        assertEquals(DashboardGrid(columns = 6, rows = 4), controller.state.value.editingView!!.grid)
        assertEquals(DashboardGrid(columns = 4, rows = 3), controller.state.value.working!!.views[1].grid) // untouched
    }

    @Test
    fun `move reorders the working copy and clamps the target to the last real tile`() {
        val (controller, store, _) = controller()
        controller.enter(layout, "main")
        controller.addSpacerTile() // id-1 -> tiles a, b, id-1

        controller.moveTile(0, 2)
        assertEquals(listOf("b", "id-1", "a"), controller.state.value.editingView!!.tiles.map { it.id })

        controller.moveTile(0, 99) // Past the end (e.g. the "＋" tile's index): clamped to the last real tile.
        assertEquals(listOf("id-1", "a", "b"), controller.state.value.editingView!!.tiles.map { it.id })

        controller.moveTile(2, -5)
        assertEquals(listOf("b", "id-1", "a"), controller.state.value.editingView!!.tiles.map { it.id })
        assertEquals(layout, store.layout.value) // Nothing persisted.
    }

    @Test
    fun `move ignores an out-of-range source such as the add tile, and same-index moves stay clean`() {
        val (controller, _, _) = controller()
        controller.enter(layout, "main")

        controller.moveTile(2, 0) // Index 2 is the synthetic "＋" in the UI; not a real tile.
        controller.moveTile(-1, 0)
        controller.moveTile(1, 1)
        controller.moveTile(1, 7) // Clamps to 1, its own index.

        assertEquals(listOf("a", "b"), controller.state.value.editingView!!.tiles.map { it.id })
        assertFalse(controller.state.value.isDirty)
    }

    @Test
    fun `move outside edit mode is a no-op`() {
        val (controller, _, _) = controller()

        controller.moveTile(0, 1)

        assertNull(controller.state.value.working)
    }

    @Test
    fun `cancel discards the working copy`() {
        val (controller, store, _) = controller()
        controller.enter(layout, "main")
        controller.addSpacerTile()

        controller.cancel()

        assertFalse(controller.state.value.isEditing)
        assertNull(controller.state.value.working)
        assertEquals(layout, store.layout.value)
    }

    @Test
    fun `done persists exactly once with the working copy and exits edit mode`() = runTest {
        val (controller, store, _) = controller()
        controller.enter(layout, "main")
        controller.addSpacerTile()
        val expected = controller.state.value.working

        controller.done()

        assertEquals(expected, store.layout.value)
        assertFalse(controller.state.value.isEditing)
    }

    @Test
    fun `isDirty is false until an edit is made and true afterwards`() {
        val (controller, _, _) = controller()
        controller.enter(layout, "main")
        assertFalse(controller.state.value.isDirty)

        controller.addSpacerTile()
        assertTrue(controller.state.value.isDirty)
    }
}
