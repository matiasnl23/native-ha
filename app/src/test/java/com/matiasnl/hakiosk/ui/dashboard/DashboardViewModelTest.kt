package com.matiasnl.hakiosk.ui.dashboard

import com.matiasnl.hakiosk.data.dashboard.DashboardTile
import com.matiasnl.hakiosk.data.dashboard.InMemoryDashboardConfigStore
import com.matiasnl.hakiosk.data.ha.HaConnectionState
import com.matiasnl.hakiosk.data.ha.HaConnectionTestResult
import com.matiasnl.hakiosk.data.ha.HaEntity
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
        val configStore = InMemoryDashboardConfigStore(
            listOf(DashboardTile("sensor.temp"), DashboardTile("light.kitchen", label = "Cocina")),
        )
        val viewModel = DashboardViewModel(repository, configStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        val tiles = viewModel.uiState.value.tiles
        assertEquals(listOf("sensor.temp", "light.kitchen"), tiles.map { it.entityId })
        assertEquals("Temp", tiles[0].label)
        assertEquals("Cocina", tiles[1].label)
    }

    @Test
    fun `missing entity is flagged and not actionable`() = runTest {
        val repository = FakeHaRepository(initialEntities = emptyList())
        val configStore = InMemoryDashboardConfigStore(listOf(DashboardTile("light.gone")))
        val viewModel = DashboardViewModel(repository, configStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        val tile = viewModel.uiState.value.tiles.single()
        assertTrue(tile.isMissing)
        assertFalse(tile.isActionable)
        assertEquals("light.gone", tile.label)
    }

    @Test
    fun `tapping a light toggles it`() = runTest {
        val repository = FakeHaRepository(initialEntities = listOf(entity("light.kitchen", "off", "Kitchen")))
        val configStore = InMemoryDashboardConfigStore(listOf(DashboardTile("light.kitchen")))
        val viewModel = DashboardViewModel(repository, configStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onTileClick(viewModel.uiState.value.tiles.single())

        assertEquals("on", repository.entities.value.getValue("light.kitchen").state)
    }

    @Test
    fun `tapping a sensor is a no-op`() = runTest {
        val repository = FakeHaRepository(initialEntities = listOf(entity("sensor.temp", "18.5", "Temp")))
        val configStore = InMemoryDashboardConfigStore(listOf(DashboardTile("sensor.temp")))
        val viewModel = DashboardViewModel(repository, configStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val tile = viewModel.uiState.value.tiles.single()

        assertFalse(tile.isActionable)
        viewModel.onTileClick(tile)

        assertEquals("18.5", repository.entities.value.getValue("sensor.temp").state)
    }

    @Test
    fun `tapping a camera is a no-op`() = runTest {
        val repository = FakeHaRepository(initialEntities = listOf(entity("camera.front_door", "idle", "Front door")))
        val configStore = InMemoryDashboardConfigStore(listOf(DashboardTile("camera.front_door")))
        val viewModel = DashboardViewModel(repository, configStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val tile = viewModel.uiState.value.tiles.single()

        assertFalse(tile.isActionable)
        viewModel.onTileClick(tile)

        assertEquals("idle", repository.entities.value.getValue("camera.front_door").state)
    }

    @Test
    fun `tapping a scene calls turn_on`() = runTest {
        val repository = FakeHaRepository(initialEntities = listOf(entity("scene.movie_night", "scening", "Movie night")))
        val configStore = InMemoryDashboardConfigStore(listOf(DashboardTile("scene.movie_night")))
        val viewModel = DashboardViewModel(repository, configStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val tile = viewModel.uiState.value.tiles.single()

        assertTrue(tile.isActionable)
        viewModel.onTileClick(tile)
        // FakeHaRepository maps "turn_on" to state "on" regardless of domain semantics.
        assertEquals("on", repository.entities.value.getValue("scene.movie_night").state)
    }

    @Test
    fun `failed service call emits an error event with the tile label`() = runTest {
        val repository = FailingHaRepository(initialEntities = listOf(entity("light.kitchen", "off", "Kitchen")))
        val configStore = InMemoryDashboardConfigStore(listOf(DashboardTile("light.kitchen", label = "Cocina")))
        val viewModel = DashboardViewModel(repository, configStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val tile = viewModel.uiState.value.tiles.single()

        var emitted: String? = null
        backgroundScope.launch(Dispatchers.Main) {
            viewModel.errorEvents.collect { emitted = it }
        }
        viewModel.onTileClick(tile)

        assertEquals("Cocina", emitted)
    }
}
