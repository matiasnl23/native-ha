package com.matiasnl.hakiosk.ui.dashboard

import com.matiasnl.hakiosk.data.dashboard.DashboardGrid
import com.matiasnl.hakiosk.data.dashboard.DashboardLayout
import com.matiasnl.hakiosk.data.dashboard.DashboardTile
import com.matiasnl.hakiosk.data.dashboard.DashboardView
import com.matiasnl.hakiosk.data.dashboard.FakeDashboardIdProvider
import com.matiasnl.hakiosk.data.dashboard.setGrid
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPlacement
import org.junit.Assert.assertSame
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
import com.matiasnl.hakiosk.ui.dashboard.edit.LinkTargetOption
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

        val tiles = viewModel.uiState.value.entityTiles()
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

        val tile = viewModel.uiState.value.entityTiles().single()
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

        viewModel.onTileClick(viewModel.uiState.value.entityTiles().single())

        assertEquals("on", repository.entities.value.getValue("light.kitchen").state)
    }

    @Test
    fun `tapping a sensor is a no-op`() = runTest {
        val repository = FakeHaRepository(initialEntities = listOf(entity("sensor.temp", "18.5", "Temp")))
        val layoutStore = InMemoryDashboardLayoutStore(layoutWithTile("sensor.temp"))
        val viewModel = DashboardViewModel(repository, layoutStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val tile = viewModel.uiState.value.entityTiles().single()

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
        val tile = viewModel.uiState.value.entityTiles().single()

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

        val tiles = viewModel.uiState.value.entityTiles()
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
        val tile = viewModel.uiState.value.entityTiles().single()

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
        val tile = viewModel.uiState.value.entityTiles().single()

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

    @Test
    fun `exposes the first view's grid and all its tiles in order with spans and packing`() = runTest {
        val repository = FakeHaRepository(initialEntities = listOf(entity("light.kitchen", "off", "Kitchen")))
        val layoutStore = InMemoryDashboardLayoutStore(mixedLayout())
        val viewModel = DashboardViewModel(repository, layoutStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        val state = viewModel.uiState.value
        assertEquals("view-1", state.viewId)
        assertEquals(DashboardGrid(columns = 3, rows = 2), state.grid)
        assertEquals(listOf("t-light", "t-spacer", "t-link", "t-missing-link"), state.tiles.map { it.id })
        assertEquals(listOf(2 to 2, 1 to 1, 1 to 1, 5 to 1), state.tiles.map { it.colSpan to it.rowSpan })

        val light = state.tiles[0] as DashboardTileUiState
        assertEquals("Kitchen", light.label)
        assertTrue(light.isActionable)
        assertTrue(state.tiles[1] is SpacerTileUiState)
        assertEquals(ViewLinkTileUiState("t-link", "view-2", "Planta alta"), state.tiles[2])
        assertEquals(null, (state.tiles[3] as ViewLinkTileUiState).label)

        assertEquals(
            listOf(
                GridPlacement(0, 0, 2, 2),
                GridPlacement(0, 2, 1, 1),
                GridPlacement(1, 2, 1, 1),
                GridPlacement(2, 0, 3, 1), // 5 columns clipped to 3.
            ),
            state.packing.placements,
        )
        assertEquals(3, state.packing.totalRows)
    }

    @Test
    fun `view link label override wins over the target view name`() = runTest {
        val layout = mixedLayout().let { layout ->
            layout.copy(
                views = layout.views.map { view ->
                    view.copy(
                        tiles = view.tiles.map {
                            if (it.id == "t-link") it.copy(content = TileContent.ViewLink("view-2", "Arriba")) else it
                        },
                    )
                },
            )
        }
        val viewModel = DashboardViewModel(FakeHaRepository(emptyList()), InMemoryDashboardLayoutStore(layout))
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        assertEquals("Arriba", (viewModel.uiState.value.tiles[2] as ViewLinkTileUiState).label)
    }

    @Test
    fun `changing the grid repacks the tiles`() = runTest {
        val layoutStore = InMemoryDashboardLayoutStore(mixedLayout())
        val viewModel = DashboardViewModel(FakeHaRepository(emptyList()), layoutStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        layoutStore.update { it.setGrid("view-1", DashboardGrid(columns = 6, rows = 4)) }

        val state = viewModel.uiState.value
        assertEquals(DashboardGrid(columns = 6, rows = 4), state.grid)
        assertEquals(
            listOf(GridPlacement(0, 0, 2, 2), GridPlacement(0, 2, 1, 1), GridPlacement(0, 3, 1, 1), GridPlacement(2, 0, 5, 1)),
            state.packing.placements,
        )
    }

    @Test
    fun `entity state changes keep the same packing instance and spans`() = runTest {
        val repository = FakeHaRepository(initialEntities = listOf(entity("light.kitchen", "off", "Kitchen")))
        val viewModel = DashboardViewModel(repository, InMemoryDashboardLayoutStore(mixedLayout()))
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val before = viewModel.uiState.value

        viewModel.onTileClick(viewModel.uiState.value.entityTiles().single())

        val after = viewModel.uiState.value
        val light = after.entityTiles().single()
        assertTrue(light.isOn)
        assertEquals(2 to 2, light.colSpan to light.rowSpan)
        assertSame(before.packing, after.packing)
    }

    // --- Edit mode ---

    private fun editingViewModel(
        layout: DashboardLayout = layoutWithTile("light.kitchen"),
        entities: List<HaEntity> = listOf(entity("light.kitchen", "off", "Kitchen")),
    ): Pair<DashboardViewModel, InMemoryDashboardLayoutStore> {
        val layoutStore = InMemoryDashboardLayoutStore(layout)
        val viewModel = DashboardViewModel(FakeHaRepository(entities), layoutStore, FakeDashboardIdProvider())
        return viewModel to layoutStore
    }

    @Test
    fun `entering edit mode snapshots the layout and appends the add tile`() = runTest {
        val (viewModel, _) = editingViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.enterEditMode()

        val state = viewModel.uiState.value
        assertTrue(state.isEditing)
        assertFalse(state.isDirty)
        assertEquals("light.kitchen", state.entityTiles().single().entityId)
        assertEquals(AddTileUiState.ID, state.tiles.last().id)
        assertEquals(2, state.tiles.size)
        assertEquals(2, state.packing.placements.size)
    }

    @Test
    fun `tile taps are no-ops while editing`() = runTest {
        val (viewModel, repository) = run {
            val repository = FakeHaRepository(initialEntities = listOf(entity("light.kitchen", "off", "Kitchen")))
            val layoutStore = InMemoryDashboardLayoutStore(layoutWithTile("light.kitchen"))
            DashboardViewModel(repository, layoutStore, FakeDashboardIdProvider()) to repository
        }
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()

        val tile = viewModel.uiState.value.entityTiles().single()
        viewModel.onTileClick(tile)

        assertEquals("off", repository.entities.value.getValue("light.kitchen").state)
    }

    @Test
    fun `add entity, spacer and link tiles show up in the working copy while editing`() = runTest {
        val layout = DashboardLayout(
            views = listOf(DashboardView(id = "view-1", name = "Principal"), DashboardView(id = "view-2", name = "Arriba")),
        )
        val (viewModel, _) = editingViewModel(layout = layout, entities = emptyList())
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()

        viewModel.addEntityTile("light.new")
        viewModel.addSpacerTile()
        viewModel.addLinkTile("view-2")

        val tiles = viewModel.uiState.value.tiles
        assertEquals(listOf("id-1", "id-2", "id-3", AddTileUiState.ID), tiles.map { it.id })
        assertTrue(tiles[1] is SpacerTileUiState)
        assertEquals("view-2", (tiles[2] as ViewLinkTileUiState).targetViewId)
        assertEquals(listOf(LinkTargetOption("view-2", "Arriba")), viewModel.uiState.value.linkTargets)
    }

    @Test
    fun `the add tile is never persisted`() = runTest {
        val (viewModel, layoutStore) = editingViewModel(entities = emptyList())
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()

        viewModel.doneEditMode()

        val persistedIds = layoutStore.layout.value.views.single().tiles.map { it.id }
        assertFalse(AddTileUiState.ID in persistedIds)
    }

    @Test
    fun `label edits apply and a blank label clears back to the default`() = runTest {
        val (viewModel, _) = editingViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()
        val tileId = viewModel.uiState.value.entityTiles().single().id

        viewModel.setEditTileLabel(tileId, "Cocina")
        assertEquals("Cocina", viewModel.uiState.value.entityTiles().single().label)

        viewModel.setEditTileLabel(tileId, "   ")
        assertEquals("Kitchen", viewModel.uiState.value.entityTiles().single().label)
    }

    @Test
    fun `resize clamps width to the view's columns`() = runTest {
        val layout = DashboardLayout(
            views = listOf(
                DashboardView(
                    id = "view-1",
                    name = "Principal",
                    grid = DashboardGrid(columns = 3, rows = 2),
                    tiles = listOf(DashboardTile("t", TileContent.Entity("light.kitchen"))),
                ),
            ),
        )
        val (viewModel, _) = editingViewModel(layout = layout)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()

        viewModel.resizeEditTile("t", colSpan = 9, rowSpan = 4)

        val tile = viewModel.uiState.value.entityTiles().single()
        assertEquals(3, tile.colSpan)
        assertEquals(4, tile.rowSpan)
    }

    @Test
    fun `remove drops the tile from the working copy`() = runTest {
        val (viewModel, _) = editingViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()
        val tileId = viewModel.uiState.value.entityTiles().single().id

        viewModel.removeEditTile(tileId)

        assertTrue(viewModel.uiState.value.entityTiles().isEmpty())
    }

    @Test
    fun `grid change applies to the working copy only until done`() = runTest {
        val (viewModel, layoutStore) = editingViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()

        viewModel.setEditGrid(DashboardGrid(columns = 6, rows = 4))

        assertEquals(DashboardGrid(columns = 6, rows = 4), viewModel.uiState.value.grid)
        assertEquals(DashboardGrid(), layoutStore.layout.value.views.single().grid)
    }

    @Test
    fun `cancel discards the working copy and exits edit mode`() = runTest {
        val (viewModel, layoutStore) = editingViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val originalLayout = layoutStore.layout.value
        viewModel.enterEditMode()
        viewModel.addSpacerTile()

        viewModel.cancelEditMode()

        assertFalse(viewModel.uiState.value.isEditing)
        assertEquals(originalLayout, layoutStore.layout.value)
    }

    @Test
    fun `done persists the working copy exactly once and exits edit mode`() = runTest {
        val (viewModel, layoutStore) = editingViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()
        viewModel.addSpacerTile()

        viewModel.doneEditMode()

        assertFalse(viewModel.uiState.value.isEditing)
        assertEquals(2, layoutStore.layout.value.views.single().tiles.size)
    }

    @Test
    fun `isDirty flips once an edit is made`() = runTest {
        val (viewModel, _) = editingViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()
        assertFalse(viewModel.uiState.value.isDirty)

        viewModel.addSpacerTile()

        assertTrue(viewModel.uiState.value.isDirty)
    }
}

private fun DashboardUiState.entityTiles(): List<DashboardTileUiState> = tiles.filterIsInstance<DashboardTileUiState>()

/** view-1 (3×2 grid): a 2×2 light, a spacer, a link to view-2 and an oversized link to a missing view. */
private fun mixedLayout() = DashboardLayout(
    views = listOf(
        DashboardView(
            id = "view-1",
            name = "Principal",
            grid = DashboardGrid(columns = 3, rows = 2),
            tiles = listOf(
                DashboardTile("t-light", TileContent.Entity("light.kitchen"), colSpan = 2, rowSpan = 2),
                DashboardTile("t-spacer", TileContent.Spacer),
                DashboardTile("t-link", TileContent.ViewLink("view-2")),
                DashboardTile("t-missing-link", TileContent.ViewLink("view-gone"), colSpan = 5),
            ),
        ),
        DashboardView(id = "view-2", name = "Planta alta"),
    ),
)
