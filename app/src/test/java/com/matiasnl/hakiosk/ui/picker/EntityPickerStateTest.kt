package com.matiasnl.hakiosk.ui.picker

import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.HaRegistry
import com.matiasnl.hakiosk.data.ha.fake.FakeHaRepository
import com.matiasnl.hakiosk.data.ha.fake.sampleEntities
import com.matiasnl.hakiosk.data.ha.fake.sampleRegistry
import com.matiasnl.hakiosk.ui.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private fun entity(id: String, name: String) = HaEntity(
    entityId = id,
    state = "on",
    attributes = JsonObject(mapOf("friendly_name" to JsonPrimitive(name))),
    lastChanged = "2026-01-01T00:00:00+00:00",
)

class EntityPickerStateTest {

    // EntityPickerState.uiState is built with stateIn(scope, ...): the sharing coroutine that
    // actually runs the combine() pipeline lives in the scope passed to the constructor, mirroring
    // how EditorViewModel passes viewModelScope (Dispatchers.Main.immediate) in production. Give it
    // an unconfined Main dispatcher here so filter/query changes are reflected in uiState.value
    // synchronously, without a manual advanceUntilIdle() after every intent call.
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun TestScope.pickerFor(
        entities: List<HaEntity>,
        registry: HaRegistry = sampleRegistry(),
        alreadyAddedIds: Flow<Set<String>> = flowOf(emptySet()),
    ): Pair<EntityPickerState, FakeHaRepository> {
        val repository = FakeHaRepository(initialEntities = entities, initialRegistry = registry)
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val picker = EntityPickerState(scope, repository, alreadyAddedIds)
        backgroundScope.launch(Dispatchers.Main) { picker.uiState.collect {} }
        return picker to repository
    }

    private fun manyEntities(count: Int) = (0 until count).map { entity("light.e%03d".format(it), "Entity %03d".format(it)) }

    @Test
    fun `available list is capped and reports the total number of matches`() = runTest {
        val (picker, _) = pickerFor(manyEntities(120))

        val state = picker.uiState.value
        assertEquals(EntityPickerState.MAX_RESULTS, state.results.size)
        assertEquals(120, state.totalMatches)
        assertEquals("Entity 000", state.results.first().friendlyName)
    }

    @Test
    fun `search narrows matches below the cap`() = runTest {
        val (picker, _) = pickerFor(manyEntities(120))

        picker.onQueryChange("entity 11")

        val state = picker.uiState.value
        assertEquals(10, state.totalMatches)
        assertEquals((110..119).map { "light.e$it" }, state.results.map { it.entityId })
    }

    @Test
    fun `entity state changes do not rebuild the picker state`() = runTest {
        val (picker, repository) = pickerFor(listOf(entity("light.a", "A"), entity("light.b", "B")))
        val before = picker.uiState.value

        repository.callService("light", "toggle", "light.a")

        assertEquals("off", repository.entities.value.getValue("light.a").state)
        assertTrue(before === picker.uiState.value)
    }

    @Test
    fun `search filters by name and entity id`() = runTest {
        val (picker, _) = pickerFor(listOf(entity("light.kitchen", "Kitchen"), entity("light.living_room", "Living room")))

        picker.onQueryChange("kitchen")

        assertEquals(listOf("light.kitchen"), picker.uiState.value.results.map { it.entityId })
    }

    @Test
    fun `domain filter narrows available entities`() = runTest {
        val (picker, _) = pickerFor(listOf(entity("light.kitchen", "Kitchen"), entity("switch.pump", "Pump")))

        picker.onDomainFilterChange("switch")

        assertEquals(listOf("switch.pump"), picker.uiState.value.results.map { it.entityId })
    }

    @Test
    fun `alreadyAdded follows the provided id flow`() = runTest {
        val alreadyAddedIds = MutableStateFlow<Set<String>>(emptySet())
        val (picker, _) = pickerFor(
            entities = listOf(entity("light.kitchen", "Kitchen"), entity("light.living_room", "Living room")),
            alreadyAddedIds = alreadyAddedIds,
        )

        assertFalse(picker.uiState.value.results.first { it.entityId == "light.kitchen" }.alreadyAdded)

        alreadyAddedIds.value = setOf("light.kitchen")

        assertTrue(picker.uiState.value.results.first { it.entityId == "light.kitchen" }.alreadyAdded)
        assertFalse(picker.uiState.value.results.first { it.entityId == "light.living_room" }.alreadyAdded)
    }

    // sampleRegistry(): floors "ground" (level 0) and "first" (level 1); areas entrance/kitchen/
    // living_room on "ground" and bedroom (empty) on "first"; sensor.outdoor_temperature unassigned.
    private fun TestScope.pickerWithSampleRegistry(): EntityPickerState =
        pickerFor(sampleEntities()).first

    @Test
    fun `floor filter matches entities whose area is on that floor`() = runTest {
        val picker = pickerWithSampleRegistry()

        picker.onFloorFilterChange("ground")

        assertEquals(
            setOf("light.living_room", "light.kitchen", "switch.coffee_maker", "camera.front_door", "scene.movie_night"),
            picker.uiState.value.results.map { it.entityId }.toSet(),
        )
    }

    @Test
    fun `floor with only empty areas yields no available entities`() = runTest {
        val picker = pickerWithSampleRegistry()

        picker.onFloorFilterChange("first")

        assertTrue(picker.uiState.value.results.isEmpty())
    }

    @Test
    fun `area choices are restricted to the selected floor`() = runTest {
        val picker = pickerWithSampleRegistry()

        picker.onFloorFilterChange("ground")
        assertEquals(
            setOf("entrance", "kitchen", "living_room"),
            picker.uiState.value.areas.map { it.areaId }.toSet(),
        )

        picker.onFloorFilterChange("first")
        assertEquals(setOf("bedroom"), picker.uiState.value.areas.map { it.areaId }.toSet())

        picker.onFloorFilterChange(null)
        assertEquals(
            setOf("bedroom", "entrance", "kitchen", "living_room"),
            picker.uiState.value.areas.map { it.areaId }.toSet(),
        )
    }

    @Test
    fun `area filter narrows to that area's entities`() = runTest {
        val picker = pickerWithSampleRegistry()

        picker.onAreaFilterChange("kitchen")

        assertEquals(
            setOf("light.kitchen", "switch.coffee_maker"),
            picker.uiState.value.results.map { it.entityId }.toSet(),
        )
    }

    @Test
    fun `no-area filter matches only unassigned entities`() = runTest {
        val picker = pickerWithSampleRegistry()

        picker.onAreaFilterChange(EntityPickerState.NO_AREA_ID)

        assertEquals(
            listOf("sensor.outdoor_temperature"),
            picker.uiState.value.results.map { it.entityId },
        )
    }

    @Test
    fun `floor area domain and search filters combine with AND`() = runTest {
        val picker = pickerWithSampleRegistry()

        picker.onFloorFilterChange("ground")
        picker.onAreaFilterChange("kitchen")
        picker.onDomainFilterChange("switch")
        picker.onQueryChange("coffee")

        assertEquals(
            listOf("switch.coffee_maker"),
            picker.uiState.value.results.map { it.entityId },
        )

        // Narrowing the search further to something that doesn't match empties the result.
        picker.onQueryChange("nonexistent")
        assertTrue(picker.uiState.value.results.isEmpty())
    }

    @Test
    fun `selecting a floor clears an area selection that does not belong to it`() = runTest {
        val picker = pickerWithSampleRegistry()

        picker.onAreaFilterChange("kitchen")
        picker.onFloorFilterChange("first")

        assertNull(picker.uiState.value.areaFilter)
    }

    @Test
    fun `selecting a floor keeps an area selection that belongs to it`() = runTest {
        val picker = pickerWithSampleRegistry()

        picker.onAreaFilterChange("kitchen")
        picker.onFloorFilterChange("ground")

        assertEquals("kitchen", picker.uiState.value.areaFilter)
    }

    @Test
    fun `selecting a floor clears a no-area selection`() = runTest {
        val picker = pickerWithSampleRegistry()

        picker.onAreaFilterChange(EntityPickerState.NO_AREA_ID)
        picker.onFloorFilterChange("ground")

        assertNull(picker.uiState.value.areaFilter)
    }

    @Test
    fun `available rows carry area and floor names, absent for unassigned entities`() = runTest {
        val picker = pickerWithSampleRegistry()

        val kitchenRow = picker.uiState.value.results.first { it.entityId == "light.kitchen" }
        assertEquals("Kitchen", kitchenRow.areaName)
        assertEquals("Ground floor", kitchenRow.floorName)

        val sensorRow = picker.uiState.value.results.first { it.entityId == "sensor.outdoor_temperature" }
        assertNull(sensorRow.areaName)
        assertNull(sensorRow.floorName)
    }

    @Test
    fun `floors and areas are empty when the registry has none`() = runTest {
        val (picker, _) = pickerFor(sampleEntities(), registry = HaRegistry())

        assertTrue(picker.uiState.value.floors.isEmpty())
        assertTrue(picker.uiState.value.areas.isEmpty())
    }
}
