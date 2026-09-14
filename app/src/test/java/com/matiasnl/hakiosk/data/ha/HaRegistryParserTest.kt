package com.matiasnl.hakiosk.data.ha

import com.matiasnl.hakiosk.data.ha.ws.HaRegistryParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaRegistryParserTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun floorsSortByLevelNullsLastThenName() {
        val floors = HaRegistryParser.parseFloors(
            json(
                """[
                {"floor_id":"up","name":"Upstairs","level":1,"icon":null,"aliases":[]},
                {"floor_id":"attic","name":"Attic","level":null},
                {"floor_id":"ground","name":"ground","level":0},
                {"floor_id":"annex","name":"Annex","level":0},
                {"floor_id":"basement","name":"Basement","level":-1},
                {"name":"no id"},
                "garbage"
                ]""",
            ),
        )!!
        assertEquals(listOf("basement", "annex", "ground", "up", "attic"), floors.map { it.floorId })
        assertNull(floors.last().level)
        assertEquals(-1, floors.first().level)
    }

    @Test
    fun areasSortByNameAndKeepFloor() {
        val areas = HaRegistryParser.parseAreas(
            json(
                """[
                {"area_id":"kitchen","name":"Kitchen","floor_id":"ground","picture":null,"labels":[]},
                {"area_id":"bath","name":"bathroom"},
                {"area_id":"garage","name":"Garage","floor_id":null}
                ]""",
            ),
        )!!
        assertEquals(
            listOf(HaArea("bath", "bathroom", null), HaArea("garage", "Garage", null), HaArea("kitchen", "Kitchen", "ground")),
            areas,
        )
    }

    @Test
    fun entityAreasFromDisplayFormatPreferEntityAreaOverDeviceArea() {
        val devices = HaRegistryParser.parseDeviceAreas(
            json("""[{"id":"d1","area_id":"kitchen","name":"Hub"},{"id":"d2","area_id":null},{"area_id":"x"}]"""),
        )!!
        assertEquals(mapOf("d1" to "kitchen"), devices)

        val entityAreas = HaRegistryParser.parseEntityAreas(
            json(
                """{"entity_categories":{"0":"config"},"entities":[
                {"ei":"light.kitchen","pl":"hue","di":"d1"},
                {"ei":"sensor.temp","ai":"living","di":"d1","en":"Temp"},
                {"ei":"switch.no_area","di":"d2"},
                {"ei":"switch.orphan"},
                {"ei":"light.unknown_device","di":"d9"}
                ]}""",
            ),
            devices,
        )
        assertEquals(mapOf("light.kitchen" to "kitchen", "sensor.temp" to "living"), entityAreas)
    }

    @Test
    fun entityAreasFromFullListFormatSkipDisabled() {
        val entityAreas = HaRegistryParser.parseEntityAreas(
            json(
                """[
                {"entity_id":"light.a","area_id":"hall","device_id":null,"disabled_by":null},
                {"entity_id":"light.b","area_id":null,"device_id":"d1"},
                {"entity_id":"light.c","area_id":"hall","disabled_by":"user"}
                ]""",
            ),
            mapOf("d1" to "kitchen"),
        )
        assertEquals(mapOf("light.a" to "hall", "light.b" to "kitchen"), entityAreas)
    }

    @Test
    fun unexpectedShapesReturnNull() {
        assertNull(HaRegistryParser.parseFloors(json("""{"floors":[]}""")))
        assertNull(HaRegistryParser.parseAreas(json("null")))
        assertNull(HaRegistryParser.parseDeviceAreas(json("\"x\"")))
        assertNull(HaRegistryParser.parseEntityAreas(json("""{"other":[]}"""), emptyMap()))
    }
}
