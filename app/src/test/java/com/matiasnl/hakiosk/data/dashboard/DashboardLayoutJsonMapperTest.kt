package com.matiasnl.hakiosk.data.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLayoutJsonMapperTest {

    @Test
    fun `encode then decode round-trips a layout`() {
        val idProvider = FakeDashboardIdProvider()
        val original = DashboardLayout(
            views = listOf(
                DashboardView(
                    id = "v1",
                    name = "Principal",
                    grid = DashboardGrid(columns = 5, rows = 3),
                    tiles = listOf(
                        newDashboardTile(TileContent.Entity("light.kitchen", "Cocina"), idProvider = idProvider),
                        newDashboardTile(TileContent.Spacer, idProvider = idProvider),
                        newDashboardTile(TileContent.ViewLink("v2", "Go"), idProvider = idProvider),
                    ),
                ),
            ),
        )

        val json = DashboardLayoutJsonMapper.encode(original)
        val decoded = DashboardLayoutJsonMapper.decode(newFormatJson = json, legacyTilesJson = null)

        assertEquals(original, decoded.layout)
        assertFalse(decoded.shouldPersist)
    }

    @Test
    fun `decode with nothing stored returns a default layout that is not marked for persisting`() {
        val decoded = DashboardLayoutJsonMapper.decode(newFormatJson = null, legacyTilesJson = null, FakeDashboardIdProvider())

        assertEquals(PRINCIPAL_VIEW_NAME, decoded.layout.views.single().name)
        assertTrue(decoded.layout.views.single().tiles.isEmpty())
        assertFalse(decoded.shouldPersist)
    }

    @Test
    fun `decode migrates the legacy tile list into one Principal view, in order, with labels kept`() {
        val legacyJson = """[{"entityId":"light.kitchen"},{"entityId":"light.living_room","label":"Salón"}]"""

        val decoded = DashboardLayoutJsonMapper.decode(
            newFormatJson = null,
            legacyTilesJson = legacyJson,
            idProvider = FakeDashboardIdProvider(),
        )

        assertTrue(decoded.shouldPersist)
        val view = decoded.layout.views.single()
        assertEquals(PRINCIPAL_VIEW_NAME, view.name)
        val entities = view.tiles.map { it.content as TileContent.Entity }
        assertEquals(listOf("light.kitchen", "light.living_room"), entities.map { it.entityId })
        assertNull(entities[0].label)
        assertEquals("Salón", entities[1].label)
        // Fresh ids, and every migrated tile is 1x1.
        assertEquals(view.tiles.map { it.id }.distinct().size, view.tiles.size)
        assertTrue(view.tiles.all { it.colSpan == 1 && it.rowSpan == 1 })
    }

    @Test
    fun `decode prefers the new format over a leftover legacy key`() {
        val idProvider = FakeDashboardIdProvider()
        val newLayout = DashboardLayout(views = listOf(DashboardView(id = "v1", name = "Principal")))
        val newJson = DashboardLayoutJsonMapper.encode(newLayout)
        val legacyJson = """[{"entityId":"light.kitchen"}]"""

        val decoded = DashboardLayoutJsonMapper.decode(newJson, legacyJson, idProvider)

        assertEquals(newLayout, decoded.layout)
        assertFalse(decoded.shouldPersist)
    }

    @Test
    fun `decode falls back to a default layout on corrupt new-format json without persisting it`() {
        val decoded = DashboardLayoutJsonMapper.decode(
            newFormatJson = "{not valid json",
            legacyTilesJson = null,
            idProvider = FakeDashboardIdProvider(),
        )

        assertEquals(PRINCIPAL_VIEW_NAME, decoded.layout.views.single().name)
        assertFalse(decoded.shouldPersist)
    }

    @Test
    fun `decode falls back to a default layout when the new format has no views`() {
        val emptyViewsJson = """{"version":1,"views":[]}"""

        val decoded = DashboardLayoutJsonMapper.decode(emptyViewsJson, legacyTilesJson = null, idProvider = FakeDashboardIdProvider())

        assertEquals(PRINCIPAL_VIEW_NAME, decoded.layout.views.single().name)
        assertFalse(decoded.shouldPersist)
    }

    @Test
    fun `decode falls back to a default layout on corrupt legacy json without persisting it`() {
        val decoded = DashboardLayoutJsonMapper.decode(
            newFormatJson = null,
            legacyTilesJson = "not an array",
            idProvider = FakeDashboardIdProvider(),
        )

        assertEquals(PRINCIPAL_VIEW_NAME, decoded.layout.views.single().name)
        assertTrue(decoded.layout.views.single().tiles.isEmpty())
        assertFalse(decoded.shouldPersist)
    }
}
