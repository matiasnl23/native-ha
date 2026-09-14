package com.matiasnl.hakiosk.data.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun view(id: String, name: String = id, tiles: List<DashboardTile> = emptyList(), grid: DashboardGrid = DashboardGrid()) =
    DashboardView(id = id, name = name, grid = grid, tiles = tiles)

private fun entityTile(id: String, entityId: String, colSpan: Int = 1, rowSpan: Int = 1) =
    DashboardTile(id = id, content = TileContent.Entity(entityId), colSpan = colSpan, rowSpan = rowSpan)

class DashboardLayoutOperationsTest {

    // -- addTile --------------------------------------------------------------------------------

    @Test
    fun `addTile appends at the end by default`() {
        val layout = DashboardLayout(views = listOf(view("v1", tiles = listOf(entityTile("t1", "light.a")))))

        val result = layout.addTile("v1", entityTile("t2", "light.b"))

        assertEquals(listOf("t1", "t2"), result.views.single().tiles.map { it.id })
    }

    @Test
    fun `addTile inserts at the given index`() {
        val layout = DashboardLayout(views = listOf(view("v1", tiles = listOf(entityTile("t1", "light.a")))))

        val result = layout.addTile("v1", entityTile("t2", "light.b"), index = 0)

        assertEquals(listOf("t2", "t1"), result.views.single().tiles.map { it.id })
    }

    @Test
    fun `addTile clamps spans of the inserted tile`() {
        val layout = DashboardLayout(views = listOf(view("v1")))

        val result = layout.addTile("v1", entityTile("t1", "light.a", colSpan = 99, rowSpan = 0))

        val tile = result.views.single().tiles.single()
        assertEquals(12, tile.colSpan)
        assertEquals(1, tile.rowSpan)
    }

    @Test
    fun `addTile on unknown view id is a no-op`() {
        val layout = DashboardLayout(views = listOf(view("v1")))

        val result = layout.addTile("unknown", entityTile("t1", "light.a"))

        assertSame(layout, result)
    }

    // -- updateTile -------------------------------------------------------------------------------

    @Test
    fun `updateTile transforms the matching tile only`() {
        val layout = DashboardLayout(
            views = listOf(view("v1", tiles = listOf(entityTile("t1", "light.a"), entityTile("t2", "light.b")))),
        )

        val result = layout.updateTile("v1", "t1") { it.copy(colSpan = 2) }

        val tiles = result.views.single().tiles
        assertEquals(2, tiles.first { it.id == "t1" }.colSpan)
        assertEquals(1, tiles.first { it.id == "t2" }.colSpan)
    }

    @Test
    fun `updateTile clamps spans set by the transform`() {
        val layout = DashboardLayout(views = listOf(view("v1", tiles = listOf(entityTile("t1", "light.a")))))

        val result = layout.updateTile("v1", "t1") { it.copy(colSpan = 50, rowSpan = -3) }

        val tile = result.views.single().tiles.single()
        assertEquals(12, tile.colSpan)
        assertEquals(1, tile.rowSpan)
    }

    @Test
    fun `updateTile with unknown tile id is a no-op`() {
        val layout = DashboardLayout(views = listOf(view("v1", tiles = listOf(entityTile("t1", "light.a")))))

        val result = layout.updateTile("v1", "unknown") { it.copy(colSpan = 5) }

        assertEquals(layout, result)
    }

    // -- removeTile -------------------------------------------------------------------------------

    @Test
    fun `removeTile drops the matching tile`() {
        val layout = DashboardLayout(
            views = listOf(view("v1", tiles = listOf(entityTile("t1", "light.a"), entityTile("t2", "light.b")))),
        )

        val result = layout.removeTile("v1", "t1")

        assertEquals(listOf("t2"), result.views.single().tiles.map { it.id })
    }

    @Test
    fun `removeTile with unknown tile id is a no-op`() {
        val layout = DashboardLayout(views = listOf(view("v1", tiles = listOf(entityTile("t1", "light.a")))))

        val result = layout.removeTile("v1", "unknown")

        assertEquals(layout, result)
    }

    // -- moveTile ---------------------------------------------------------------------------------

    @Test
    fun `moveTile reorders within the view`() {
        val layout = DashboardLayout(
            views = listOf(
                view(
                    "v1",
                    tiles = listOf(entityTile("t1", "a"), entityTile("t2", "b"), entityTile("t3", "c")),
                ),
            ),
        )

        val result = layout.moveTile("v1", fromIndex = 0, toIndex = 2)

        assertEquals(listOf("t2", "t3", "t1"), result.views.single().tiles.map { it.id })
    }

    @Test
    fun `moveTile clamps an out-of-range target instead of crashing`() {
        val layout = DashboardLayout(
            views = listOf(view("v1", tiles = listOf(entityTile("t1", "a"), entityTile("t2", "b")))),
        )

        val result = layout.moveTile("v1", fromIndex = 0, toIndex = 99)

        assertEquals(listOf("t2", "t1"), result.views.single().tiles.map { it.id })
    }

    @Test
    fun `moveTile with out-of-range fromIndex is a no-op`() {
        val layout = DashboardLayout(views = listOf(view("v1", tiles = listOf(entityTile("t1", "a")))))

        val result = layout.moveTile("v1", fromIndex = 5, toIndex = 0)

        assertEquals(layout, result)
    }

    // -- addView / renameView ----------------------------------------------------------------------

    @Test
    fun `addView appends a new empty view and returns its id`() {
        val layout = DashboardLayout(views = listOf(view("v1")))
        val idProvider = FakeDashboardIdProvider()

        val (result, newId) = layout.addView("Bedroom", idProvider)

        assertEquals(listOf("v1", newId), result.views.map { it.id })
        assertEquals("Bedroom", result.views.last().name)
        assertTrue(result.views.last().tiles.isEmpty())
    }

    @Test
    fun `renameView renames only the matching view`() {
        val layout = DashboardLayout(views = listOf(view("v1", name = "Principal"), view("v2", name = "Old")))

        val result = layout.renameView("v2", "Bedroom")

        assertEquals("Principal", result.views[0].name)
        assertEquals("Bedroom", result.views[1].name)
    }

    @Test
    fun `renameView on unknown id is a no-op`() {
        val layout = DashboardLayout(views = listOf(view("v1")))

        val result = layout.renameView("unknown", "New name")

        assertEquals(layout, result)
    }

    // -- removeView -------------------------------------------------------------------------------

    @Test
    fun `removeView drops the matching view`() {
        val layout = DashboardLayout(views = listOf(view("v1"), view("v2")))

        val result = layout.removeView("v2")

        assertEquals(listOf("v1"), result.views.map { it.id })
    }

    @Test
    fun `removeView refuses to drop the last remaining view`() {
        val layout = DashboardLayout(views = listOf(view("v1")))

        val result = layout.removeView("v1")

        assertEquals(layout, result)
    }

    @Test
    fun `removeView drops view-link tiles that pointed at the removed view`() {
        val linkTile = DashboardTile(id = "link", content = TileContent.ViewLink(targetViewId = "v2"))
        val otherLinkTile = DashboardTile(id = "other-link", content = TileContent.ViewLink(targetViewId = "v1"))
        val layout = DashboardLayout(
            views = listOf(
                view("v1", tiles = listOf(linkTile, otherLinkTile)),
                view("v2"),
            ),
        )

        val result = layout.removeView("v2")

        assertEquals(listOf("other-link"), result.views.single().tiles.map { it.id })
    }

    @Test
    fun `removeView on unknown id is a no-op`() {
        val layout = DashboardLayout(views = listOf(view("v1"), view("v2")))

        val result = layout.removeView("unknown")

        assertEquals(layout, result)
    }

    // -- moveView ---------------------------------------------------------------------------------

    @Test
    fun `moveView reorders views`() {
        val layout = DashboardLayout(views = listOf(view("v1"), view("v2"), view("v3")))

        val result = layout.moveView(fromIndex = 2, toIndex = 0)

        assertEquals(listOf("v3", "v1", "v2"), result.views.map { it.id })
    }

    @Test
    fun `moveView clamps an out-of-range target`() {
        val layout = DashboardLayout(views = listOf(view("v1"), view("v2")))

        val result = layout.moveView(fromIndex = 0, toIndex = 99)

        assertEquals(listOf("v2", "v1"), result.views.map { it.id })
    }

    @Test
    fun `moveView with out-of-range fromIndex is a no-op`() {
        val layout = DashboardLayout(views = listOf(view("v1")))

        val result = layout.moveView(fromIndex = 5, toIndex = 0)

        assertEquals(layout, result)
    }

    // -- setGrid ----------------------------------------------------------------------------------

    @Test
    fun `setGrid replaces the view's grid`() {
        val layout = DashboardLayout(views = listOf(view("v1")))

        val result = layout.setGrid("v1", DashboardGrid(columns = 6, rows = 4))

        assertEquals(DashboardGrid(columns = 6, rows = 4), result.views.single().grid)
    }

    @Test
    fun `setGrid clamps columns and rows`() {
        val layout = DashboardLayout(views = listOf(view("v1")))

        val result = layout.setGrid("v1", DashboardGrid(columns = 99, rows = 0))

        assertEquals(DashboardGrid(columns = 12, rows = 1), result.views.single().grid)
    }

    @Test
    fun `setGrid on unknown view id is a no-op`() {
        val layout = DashboardLayout(views = listOf(view("v1")))

        val result = layout.setGrid("unknown", DashboardGrid(columns = 6, rows = 4))

        assertEquals(layout, result)
    }

    // -- defaultDashboardLayout / newDashboardTile -------------------------------------------------

    @Test
    fun `defaultDashboardLayout has one empty Principal view`() {
        val layout = defaultDashboardLayout(FakeDashboardIdProvider())

        val singleView = layout.views.single()
        assertEquals(PRINCIPAL_VIEW_NAME, singleView.name)
        assertTrue(singleView.tiles.isEmpty())
    }

    @Test
    fun `newDashboardTile clamps spans and assigns a fresh id`() {
        val idProvider = FakeDashboardIdProvider()

        val tile = newDashboardTile(TileContent.Entity("light.a"), colSpan = -1, rowSpan = 20, idProvider = idProvider)

        assertEquals("id-1", tile.id)
        assertEquals(1, tile.colSpan)
        assertEquals(12, tile.rowSpan)
        assertNull((tile.content as TileContent.Entity).label)
    }
}
