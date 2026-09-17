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
    fun `tap action round-trips and the default is not written`() {
        val layout = DashboardLayout(
            views = listOf(
                DashboardView(
                    id = "v1",
                    name = "Principal",
                    tiles = listOf(
                        DashboardTile("a", TileContent.Entity("light.a")),
                        DashboardTile("b", TileContent.Entity("light.b", tapAction = TileTapAction.OPEN_DETAILS)),
                        DashboardTile("c", TileContent.Entity("light.c", tapAction = TileTapAction.TOGGLE)),
                    ),
                ),
            ),
        )

        val json = DashboardLayoutJsonMapper.encode(layout)
        val decoded = DashboardLayoutJsonMapper.decode(newFormatJson = json, legacyTilesJson = null)

        assertEquals(layout, decoded.layout)
        assertTrue(json.contains("\"tapAction\":\"open_details\""))
        assertTrue(json.contains("\"tapAction\":\"toggle\""))
        assertEquals(2, Regex("tapAction").findAll(json).count())
    }

    @Test
    fun `tile style round-trips, the default is not written, and an unknown style falls back to DEFAULT`() {
        val layout = DashboardLayout(
            views = listOf(
                DashboardView(
                    id = "v1",
                    name = "Principal",
                    tiles = listOf(
                        DashboardTile("a", TileContent.Entity("climate.a")),
                        DashboardTile("b", TileContent.Entity("climate.b", style = TileStyle.QUICK_ADJUST)),
                    ),
                ),
            ),
        )

        val json = DashboardLayoutJsonMapper.encode(layout)
        val decoded = DashboardLayoutJsonMapper.decode(newFormatJson = json, legacyTilesJson = null)

        assertEquals(layout, decoded.layout)
        assertTrue(json.contains("\"style\":\"quick_adjust\""))
        assertEquals(1, Regex("\"style\"").findAll(json).count())

        val fromNewerApp = json.replace("quick_adjust", "hologram")
        val tiles = DashboardLayoutJsonMapper.decode(newFormatJson = fromNewerApp, legacyTilesJson = null).layout.views.single().tiles
        assertEquals(listOf("a", "b"), tiles.map { it.id })
        assertEquals(TileStyle.DEFAULT, (tiles[1].content as TileContent.Entity).style)
    }

    @Test
    fun `camera options round-trip and a tile with no options writes no camera key`() {
        val layout = DashboardLayout(
            views = listOf(
                DashboardView(
                    id = "v1",
                    name = "Principal",
                    tiles = listOf(
                        DashboardTile("a", TileContent.Entity("camera.a")),
                        DashboardTile(
                            "b",
                            TileContent.Entity(
                                "camera.b",
                                camera = CameraTileOptions(focusStream = "main", thumbnailLive = true, thumbnailStream = "sub"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val json = DashboardLayoutJsonMapper.encode(layout)
        val decoded = DashboardLayoutJsonMapper.decode(newFormatJson = json, legacyTilesJson = null)

        assertEquals(layout, decoded.layout)
        // Only "b" has non-default camera options; "a" (null, the default) writes no "camera" key at all.
        assertEquals(1, Regex("\"camera\"").findAll(json).count())
    }

    @Test
    fun `stored json from before the camera field existed decodes as null`() {
        val stageJson = """
            {"version":1,"views":[{"id":"v1","name":"Principal","tiles":[
              {"id":"t1","content":{"type":"entity","entityId":"camera.front_door"}}
            ]}]}
        """.trimIndent()

        val decoded = DashboardLayoutJsonMapper.decode(newFormatJson = stageJson, legacyTilesJson = null)

        assertEquals(
            TileContent.Entity("camera.front_door", camera = null),
            decoded.layout.views.single().tiles.single().content,
        )
    }

    @Test
    fun `unknown extra keys inside camera options don't lose the layout`() {
        val json = """
            {"version":1,"views":[{"id":"v1","name":"Principal","tiles":[
              {"id":"t1","content":{"type":"entity","entityId":"camera.front_door","camera":{"focusStream":"main","futureField":"???"}}}
            ]}]}
        """.trimIndent()

        val decoded = DashboardLayoutJsonMapper.decode(newFormatJson = json, legacyTilesJson = null)

        assertEquals(
            TileContent.Entity("camera.front_door", camera = CameraTileOptions(focusStream = "main")),
            decoded.layout.views.single().tiles.single().content,
        )
    }

    @Test
    fun `stored json from before the tap action existed decodes as DEFAULT`() {
        val stage5Json = """
            {"version":1,"views":[{"id":"v1","name":"Principal","grid":{"columns":4,"rows":3},"tiles":[
              {"id":"t1","content":{"type":"entity","entityId":"light.kitchen","label":"Cocina"},"colSpan":2,"rowSpan":1},
              {"id":"t2","content":{"type":"entity","entityId":"switch.fan"}},
              {"id":"t3","content":{"type":"spacer"}},
              {"id":"t4","content":{"type":"view_link","targetViewId":"v2"}}
            ]}]}
        """.trimIndent()

        val decoded = DashboardLayoutJsonMapper.decode(newFormatJson = stage5Json, legacyTilesJson = null)

        val tiles = decoded.layout.views.single().tiles
        assertEquals(listOf("t1", "t2", "t3", "t4"), tiles.map { it.id })
        assertEquals(TileContent.Entity("light.kitchen", "Cocina", TileTapAction.DEFAULT), tiles[0].content)
        assertEquals(TileTapAction.DEFAULT, (tiles[1].content as TileContent.Entity).tapAction)
        assertEquals(TileContent.Spacer, tiles[2].content)
        assertFalse(decoded.shouldPersist)
    }

    @Test
    fun `an unknown tap action written by a newer app falls back to DEFAULT without losing the layout`() {
        val json = """
            {"version":1,"views":[{"id":"v1","name":"Principal","tiles":[
              {"id":"t1","content":{"type":"entity","entityId":"light.kitchen","tapAction":"double_tap_magic"}}
            ]}]}
        """.trimIndent()

        val decoded = DashboardLayoutJsonMapper.decode(newFormatJson = json, legacyTilesJson = null)

        assertEquals("v1", decoded.layout.views.single().id)
        assertEquals(TileContent.Entity("light.kitchen"), decoded.layout.views.single().tiles.single().content)
    }

    @Test
    fun `decode with nothing stored returns a default layout that is not marked for persisting`() {
        val decoded = DashboardLayoutJsonMapper.decode(newFormatJson = null, legacyTilesJson = null, FakeDashboardIdProvider())

        assertEquals(PRINCIPAL_VIEW_NAME, decoded.layout.views.single().name)
        assertTrue(decoded.layout.views.single().tiles.isEmpty())
        assertFalse(decoded.shouldPersist)
    }

    @Test
    fun `default layout keeps the same view id across decodes so edits by view id still apply`() {
        val first = DashboardLayoutJsonMapper.decode(newFormatJson = null, legacyTilesJson = null)
        val second = DashboardLayoutJsonMapper.decode(newFormatJson = null, legacyTilesJson = null)
        val corrupt = DashboardLayoutJsonMapper.decode(newFormatJson = "{not json", legacyTilesJson = null)

        assertEquals(DEFAULT_VIEW_ID, first.layout.views.single().id)
        assertEquals(first.layout, second.layout)
        assertEquals(DEFAULT_VIEW_ID, corrupt.layout.views.single().id)

        // What the store does inside update(): rebuild the default, then apply an edit addressed by id.
        val tile = newDashboardTile(TileContent.Spacer, idProvider = FakeDashboardIdProvider())
        val edited = second.layout.addTile(first.layout.views.single().id, tile)
        assertEquals(listOf(tile), edited.views.single().tiles)
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
    fun `encode always writes the format version, and still omits every other default`() {
        val layout = DashboardLayout(
            views = listOf(
                DashboardView(
                    id = "v1",
                    name = "Principal",
                    tiles = listOf(DashboardTile("t1", TileContent.Entity("light.kitchen"))),
                ),
            ),
        )

        val json = DashboardLayoutJsonMapper.encode(layout)

        // Without this, a future version couldn't tell an old file from one it wrote itself.
        assertTrue(json.contains("\"version\":1"))
        // Only the version is forced: the tile's default spans and tap action stay out of the file.
        assertFalse(json.contains("colSpan"))
        assertFalse(json.contains("tapAction"))
        assertEquals(layout, DashboardLayoutJsonMapper.decode(json, null).layout)
    }

    @Test
    fun `decode reports whether the stored layout could actually be read`() {
        val stored = DashboardLayoutJsonMapper.encode(DashboardLayout(listOf(DashboardView("v1", "Principal"))))

        // Real stored data, and a fresh install: both are the user's real current state.
        assertTrue(DashboardLayoutJsonMapper.decode(stored, null).isReadable)
        assertTrue(DashboardLayoutJsonMapper.decode(null, null).isReadable)
        assertTrue(DashboardLayoutJsonMapper.decode(null, """[{"entityId":"light.a"}]""").isReadable)

        // Something IS stored and we couldn't read it: the default layout below is only a stand-in.
        assertFalse(DashboardLayoutJsonMapper.decode("{not valid json", null).isReadable)
        assertFalse(DashboardLayoutJsonMapper.decode("""{"version":1,"views":[]}""", null).isReadable)
        assertFalse(DashboardLayoutJsonMapper.decode("""{"version":99,"views":[{"id":"v","name":"V"}]}""", null).isReadable)
        assertFalse(DashboardLayoutJsonMapper.decode(null, "not an array").isReadable)
    }

    @Test
    fun `decodeStrict tells an unreadable layout apart from a readable one, unlike decode`() {
        val layout = DashboardLayout(views = listOf(DashboardView(id = "v1", name = "Principal")))
        val json = DashboardLayoutJsonMapper.encode(layout)

        assertEquals(layout, DashboardLayoutJsonMapper.decodeStrict(json))
        assertNull(DashboardLayoutJsonMapper.decodeStrict("{not valid json"))
        assertNull(DashboardLayoutJsonMapper.decodeStrict("""{"version":1,"views":[]}"""))
        // A format a future version writes: readable as JSON, but its meaning is anyone's guess.
        assertNull(DashboardLayoutJsonMapper.decodeStrict("""{"version":99,"views":[{"id":"v1","name":"P"}]}"""))
        // decode() keeps falling back instead, and still never asks the store to persist that fallback.
        val fallback = DashboardLayoutJsonMapper.decode("""{"version":99,"views":[{"id":"v1","name":"P"}]}""", null)
        assertEquals(DEFAULT_VIEW_ID, fallback.layout.views.single().id)
        assertFalse(fallback.shouldPersist)
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
