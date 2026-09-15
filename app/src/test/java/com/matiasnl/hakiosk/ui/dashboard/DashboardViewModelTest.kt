package com.matiasnl.hakiosk.ui.dashboard

import com.matiasnl.hakiosk.data.dashboard.DashboardGrid
import com.matiasnl.hakiosk.data.dashboard.DashboardLayout
import com.matiasnl.hakiosk.data.dashboard.DashboardTile
import com.matiasnl.hakiosk.data.dashboard.DashboardView
import com.matiasnl.hakiosk.data.dashboard.FakeDashboardIdProvider
import com.matiasnl.hakiosk.data.dashboard.setGrid
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPlacement
import org.junit.Assert.assertSame
import com.matiasnl.hakiosk.data.dashboard.DashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.InMemoryDashboardLayoutStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
import com.matiasnl.hakiosk.data.dashboard.TileStyle
import com.matiasnl.hakiosk.data.dashboard.TileTapAction
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileDetailsRequest
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileSummary
import com.matiasnl.hakiosk.ui.dashboard.tiles.testEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import com.matiasnl.hakiosk.data.dashboard.DashboardViewPreferences
import com.matiasnl.hakiosk.data.dashboard.InMemoryDashboardViewPreferencesStore
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

/** A store whose layout hasn't loaded yet (never emits); records whether anything was written. */
private class NotYetLoadedLayoutStore : DashboardLayoutStore {
    var updates = 0
    override val layout: Flow<DashboardLayout> = emptyFlow()
    override suspend fun update(transform: (DashboardLayout) -> DashboardLayout) {
        updates++
    }
}

/** In-memory store that counts [update] calls, to assert Listo writes exactly once. */
private class CountingLayoutStore(initial: DashboardLayout) : DashboardLayoutStore {
    private val delegate = InMemoryDashboardLayoutStore(initial)
    var updates = 0
    override val layout: StateFlow<DashboardLayout> = delegate.layout
    override suspend fun update(transform: (DashboardLayout) -> DashboardLayout) {
        updates++
        delegate.update(transform)
    }
}

class DashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `edit mode cannot start before the stored layout loads, so Listo can't overwrite it`() = runTest {
        val store = NotYetLoadedLayoutStore()
        val viewModel = DashboardViewModel(FakeHaRepository(initialEntities = emptyList()), store)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.enterEditMode()
        viewModel.doneEditMode()

        assertFalse(viewModel.uiState.value.isEditing)
        assertEquals(0, store.updates)
    }

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
    fun `swiping a light tile sets its brightness, and swiping it to zero turns it off`() = runTest {
        val repository = FakeHaRepository(initialEntities = listOf(entity("light.kitchen", "off", "Kitchen")))
        val viewModel = DashboardViewModel(repository, InMemoryDashboardLayoutStore(layoutWithTile("light.kitchen")))
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onTileBrightnessChange(viewModel.uiState.value.entityTiles().single(), 40)
        assertEquals("on", repository.entities.value.getValue("light.kitchen").state)

        viewModel.onTileBrightnessChange(viewModel.uiState.value.entityTiles().single(), 0)
        assertEquals("off", repository.entities.value.getValue("light.kitchen").state)
    }

    @Test
    fun `brightness swipes are ignored for other domains`() = runTest {
        val repository = FakeHaRepository(initialEntities = listOf(entity("switch.fan", "off", "Fan")))
        val viewModel = DashboardViewModel(repository, InMemoryDashboardLayoutStore(layoutWithTile("switch.fan")))
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onTileBrightnessChange(viewModel.uiState.value.entityTiles().single(), 40)

        assertEquals("off", repository.entities.value.getValue("switch.fan").state)
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
    fun `long press on a domain without a details panel opens nothing`() = runTest {
        val repository = FakeHaRepository(
            initialEntities = listOf(entity("switch.coffee", "off", "Coffee"), entity("sensor.temp", "18", "Temp")),
        )
        val layoutStore = InMemoryDashboardLayoutStore(layoutWithTiles("switch.coffee" to null, "sensor.temp" to null))
        val viewModel = DashboardViewModel(repository, layoutStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.uiState.value.entityTiles().forEach { tile ->
            assertFalse(tile.hasDetails)
            viewModel.onTileLongPress(tile)
        }

        assertEquals(null, viewModel.detailsRequest.value)
        assertEquals("off", repository.entities.value.getValue("switch.coffee").state)
    }

    // --- Smart tiles: summaries and details panels ---

    @Test
    fun `light tiles carry their summary and a details panel`() = runTest {
        val repository = FakeHaRepository(
            initialEntities = listOf(
                testEntity("light.dim", "on", """{"friendly_name":"Dim","supported_color_modes":["brightness"],"brightness":153}"""),
                testEntity("light.plain", "on", """{"supported_color_modes":["onoff"]}"""),
                testEntity("light.off", "off", """{"supported_color_modes":["brightness"]}"""),
            ),
        )
        val layoutStore = InMemoryDashboardLayoutStore(
            layoutWithTiles("light.dim" to null, "light.plain" to null, "light.off" to null, "light.gone" to null),
        )
        val viewModel = DashboardViewModel(repository, layoutStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        val tiles = viewModel.uiState.value.entityTiles()
        assertEquals(
            listOf(
                TileSummary.Light(true, 60, supportsBrightness = true),
                TileSummary.Light(true, null, supportsBrightness = false),
                TileSummary.Light(false, null, supportsBrightness = true),
                TileSummary.Default,
            ),
            tiles.map { it.summary },
        )
        assertEquals(listOf(true, true, true, false), tiles.map { it.hasDetails })
    }

    @Test
    fun `an entity update recomputes only that entity's summary`() = runTest {
        val repository = FakeHaRepository(
            initialEntities = listOf(
                testEntity("light.a", "on", """{"supported_color_modes":["brightness"],"brightness":255}"""),
                testEntity("light.b", "on", """{"supported_color_modes":["brightness"],"brightness":128}"""),
            ),
        )
        val viewModel = DashboardViewModel(repository, InMemoryDashboardLayoutStore(layoutWithTiles("light.a" to null, "light.b" to null)))
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val before = viewModel.uiState.value.entityTiles()

        viewModel.onTileClick(before[0]) // Toggles light.a off; light.b's entity instance is untouched.

        val after = viewModel.uiState.value.entityTiles()
        assertEquals(TileSummary.Light(false, null, supportsBrightness = true), after[0].summary)
        assertSame(before[1].summary, after[1].summary)
    }

    @Test
    fun `long press on a light opens its details panel, and dismiss closes it`() = runTest {
        val (viewModel, _) = editingViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val tile = viewModel.uiState.value.entityTiles().single()

        viewModel.onTileLongPress(tile)

        assertEquals(TileDetailsRequest(tile.id, "light.kitchen", "Kitchen", "light"), viewModel.detailsRequest.value)
        viewModel.dismissDetails()
        assertEquals(null, viewModel.detailsRequest.value)
    }

    @Test
    fun `long press never opens details while editing, and entering edit mode closes an open panel`() = runTest {
        val (viewModel, _) = editingViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.onTileLongPress(viewModel.uiState.value.entityTiles().single())

        viewModel.enterEditMode()
        assertEquals(null, viewModel.detailsRequest.value)

        viewModel.onTileLongPress(viewModel.uiState.value.entityTiles().single())
        assertEquals(null, viewModel.detailsRequest.value)
    }

    @Test
    fun `long press on a missing light opens nothing`() = runTest {
        val viewModel = DashboardViewModel(FakeHaRepository(emptyList()), InMemoryDashboardLayoutStore(layoutWithTile("light.gone")))
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onTileLongPress(viewModel.uiState.value.entityTiles().single())

        assertEquals(null, viewModel.detailsRequest.value)
    }

    @Test
    fun `a light tile set to open controls opens the panel on tap instead of toggling`() = runTest {
        val repository = FakeHaRepository(initialEntities = listOf(entity("light.kitchen", "off", "Kitchen")))
        val layout = DashboardLayout(
            views = listOf(
                DashboardView(
                    id = "view-1",
                    name = "Principal",
                    tiles = listOf(DashboardTile("t", TileContent.Entity("light.kitchen", tapAction = TileTapAction.OPEN_DETAILS))),
                ),
            ),
        )
        val viewModel = DashboardViewModel(repository, InMemoryDashboardLayoutStore(layout))
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val tile = viewModel.uiState.value.entityTiles().single()

        assertTrue(tile.isActionable)
        viewModel.onTileClick(tile)

        assertEquals("t", viewModel.detailsRequest.value?.tileId)
        assertEquals("off", repository.entities.value.getValue("light.kitchen").state)
    }

    @Test
    fun `tapping an alarm tile opens its panel and never arms or disarms, whatever its stored preference`() = runTest {
        val repository = FakeHaRepository(
            initialEntities = listOf(
                testEntity("alarm_control_panel.home", "armed_away", """{"friendly_name":"Alarma","supported_features":3}"""),
            ),
        )
        val layout = DashboardLayout(
            views = listOf(
                DashboardView(
                    id = "view-1",
                    name = "Principal",
                    tiles = listOf(
                        DashboardTile("t", TileContent.Entity("alarm_control_panel.home", tapAction = TileTapAction.TOGGLE)),
                    ),
                ),
            ),
        )
        val viewModel = DashboardViewModel(repository, InMemoryDashboardLayoutStore(layout))
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val tile = viewModel.uiState.value.entityTiles().single()

        assertEquals(TileSummary.Alarm(com.matiasnl.hakiosk.data.ha.domain.AlarmPanelState.ARMED_AWAY), tile.summary)
        assertTrue(tile.isActionable)
        viewModel.onTileClick(tile)

        assertEquals(TileDetailsRequest("t", "alarm_control_panel.home", "Alarma", "alarm_control_panel"), viewModel.detailsRequest.value)
        assertEquals("armed_away", repository.entities.value.getValue("alarm_control_panel.home").state)
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
        assertEquals(
            ViewLinkTileUiState("t-link", "view-2", "Planta alta", rawLabel = null, targetViewName = "Planta alta"),
            state.tiles[2],
        )
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
    fun `a tap action change goes to the working copy, Cancelar discards it and Listo persists it`() = runTest {
        val (viewModel, layoutStore) = editingViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        fun storedTapAction() = (layoutStore.layout.value.views.single().tiles.single().content as TileContent.Entity).tapAction

        viewModel.enterEditMode()
        val tileId = viewModel.uiState.value.entityTiles().single().id
        viewModel.setEditTileTapAction(tileId, TileTapAction.OPEN_DETAILS)

        assertEquals(TileTapAction.OPEN_DETAILS, viewModel.uiState.value.entityTiles().single().tapAction)
        assertTrue(viewModel.uiState.value.isDirty)
        assertEquals(TileTapAction.DEFAULT, storedTapAction())

        viewModel.cancelEditMode()
        assertEquals(TileTapAction.DEFAULT, viewModel.uiState.value.entityTiles().single().tapAction)
        assertEquals(TileTapAction.DEFAULT, storedTapAction())

        viewModel.enterEditMode()
        viewModel.setEditTileTapAction(tileId, TileTapAction.OPEN_DETAILS)
        viewModel.doneEditMode()
        assertEquals(TileTapAction.OPEN_DETAILS, storedTapAction())
    }

    @Test
    fun `a style change goes to the working copy and Listo persists it`() = runTest {
        val (viewModel, layoutStore) = editingViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        fun storedStyle() = (layoutStore.layout.value.views.single().tiles.single().content as TileContent.Entity).style

        viewModel.enterEditMode()
        val tileId = viewModel.uiState.value.entityTiles().single().id
        viewModel.setEditTileStyle(tileId, TileStyle.QUICK_ADJUST)

        assertEquals(TileStyle.QUICK_ADJUST, viewModel.uiState.value.entityTiles().single().style)
        assertEquals(TileStyle.DEFAULT, storedStyle())

        viewModel.doneEditMode()
        assertEquals(TileStyle.QUICK_ADJUST, storedStyle())
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
    fun `moving a tile while editing re-packs the working copy and cancel discards it`() = runTest {
        val (viewModel, layoutStore) = editingViewModel(layout = mixedLayout())
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val originalLayout = layoutStore.layout.value
        viewModel.enterEditMode()
        val packingBefore = viewModel.uiState.value.packing

        viewModel.moveEditTile(0, 3) // The 2×2 light goes after the oversized link; "＋" stays last.

        val state = viewModel.uiState.value
        assertEquals(listOf("t-spacer", "t-link", "t-missing-link", "t-light", AddTileUiState.ID), state.tiles.map { it.id })
        assertTrue(state.isDirty)
        assertTrue(packingBefore !== state.packing)
        assertEquals(
            listOf(
                GridPlacement(0, 0, 1, 1),
                GridPlacement(0, 1, 1, 1),
                GridPlacement(1, 0, 3, 1),
                GridPlacement(2, 0, 2, 2),
                GridPlacement(0, 2, 1, 1), // "＋" back-fills the first hole.
            ),
            state.packing.placements,
        )
        assertEquals(originalLayout, layoutStore.layout.value)

        viewModel.cancelEditMode()

        assertFalse(viewModel.uiState.value.isEditing)
        assertEquals(listOf("t-light", "t-spacer", "t-link", "t-missing-link"), viewModel.uiState.value.tiles.map { it.id })
        assertEquals(originalLayout, layoutStore.layout.value)
    }

    // --- Multiple views ---

    private fun multiViewModel(
        layout: DashboardLayout = threeViewLayout(),
        preferences: InMemoryDashboardViewPreferencesStore = InMemoryDashboardViewPreferencesStore(),
        repository: FakeHaRepository = FakeHaRepository(listOf(entity("light.kitchen", "off", "Kitchen"))),
    ): Triple<DashboardViewModel, InMemoryDashboardLayoutStore, InMemoryDashboardViewPreferencesStore> {
        val layoutStore = InMemoryDashboardLayoutStore(layout)
        val viewModel = DashboardViewModel(
            repository,
            layoutStore,
            FakeDashboardIdProvider(),
            viewPreferencesStore = preferences,
        )
        return Triple(viewModel, layoutStore, preferences)
    }

    @Test
    fun `builds one page per view with its own grid, tiles and packing`() = runTest {
        val (viewModel, _, _) = multiViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        val state = viewModel.uiState.value
        assertTrue(state.isLoaded)
        assertEquals(listOf("v1", "v2", "v3"), state.pages.map { it.viewId })
        assertEquals(listOf("Principal", "Planta alta", "Vacía"), state.pages.map { it.name })
        assertEquals(0, state.currentPage)

        val (first, second, third) = state.pages
        assertEquals(DashboardGrid(columns = 2, rows = 2), first.grid)
        assertEquals(listOf("a-light", "a-link"), first.tiles.map { it.id })
        assertEquals(listOf(GridPlacement(0, 0, 2, 1), GridPlacement(1, 0, 1, 1)), first.packing.placements)

        assertEquals(DashboardGrid(columns = 3, rows = 1), second.grid)
        assertEquals(listOf("b-spacer", "b-link"), second.tiles.map { it.id })
        assertEquals(listOf(GridPlacement(0, 0, 1, 2), GridPlacement(0, 1, 1, 1)), second.packing.placements)

        assertTrue(third.tiles.isEmpty())
        assertEquals(0, third.packing.placements.size)
    }

    @Test
    fun `entity updates re-pack no page and keep unaffected pages as the same instance`() = runTest {
        val repository = FakeHaRepository(listOf(entity("light.kitchen", "off", "Kitchen")))
        val (viewModel, _, _) = multiViewModel(repository = repository)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val before = viewModel.uiState.value

        viewModel.onTileClick(before.pages[0].tiles.filterIsInstance<DashboardTileUiState>().single())

        val after = viewModel.uiState.value
        assertTrue((after.pages[0].tiles[0] as DashboardTileUiState).isOn)
        before.pages.zip(after.pages).forEach { (old, new) -> assertSame(old.packing, new.packing) }
        assertSame(before.pages[1], after.pages[1])
        assertSame(before.pages[2], after.pages[2])
    }

    @Test
    fun `editing one view re-packs only that view's page`() = runTest {
        val (viewModel, _, _) = multiViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()
        val before = viewModel.uiState.value

        viewModel.addSpacerTile()

        val after = viewModel.uiState.value
        assertTrue(before.pages[0].packing !== after.pages[0].packing)
        assertSame(before.pages[1].packing, after.pages[1].packing)
        assertSame(before.pages[2].packing, after.pages[2].packing)
    }

    @Test
    fun `opens the persisted last view`() = runTest {
        val (viewModel, _, _) = multiViewModel(
            preferences = InMemoryDashboardViewPreferencesStore(DashboardViewPreferences(lastViewId = "v2")),
        )
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        assertEquals(1, viewModel.uiState.value.currentPage)
        assertEquals("v2", viewModel.uiState.value.viewId)
    }

    @Test
    fun `falls back to the first view when the persisted last view no longer exists`() = runTest {
        val (viewModel, _, _) = multiViewModel(
            preferences = InMemoryDashboardViewPreferencesStore(DashboardViewPreferences(lastViewId = "deleted")),
        )
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        assertEquals(0, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `settled pages become current and only the last one is persisted after the debounce`() = runTest {
        val (viewModel, _, preferences) = multiViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onPageSettled("v2")
        advanceTimeBy(LAST_VIEW_SAVE_DEBOUNCE_MILLIS / 2)
        viewModel.onPageSettled("v3")
        assertEquals(2, viewModel.uiState.value.currentPage)
        assertEquals(0, preferences.writes)

        advanceTimeBy(LAST_VIEW_SAVE_DEBOUNCE_MILLIS + 1)

        assertEquals(1, preferences.writes)
        assertEquals("v3", preferences.preferences.value.lastViewId)
    }

    @Test
    fun `settling on the already persisted view or an unknown view writes nothing`() = runTest {
        val (viewModel, _, preferences) = multiViewModel(
            preferences = InMemoryDashboardViewPreferencesStore(DashboardViewPreferences(lastViewId = "v2")),
        )
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.onPageSettled("v2")
        viewModel.onPageSettled("nope")
        advanceTimeBy(LAST_VIEW_SAVE_DEBOUNCE_MILLIS * 2)

        assertEquals(1, viewModel.uiState.value.currentPage)
        assertEquals(0, preferences.writes)
    }

    @Test
    fun `tapping a view link makes its target the current page and persists it`() = runTest {
        val (viewModel, _, preferences) = multiViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val link = viewModel.uiState.value.pages[0].tiles.filterIsInstance<ViewLinkTileUiState>().single()
        assertTrue(link.hasTarget)

        viewModel.onViewLinkClick(link)

        assertEquals(1, viewModel.uiState.value.currentPage)
        advanceTimeBy(LAST_VIEW_SAVE_DEBOUNCE_MILLIS + 1)
        assertEquals("v2", preferences.preferences.value.lastViewId)
    }

    @Test
    fun `tapping a link to a missing view is a no-op`() = runTest {
        val (viewModel, _, _) = multiViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.onPageSettled("v2")
        val stale = viewModel.uiState.value.pages[1].tiles.filterIsInstance<ViewLinkTileUiState>().single()
        assertFalse(stale.hasTarget)

        viewModel.onViewLinkClick(stale)

        assertEquals(1, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `tapping a view link while editing does not navigate`() = runTest {
        val (viewModel, _, _) = multiViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()
        val link = viewModel.uiState.value.pages[0].tiles.filterIsInstance<ViewLinkTileUiState>().single()

        viewModel.onViewLinkClick(link)

        assertEquals(0, viewModel.uiState.value.currentPage)
        assertEquals("v1", viewModel.uiState.value.viewId)
    }

    @Test
    fun `edit mode targets the current view and page settles are ignored while editing`() = runTest {
        val (viewModel, _, _) = multiViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.onPageSettled("v2")

        viewModel.enterEditMode()
        viewModel.addSpacerTile()
        viewModel.onPageSettled("v1")

        val state = viewModel.uiState.value
        assertEquals(1, state.currentPage)
        assertEquals(listOf("b-spacer", "b-link", "id-1", AddTileUiState.ID), state.tiles.map { it.id })
        assertFalse(state.pages[0].tiles.any { it is AddTileUiState })
        assertEquals(listOf(LinkTargetOption("v1", "Principal"), LinkTargetOption("v3", "Vacía")), state.linkTargets)
    }

    @Test
    fun `switching the edited view moves the current page and the add tile, and tile ops follow it`() = runTest {
        val (viewModel, _, _) = multiViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()

        viewModel.selectEditingView("v3")
        viewModel.addSpacerTile()

        val state = viewModel.uiState.value
        assertEquals(2, state.currentPage)
        assertEquals(listOf("id-1", AddTileUiState.ID), state.pages[2].tiles.map { it.id })
        assertFalse(state.pages[0].tiles.any { it is AddTileUiState })
        assertEquals(listOf("v1", "v2"), state.linkTargets.map { it.viewId })
    }

    @Test
    fun `the edited view cannot change while a tile drag is in progress`() = runTest {
        val (viewModel, _, _) = multiViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()

        viewModel.setDragActive(true)
        viewModel.selectEditingView("v2")
        assertEquals(0, viewModel.uiState.value.currentPage)

        viewModel.setDragActive(false)
        viewModel.selectEditingView("v2")
        assertEquals(1, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `entering edit mode again while editing keeps the working copy`() = runTest {
        val (viewModel, _, _) = multiViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()
        viewModel.addSpacerTile()

        viewModel.enterEditMode()

        assertTrue(viewModel.uiState.value.isDirty)
    }

    @Test
    fun `Listo persists once and stays on the edited view`() = runTest {
        val store = CountingLayoutStore(threeViewLayout())
        val preferences = InMemoryDashboardViewPreferencesStore()
        val viewModel = DashboardViewModel(
            FakeHaRepository(emptyList()),
            store,
            FakeDashboardIdProvider(),
            viewPreferencesStore = preferences,
        )
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()
        viewModel.selectEditingView("v2")
        viewModel.addSpacerTile()

        viewModel.doneEditMode()

        val state = viewModel.uiState.value
        assertFalse(state.isEditing)
        assertEquals(1, store.updates)
        assertEquals(1, state.currentPage)
        assertEquals(3, store.layout.value.views[1].tiles.size)
        advanceTimeBy(LAST_VIEW_SAVE_DEBOUNCE_MILLIS + 1)
        assertEquals("v2", preferences.preferences.value.lastViewId)
    }

    @Test
    fun `Cancelar discards the working copy and stays on the edited view`() = runTest {
        val (viewModel, layoutStore, _) = multiViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val original = layoutStore.layout.value
        viewModel.enterEditMode()
        viewModel.selectEditingView("v3")
        viewModel.addSpacerTile()

        viewModel.cancelEditMode()

        assertFalse(viewModel.uiState.value.isEditing)
        assertEquals(original, layoutStore.layout.value)
        assertEquals(2, viewModel.uiState.value.currentPage)
        assertTrue(viewModel.uiState.value.pages[2].tiles.isEmpty())
    }

    @Test
    fun `adding a view appends a page and edits it`() = runTest {
        val (viewModel, _, _) = multiViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()

        viewModel.addView(" Vista 4 ")
        viewModel.addView("")

        val state = viewModel.uiState.value
        assertEquals(listOf("v1", "v2", "v3", "id-1"), state.pages.map { it.viewId })
        assertEquals("Vista 4", state.pages[3].name)
        assertEquals(3, state.currentPage)
        assertEquals(listOf(AddTileUiState.ID), state.tiles.map { it.id })
    }

    @Test
    fun `renaming a view updates its page and the links showing its name`() = runTest {
        val (viewModel, _, _) = multiViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()

        viewModel.renameView("v2", "Arriba")

        val state = viewModel.uiState.value
        assertEquals("Arriba", state.pages[1].name)
        assertEquals("Arriba", (state.pages[0].tiles[1] as ViewLinkTileUiState).label)
    }

    @Test
    fun `deleting the edited view edits its neighbour and removes links to it`() = runTest {
        val (viewModel, _, _) = multiViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()
        viewModel.selectEditingView("v2")

        viewModel.removeView("v2")

        val state = viewModel.uiState.value
        assertEquals(listOf("v1", "v3"), state.pages.map { it.viewId })
        assertEquals("v3", state.viewId)
        assertEquals(listOf("a-light"), state.pages[0].tiles.map { it.id })
    }

    @Test
    fun `the last view can't be deleted`() = runTest {
        val (viewModel, _, _) = multiViewModel(layout = layoutWithTile("light.kitchen"))
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()

        viewModel.removeView("view-1")

        assertEquals(listOf("view-1"), viewModel.uiState.value.pages.map { it.viewId })
        assertFalse(viewModel.uiState.value.isDirty)
    }

    @Test
    fun `reordering views keeps the edited view as the current page`() = runTest {
        val (viewModel, _, _) = multiViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()

        viewModel.moveView(0, 2)

        val state = viewModel.uiState.value
        assertEquals(listOf("v2", "v3", "v1"), state.pages.map { it.viewId })
        assertEquals(2, state.currentPage)
        assertEquals("v1", state.viewId)
    }

    @Test
    fun `Cancelar discards added, renamed, deleted and reordered views`() = runTest {
        val (viewModel, layoutStore, _) = multiViewModel()
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        val original = layoutStore.layout.value
        viewModel.enterEditMode()
        viewModel.addView("Nueva")
        viewModel.renameView("v1", "Otra")
        viewModel.removeView("v2")
        viewModel.moveView(0, 1)

        viewModel.cancelEditMode()

        val state = viewModel.uiState.value
        assertEquals(original, layoutStore.layout.value)
        assertEquals(listOf("v1", "v2", "v3"), state.pages.map { it.viewId })
        assertEquals(0, state.currentPage) // The added (edited) view is gone: back to the view edit mode started on.
    }

    @Test
    fun `Listo persists added views once and stays on the new view`() = runTest {
        val store = CountingLayoutStore(threeViewLayout())
        val viewModel = DashboardViewModel(FakeHaRepository(emptyList()), store, FakeDashboardIdProvider())
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.enterEditMode()
        viewModel.addView("Nueva")

        viewModel.doneEditMode()

        assertEquals(1, store.updates)
        assertEquals(listOf("v1", "v2", "v3", "id-1"), store.layout.value.views.map { it.id })
        assertEquals(3, viewModel.uiState.value.currentPage)
    }

    // --- Inactivity return ---

    private fun kotlinx.coroutines.test.TestScope.inactivityViewModel(minutes: Int): Pair<DashboardViewModel, InMemoryDashboardViewPreferencesStore> {
        val preferences = InMemoryDashboardViewPreferencesStore(DashboardViewPreferences(inactivityReturnMinutes = minutes))
        val viewModel = DashboardViewModel(
            FakeHaRepository(emptyList()),
            InMemoryDashboardLayoutStore(threeViewLayout()),
            FakeDashboardIdProvider(),
            viewPreferencesStore = preferences,
            clock = { testScheduler.currentTime },
        )
        return viewModel to preferences
    }

    @Test
    fun `inactivity return is disabled by default`() = runTest {
        val (viewModel, _) = inactivityViewModel(minutes = 0)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.onPageSettled("v3")

        advanceTimeBy(60 * 60_000L)

        assertEquals(0, viewModel.uiState.value.inactivityReturnMinutes)
        assertEquals(2, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `returns to the first view after the configured minutes without touches, and touches reset it`() = runTest {
        val (viewModel, _) = inactivityViewModel(minutes = 1)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.onPageSettled("v3")

        advanceTimeBy(59_000)
        viewModel.onUserActivity()
        advanceTimeBy(59_000) // 118 s in, 59 s after the touch.
        assertEquals(2, viewModel.uiState.value.currentPage)

        advanceTimeBy(1_001)
        assertEquals(0, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `inactivity return never fires while editing and restarts after leaving edit mode`() = runTest {
        val (viewModel, _) = inactivityViewModel(minutes = 1)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.onPageSettled("v2")
        viewModel.enterEditMode()

        advanceTimeBy(5 * 60_000L)
        assertTrue(viewModel.uiState.value.isEditing)
        assertEquals(1, viewModel.uiState.value.currentPage)

        viewModel.cancelEditMode()
        advanceTimeBy(59_000)
        assertEquals(1, viewModel.uiState.value.currentPage)
        advanceTimeBy(1_001)
        assertEquals(0, viewModel.uiState.value.currentPage)
    }

    @Test
    fun `changing the inactivity setting persists it and takes effect`() = runTest {
        val (viewModel, preferences) = inactivityViewModel(minutes = 0)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        viewModel.onPageSettled("v2")

        viewModel.setInactivityReturnMinutes(5)
        assertEquals(5, preferences.preferences.value.inactivityReturnMinutes)
        assertEquals(5, viewModel.uiState.value.inactivityReturnMinutes)

        advanceTimeBy(5 * 60_000L + 1)
        assertEquals(0, viewModel.uiState.value.currentPage)
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

/**
 * v1 "Principal" (2×2): a 2-wide light and a link to v2. v2 "Planta alta" (3×1): a 1×2 spacer and a
 * link to a missing view. v3 "Vacía": no tiles.
 */
private fun threeViewLayout() = DashboardLayout(
    views = listOf(
        DashboardView(
            id = "v1",
            name = "Principal",
            grid = DashboardGrid(columns = 2, rows = 2),
            tiles = listOf(
                DashboardTile("a-light", TileContent.Entity("light.kitchen"), colSpan = 2),
                DashboardTile("a-link", TileContent.ViewLink("v2")),
            ),
        ),
        DashboardView(
            id = "v2",
            name = "Planta alta",
            grid = DashboardGrid(columns = 3, rows = 1),
            tiles = listOf(
                DashboardTile("b-spacer", TileContent.Spacer, rowSpan = 2),
                DashboardTile("b-link", TileContent.ViewLink("gone")),
            ),
        ),
        DashboardView(id = "v3", name = "Vacía"),
    ),
)

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
