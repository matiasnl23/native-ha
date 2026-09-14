package com.matiasnl.hakiosk.ui.remote

import com.matiasnl.hakiosk.data.dashboard.DashboardLayout
import com.matiasnl.hakiosk.data.dashboard.DashboardView
import com.matiasnl.hakiosk.data.dashboard.TileContent
import com.matiasnl.hakiosk.data.dashboard.newDashboardTile
import com.matiasnl.hakiosk.data.device.CameraOption
import com.matiasnl.hakiosk.data.device.ViewOption
import com.matiasnl.hakiosk.data.ha.HaEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

private fun entity(id: String, friendlyName: String) = HaEntity(
    entityId = id,
    state = "idle",
    attributes = JsonObject(mapOf("friendly_name" to JsonPrimitive(friendlyName))),
    lastChanged = "2026-01-01T00:00:00+00:00",
)

class DeviceUiStateMapperTest {

    @Test
    fun `viewOptionsFrom lists every view in order`() {
        val layout = DashboardLayout(
            views = listOf(DashboardView("v1", "Principal"), DashboardView("v2", "Planta alta")),
        )

        assertEquals(listOf(ViewOption("v1", "Principal"), ViewOption("v2", "Planta alta")), viewOptionsFrom(layout))
    }

    @Test
    fun `cameraOptionsFrom collects camera tiles across every view, de-duplicated`() {
        val layout = DashboardLayout(
            views = listOf(
                DashboardView(
                    "v1",
                    "Principal",
                    tiles = listOf(
                        newDashboardTile(TileContent.Entity("camera.front_door")),
                        newDashboardTile(TileContent.Entity("light.living_room")),
                    ),
                ),
                DashboardView(
                    "v2",
                    "Patio",
                    tiles = listOf(
                        newDashboardTile(TileContent.Entity("camera.front_door")), // duplicate, ignored
                        newDashboardTile(TileContent.Entity("camera.backyard", label = "Fondo")),
                    ),
                ),
            ),
        )
        val entities = mapOf(
            "camera.front_door" to entity("camera.front_door", "Puerta de entrada"),
        )

        val cameras = cameraOptionsFrom(layout, entities)

        assertEquals(
            listOf(
                CameraOption("camera.front_door", "Puerta de entrada"),
                CameraOption("camera.backyard", "Fondo"),
            ),
            cameras,
        )
    }

    @Test
    fun `cameraOptionsFrom falls back to the entity id when there is no label or known entity`() {
        val layout = DashboardLayout(
            views = listOf(
                DashboardView("v1", "Principal", tiles = listOf(newDashboardTile(TileContent.Entity("camera.gone")))),
            ),
        )

        assertEquals(listOf(CameraOption("camera.gone", "camera.gone")), cameraOptionsFrom(layout, emptyMap()))
    }

    @Test
    fun `non-camera tiles and view links are ignored`() {
        val layout = DashboardLayout(
            views = listOf(
                DashboardView(
                    "v1",
                    "Principal",
                    tiles = listOf(
                        newDashboardTile(TileContent.Entity("light.living_room")),
                        newDashboardTile(TileContent.ViewLink("v1")),
                        newDashboardTile(TileContent.Spacer),
                    ),
                ),
            ),
        )

        assertEquals(emptyList<CameraOption>(), cameraOptionsFrom(layout, emptyMap()))
    }
}
