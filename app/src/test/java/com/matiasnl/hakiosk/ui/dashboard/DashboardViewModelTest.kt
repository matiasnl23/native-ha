package com.matiasnl.hakiosk.ui.dashboard

import com.matiasnl.hakiosk.data.dashboard.DashboardLayout
import com.matiasnl.hakiosk.data.dashboard.DashboardView
import com.matiasnl.hakiosk.data.dashboard.InMemoryDashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.TileContent
import com.matiasnl.hakiosk.data.dashboard.newDashboardTile
import com.matiasnl.hakiosk.data.ha.HaConnectionState
import com.matiasnl.hakiosk.data.ha.HaConnectionTestResult
import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.HaRegistry
import com.matiasnl.hakiosk.data.ha.HaRepository
import com.matiasnl.hakiosk.data.ha.HaServerConfig
import com.matiasnl.hakiosk.data.ha.fake.FakeHaRepository
import com.matiasnl.hakiosk.ui.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Test double whose [callService] always fails, to exercise the error-event path. */
private class FailingHaRepository(
    initialEntities: List<HaEntity>,
) : HaRepository {
    override val connectionState: StateFlow<HaConnectionState> =
        MutableStateFlow<HaConnectionState>(HaConnectionState.Connected).asStateFlow()
    override val entities: StateFlow<Map<String, HaEntity>> =
        MutableStateFlow(initialEntities.associateBy { it.entityId }).asStateFlow()
    override val registry: StateFlow<HaRegistry> = MutableStateFlow(HaRegistry()).asStateFlow()

    override fun start() {}
    override fun stop() {}

    override suspend fun callService(
        domain: String,
        service: String,
        entityId: String,
        data: JsonObject,
    ): Result<Unit> = Result.failure(IllegalStateException("boom"))

    override suspend fun testConnection(config: HaServerConfig): HaConnectionTestResult =
        HaConnectionTestResult.Success("fake")
}

private fun entity(id: String, state: String, name: String, unit: String? = null) = HaEntity(
    entityId = id,
    state = state,
    attributes = JsonObject(
        buildMap {
            put("friendly_name", JsonPrimitive(name))
            unit?.let { put("unit_of_measurement", JsonPrimitive(it)) }
        },
    ),
    lastChanged = "2026-01-01T00:00:00+00:00",
)

/** A single-view layout whose first view has the given entity tiles, in order. */
private fun layoutWithTiles(vararg tiles: Pair<String, String?>): DashboardLayout = DashboardLayout(
    views = listOf(
        DashboardView(
            id = "view-1",
            name = "Principal",
            tiles = tiles.map { (entityId, label) -> newDashboardTile(TileContent.Entity(entityId, label)) },
        ),
    ),
)

private fun layoutWithTile(entityId: String, label: String? = null) = layoutWithTiles(entityId to label)

class DashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `tiles join configured order with live entity state`() = runTest {
        val repository = FakeHaRepository(
            initialEntities = listOf(
                entity("light.kitchen", "on", "Kitchen"),
                entity("sensor.temp", "18.5", "Temp", unit = "°C"),
            ),
        )
        val layoutStore = InMemoryDashboardLayoutStore(
            layoutWithTiles("sensor.temp" to null, "light.kitchen" to "Cocina"),
        )
        val viewModel = DashboardViewModel(repository, layoutStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        val tiles = viewModel.uiState.value.tiles
        assertEquals(listOf("sensor.temp", "light.kitchen"), tiles.map { it.entityId })
        assertEquals("Temp", tiles[0].label)
        assertEquals("Cocina", tiles[1].label)
    }

    @Test
    fun `missing entity is flagged and not actionable`() = runTest {
        val repository = FakeHaRepository(initialEntities = emptyList())
        val layoutStore = InMemoryDashboardLayoutStore(layoutWithTile("light.gone"))
        val viewModel = DashboardViewModel(repository, layoutStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        val tile = viewModel.uiState.value.tiles.single()
        assertTrue(tile.isMissing)
        assertFalse(tile.isActionable)
        assertEquals("light.gone", tile.label)
    }

    @Test
    fun `tapping a light toggles it`() = runTest {
        val repository = FakeHaRepository(initialEntities = listOf(entity("light.kitchen", "off", "Kitchen")))
        val layoutStore = InMemoryDashboardLayoutStore(layoutWithTile("light.kitchen"))
        val viewModel = DashboardViewModel(repository, layoutStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onTileClick(viewModel.uiState.value.tiles.single())

        assertEquals("on", repository.entities.value.getValue("light.kitchen").state)
    }

    @Test
    fun `tapping a sensor is a no-op`() = runTest {
        val repository = FakeHaRepository(initialEntities = listOf(entity("sensor.temp", "18.5", "Temp")))
        val layoutStore = InMemoryDashboardLayoutStore(layoutWithTile("sensor.temp"))
        val viewModel = DashboardViewModel(repository, layoutStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val tile = viewModel.uiState.value.tiles.single()

        assertFalse(tile.isActionable)
        viewModel.onTileClick(tile)

        assertEquals("18.5", repository.entities.value.getValue("sensor.temp").state)
    }

    @Test
    fun `tapping a camera opens its focus view without calling a service`() = runTest {
        val repository = FakeHaRepository(initialEntities = listOf(entity("camera.front_door", "idle", "Front door")))
        val layoutStore = InMemoryDashboardLayoutStore(layoutWithTile("camera.front_door", label = "Entrada"))
        val viewModel = DashboardViewModel(repository, layoutStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val opened = mutableListOf<OpenCameraEvent>()
        backgroundScope.launch(Dispatchers.Main) { viewModel.openCameraEvents.collect { opened += it } }
        val tile = viewModel.uiState.value.tiles.single()

        assertTrue(tile.isActionable)
        viewModel.onTileClick(tile)

        assertEquals(listOf(OpenCameraEvent("camera.front_door", "Entrada")), opened)
        assertEquals("idle", repository.entities.value.getValue("camera.front_door").state)
    }

    @Test
    fun `unavailable or missing cameras are not actionable and do not open`() = runTest {
        val repository = FakeHaRepository(
            initialEntities = listOf(entity("camera.garage", "unavailable", "Garage")),
        )
        val layoutStore = InMemoryDashboardLayoutStore(
            layoutWithTiles("camera.garage" to null, "camera.removed" to null),
        )
        val viewModel = DashboardViewModel(repository, layoutStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val opened = mutableListOf<OpenCameraEvent>()
        backgroundScope.launch(Dispatchers.Main) { viewModel.openCameraEvents.collect { opened += it } }

        val tiles = viewModel.uiState.value.tiles
        assertFalse(tiles.any { it.isActionable })
        tiles.forEach(viewModel::onTileClick)

        assertTrue(opened.isEmpty())
    }

    @Test
    fun `tapping a scene calls turn_on`() = runTest {
        val repository = FakeHaRepository(initialEntities = listOf(entity("scene.movie_night", "scening", "Movie night")))
        val layoutStore = InMemoryDashboardLayoutStore(layoutWithTile("scene.movie_night"))
        val viewModel = DashboardViewModel(repository, layoutStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val tile = viewModel.uiState.value.tiles.single()

        assertTrue(tile.isActionable)
        viewModel.onTileClick(tile)
        // FakeHaRepository maps "turn_on" to state "on" regardless of domain semantics.
        assertEquals("on", repository.entities.value.getValue("scene.movie_night").state)
    }

    @Test
    fun `failed service call emits an error event with the tile label and repository message`() = runTest {
        val repository = FailingHaRepository(initialEntities = listOf(entity("light.kitchen", "off", "Kitchen")))
        val layoutStore = InMemoryDashboardLayoutStore(layoutWithTile("light.kitchen", label = "Cocina"))
        val viewModel = DashboardViewModel(repository, layoutStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val tile = viewModel.uiState.value.tiles.single()

        var emitted: DashboardActionError? = null
        backgroundScope.launch(Dispatchers.Main) {
            viewModel.errorEvents.collect { emitted = it }
        }
        viewModel.onTileClick(tile)

        assertEquals("Cocina", emitted?.label)
        assertEquals("boom", emitted?.message)
    }

    @Test
    fun `hasEntities stays true after entities were synced even if the state goes idle`() = runTest {
        val repository = FakeHaRepository(initialEntities = listOf(entity("light.kitchen", "on", "Kitchen")))
        val layoutStore = InMemoryDashboardLayoutStore(layoutWithTile("light.kitchen"))
        val viewModel = DashboardViewModel(repository, layoutStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        repository.start()
        assertTrue(viewModel.uiState.value.hasEntities)

        repository.stop()
        assertEquals(com.matiasnl.hakiosk.data.ha.HaConnectionState.Idle, viewModel.uiState.value.connectionState)
        assertTrue(viewModel.uiState.value.hasEntities)
    }
}
