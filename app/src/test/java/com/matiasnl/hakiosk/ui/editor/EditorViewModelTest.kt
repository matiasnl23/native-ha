package com.matiasnl.hakiosk.ui.editor

import com.matiasnl.hakiosk.data.dashboard.DashboardConfigStore
import com.matiasnl.hakiosk.data.dashboard.DashboardTile
import com.matiasnl.hakiosk.data.dashboard.InMemoryDashboardConfigStore
import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.HaRegistry
import com.matiasnl.hakiosk.data.ha.fake.FakeHaRepository
import com.matiasnl.hakiosk.ui.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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

/**
 * [DashboardConfigStore] whose [tiles] flow only emits once [release] is called, to reproduce the
 * race between the async initial load in [EditorViewModel]'s init and edits made in the meantime.
 */
private class GatedDashboardConfigStore(private val stored: List<DashboardTile>) : DashboardConfigStore {
    private val gate = CompletableDeferred<Unit>()

    override val tiles: Flow<List<DashboardTile>> = flow {
        gate.await()
        emit(stored)
    }

    override suspend fun setTiles(tiles: List<DashboardTile>) {}

    fun release() {
        gate.complete(Unit)
    }
}

class EditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun TestScopeViewModel(
        entities: List<HaEntity>,
        initialTiles: List<DashboardTile> = emptyList(),
    ): Triple<EditorViewModel, FakeHaRepository, InMemoryDashboardConfigStore> {
        val repository = FakeHaRepository(initialEntities = entities)
        val configStore = InMemoryDashboardConfigStore(initialTiles)
        val viewModel = EditorViewModel(repository, configStore)
        return Triple(viewModel, repository, configStore)
    }

    private fun manyEntities(count: Int) = (0 until count).map { entity("light.e%03d".format(it), "Entity %03d".format(it)) }

    @Test
    fun `available list is capped and reports the total number of matches`() = runTest {
        val (viewModel, _, _) = TestScopeViewModel(manyEntities(120))
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        val state = viewModel.uiState.value
        assertEquals(EditorViewModel.MAX_AVAILABLE_RESULTS, state.availableEntities.size)
        assertEquals(120, state.totalMatches)
        assertEquals("Entity 000", state.availableEntities.first().friendlyName)
    }

    @Test
    fun `search narrows matches below the cap`() = runTest {
        val (viewModel, _, _) = TestScopeViewModel(manyEntities(120))
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onQueryChange("entity 11")

        val state = viewModel.uiState.value
        assertEquals(10, state.totalMatches)
        assertEquals((110..119).map { "light.e$it" }, state.availableEntities.map { it.entityId })
    }

    @Test
    fun `entity state changes do not rebuild the editor state`() = runTest {
        val (viewModel, repository, _) = TestScopeViewModel(listOf(entity("light.a", "A"), entity("light.b", "B")))
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val before = viewModel.uiState.value

        repository.callService("light", "toggle", "light.a")

        assertEquals("off", repository.entities.value.getValue("light.a").state)
        assertTrue(before === viewModel.uiState.value)
    }

    @Test
    fun `loads existing tiles and lists all entities as available`() = runTest {
        val (viewModel, _, _) = TestScopeViewModel(
            entities = listOf(entity("light.kitchen", "Kitchen"), entity("light.living_room", "Living room")),
            initialTiles = listOf(DashboardTile("light.living_room")),
        )
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        val state = viewModel.uiState.value
        assertEquals(listOf("light.living_room"), state.currentTiles.map { it.entityId })
        assertEquals(2, state.availableEntities.size)
        assertTrue(state.availableEntities.first { it.entityId == "light.living_room" }.alreadyAdded)
        assertFalse(state.availableEntities.first { it.entityId == "light.kitchen" }.alreadyAdded)
    }

    @Test
    fun `add appends a tile without duplicating`() = runTest {
        val (viewModel, _, _) = TestScopeViewModel(entities = listOf(entity("light.kitchen", "Kitchen")))
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.addTile("light.kitchen")
        viewModel.addTile("light.kitchen")

        assertEquals(listOf("light.kitchen"), viewModel.uiState.value.currentTiles.map { it.entityId })
    }

    @Test
    fun `remove drops the tile`() = runTest {
        val (viewModel, _, _) = TestScopeViewModel(
            entities = listOf(entity("light.kitchen", "Kitchen")),
            initialTiles = listOf(DashboardTile("light.kitchen")),
        )
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.removeTile("light.kitchen")

        assertTrue(viewModel.uiState.value.currentTiles.isEmpty())
    }

    @Test
    fun `move up and down reorder tiles`() = runTest {
        val (viewModel, _, _) = TestScopeViewModel(
            entities = listOf(entity("light.a", "A"), entity("light.b", "B"), entity("light.c", "C")),
            initialTiles = listOf(DashboardTile("light.a"), DashboardTile("light.b"), DashboardTile("light.c")),
        )
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.moveDown("light.a")
        assertEquals(listOf("light.b", "light.a", "light.c"), viewModel.uiState.value.currentTiles.map { it.entityId })

        viewModel.moveUp("light.c")
        assertEquals(listOf("light.b", "light.c", "light.a"), viewModel.uiState.value.currentTiles.map { it.entityId })

        // Already first: no-op.
        viewModel.moveUp("light.b")
        assertEquals(listOf("light.b", "light.c", "light.a"), viewModel.uiState.value.currentTiles.map { it.entityId })
    }

    @Test
    fun `label override is stored and blank clears it`() = runTest {
        val (viewModel, _, _) = TestScopeViewModel(
            entities = listOf(entity("light.kitchen", "Kitchen")),
            initialTiles = listOf(DashboardTile("light.kitchen")),
        )
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.setLabel("light.kitchen", "Cocina")
        assertEquals("Cocina", viewModel.uiState.value.currentTiles.single().label)

        viewModel.setLabel("light.kitchen", "  ")
        assertNull(viewModel.uiState.value.currentTiles.single().label)
    }

    @Test
    fun `search filters by name and entity id`() = runTest {
        val (viewModel, _, _) = TestScopeViewModel(
            entities = listOf(entity("light.kitchen", "Kitchen"), entity("light.living_room", "Living room")),
        )
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onQueryChange("kitchen")

        assertEquals(listOf("light.kitchen"), viewModel.uiState.value.availableEntities.map { it.entityId })
    }

    @Test
    fun `domain filter narrows available entities`() = runTest {
        val (viewModel, _, _) = TestScopeViewModel(
            entities = listOf(entity("light.kitchen", "Kitchen"), entity("switch.pump", "Pump")),
        )
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onDomainFilterChange("switch")

        assertEquals(listOf("switch.pump"), viewModel.uiState.value.availableEntities.map { it.entityId })
    }

    @Test
    fun `save persists the working tile list`() = runTest {
        val (viewModel, _, configStore) = TestScopeViewModel(entities = listOf(entity("light.kitchen", "Kitchen")))
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.addTile("light.kitchen")
        var saved = false
        viewModel.save { saved = true }

        assertTrue(saved)
        assertEquals(listOf("light.kitchen"), configStore.tiles.value.map { it.entityId })
    }

    @Test
    fun `isLoaded is false until the stored tiles arrive`() = runTest {
        val repository = FakeHaRepository(initialEntities = listOf(entity("light.kitchen", "Kitchen")))
        val configStore = GatedDashboardConfigStore(emptyList())
        val viewModel = EditorViewModel(repository, configStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        assertFalse(viewModel.uiState.value.isLoaded)

        configStore.release()

        assertTrue(viewModel.uiState.value.isLoaded)
    }

    @Test
    fun `tile added before the stored load arrives is kept alongside the loaded tiles`() = runTest {
        val repository = FakeHaRepository(
            initialEntities = listOf(entity("light.kitchen", "Kitchen"), entity("light.living_room", "Living room")),
        )
        val configStore = GatedDashboardConfigStore(listOf(DashboardTile("light.living_room")))
        val viewModel = EditorViewModel(repository, configStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        // The user adds a tile before the stored ("light.living_room") list has loaded.
        viewModel.addTile("light.kitchen")
        assertEquals(listOf("light.kitchen"), viewModel.uiState.value.currentTiles.map { it.entityId })

        configStore.release()

        // Neither the pre-load edit nor the previously stored tile was lost.
        assertEquals(
            setOf("light.living_room", "light.kitchen"),
            viewModel.uiState.value.currentTiles.map { it.entityId }.toSet(),
        )
    }

    @Test
    fun `tile removed before the stored load arrives stays removed after it loads`() = runTest {
        val repository = FakeHaRepository(
            initialEntities = listOf(entity("light.kitchen", "Kitchen"), entity("light.living_room", "Living room")),
        )
        val configStore = GatedDashboardConfigStore(
            listOf(DashboardTile("light.living_room"), DashboardTile("light.kitchen")),
        )
        val viewModel = EditorViewModel(repository, configStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        // Removing a tile that isn't loaded yet queues the edit; nothing to remove yet.
        viewModel.removeTile("light.kitchen")
        assertTrue(viewModel.uiState.value.currentTiles.isEmpty())

        configStore.release()

        assertEquals(listOf("light.living_room"), viewModel.uiState.value.currentTiles.map { it.entityId })
    }

    // sampleRegistry(): floors "ground" (level 0) and "first" (level 1); areas entrance/kitchen/
    // living_room on "ground" and bedroom (empty) on "first"; sensor.outdoor_temperature unassigned.
    private fun editorWithSampleRegistry(): EditorViewModel =
        EditorViewModel(FakeHaRepository(), InMemoryDashboardConfigStore())

    @Test
    fun `floor filter matches entities whose area is on that floor`() = runTest {
        val viewModel = editorWithSampleRegistry()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onFloorFilterChange("ground")

        assertEquals(
            setOf("light.living_room", "light.kitchen", "switch.coffee_maker", "camera.front_door", "scene.movie_night"),
            viewModel.uiState.value.availableEntities.map { it.entityId }.toSet(),
        )
    }

    @Test
    fun `floor with only empty areas yields no available entities`() = runTest {
        val viewModel = editorWithSampleRegistry()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onFloorFilterChange("first")

        assertTrue(viewModel.uiState.value.availableEntities.isEmpty())
    }

    @Test
    fun `area choices are restricted to the selected floor`() = runTest {
        val viewModel = editorWithSampleRegistry()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onFloorFilterChange("ground")
        assertEquals(
            setOf("entrance", "kitchen", "living_room"),
            viewModel.uiState.value.areas.map { it.areaId }.toSet(),
        )

        viewModel.onFloorFilterChange("first")
        assertEquals(setOf("bedroom"), viewModel.uiState.value.areas.map { it.areaId }.toSet())

        viewModel.onFloorFilterChange(null)
        assertEquals(
            setOf("bedroom", "entrance", "kitchen", "living_room"),
            viewModel.uiState.value.areas.map { it.areaId }.toSet(),
        )
    }

    @Test
    fun `area filter narrows to that area's entities`() = runTest {
        val viewModel = editorWithSampleRegistry()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onAreaFilterChange("kitchen")

        assertEquals(
            setOf("light.kitchen", "switch.coffee_maker"),
            viewModel.uiState.value.availableEntities.map { it.entityId }.toSet(),
        )
    }

    @Test
    fun `no-area filter matches only unassigned entities`() = runTest {
        val viewModel = editorWithSampleRegistry()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onAreaFilterChange(EditorViewModel.NO_AREA_ID)

        assertEquals(
            listOf("sensor.outdoor_temperature"),
            viewModel.uiState.value.availableEntities.map { it.entityId },
        )
    }

    @Test
    fun `floor area domain and search filters combine with AND`() = runTest {
        val viewModel = editorWithSampleRegistry()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onFloorFilterChange("ground")
        viewModel.onAreaFilterChange("kitchen")
        viewModel.onDomainFilterChange("switch")
        viewModel.onQueryChange("coffee")

        assertEquals(
            listOf("switch.coffee_maker"),
            viewModel.uiState.value.availableEntities.map { it.entityId },
        )

        // Narrowing the search further to something that doesn't match empties the result.
        viewModel.onQueryChange("nonexistent")
        assertTrue(viewModel.uiState.value.availableEntities.isEmpty())
    }

    @Test
    fun `selecting a floor clears an area selection that does not belong to it`() = runTest {
        val viewModel = editorWithSampleRegistry()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onAreaFilterChange("kitchen")
        viewModel.onFloorFilterChange("first")

        assertNull(viewModel.uiState.value.areaFilter)
    }

    @Test
    fun `selecting a floor keeps an area selection that belongs to it`() = runTest {
        val viewModel = editorWithSampleRegistry()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onAreaFilterChange("kitchen")
        viewModel.onFloorFilterChange("ground")

        assertEquals("kitchen", viewModel.uiState.value.areaFilter)
    }

    @Test
    fun `selecting a floor clears a no-area selection`() = runTest {
        val viewModel = editorWithSampleRegistry()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onAreaFilterChange(EditorViewModel.NO_AREA_ID)
        viewModel.onFloorFilterChange("ground")

        assertNull(viewModel.uiState.value.areaFilter)
    }

    @Test
    fun `available rows carry area and floor names, absent for unassigned entities`() = runTest {
        val viewModel = editorWithSampleRegistry()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        val kitchenRow = viewModel.uiState.value.availableEntities.first { it.entityId == "light.kitchen" }
        assertEquals("Kitchen", kitchenRow.areaName)
        assertEquals("Ground floor", kitchenRow.floorName)

        val sensorRow = viewModel.uiState.value.availableEntities.first { it.entityId == "sensor.outdoor_temperature" }
        assertNull(sensorRow.areaName)
        assertNull(sensorRow.floorName)
    }

    @Test
    fun `floors and areas are empty when the registry has none`() = runTest {
        val repository = FakeHaRepository(initialRegistry = HaRegistry())
        val viewModel = EditorViewModel(repository, InMemoryDashboardConfigStore())
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        assertTrue(viewModel.uiState.value.floors.isEmpty())
        assertTrue(viewModel.uiState.value.areas.isEmpty())
    }
}
