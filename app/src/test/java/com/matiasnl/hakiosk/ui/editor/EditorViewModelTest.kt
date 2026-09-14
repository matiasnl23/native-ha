package com.matiasnl.hakiosk.ui.editor

import com.matiasnl.hakiosk.data.dashboard.DashboardLayout
import com.matiasnl.hakiosk.data.dashboard.DashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.DashboardView
import com.matiasnl.hakiosk.data.dashboard.InMemoryDashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.TileContent
import com.matiasnl.hakiosk.data.dashboard.defaultDashboardLayout
import com.matiasnl.hakiosk.data.dashboard.newDashboardTile
import com.matiasnl.hakiosk.data.ha.HaEntity
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

/** A single-view layout, "Principal", whose tiles are entity tiles for [entityIds] in order. */
private fun layoutWithTiles(vararg entityIds: String): DashboardLayout = DashboardLayout(
    views = listOf(
        DashboardView(
            id = "view-1",
            name = "Principal",
            tiles = entityIds.map { newDashboardTile(TileContent.Entity(it)) },
        ),
    ),
)

/**
 * [DashboardLayoutStore] whose [layout] flow only emits once [release] is called, to reproduce the
 * race between the async initial load in [EditorViewModel]'s init and edits made in the meantime.
 */
private class GatedDashboardLayoutStore(private val stored: DashboardLayout) : DashboardLayoutStore {
    private val gate = CompletableDeferred<Unit>()

    override val layout: Flow<DashboardLayout> = flow {
        gate.await()
        emit(stored)
    }

    override suspend fun update(transform: (DashboardLayout) -> DashboardLayout) {}

    fun release() {
        gate.complete(Unit)
    }
}

class EditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun TestScopeViewModel(
        entities: List<HaEntity>,
        initialLayout: DashboardLayout = defaultDashboardLayout(),
    ): Triple<EditorViewModel, FakeHaRepository, InMemoryDashboardLayoutStore> {
        val repository = FakeHaRepository(initialEntities = entities)
        val layoutStore = InMemoryDashboardLayoutStore(initialLayout)
        val viewModel = EditorViewModel(repository, layoutStore)
        return Triple(viewModel, repository, layoutStore)
    }

    @Test
    fun `loads existing tiles with their friendly names`() = runTest {
        val (viewModel, _, _) = TestScopeViewModel(
            entities = listOf(entity("light.kitchen", "Kitchen"), entity("light.living_room", "Living room")),
            initialLayout = layoutWithTiles("light.living_room"),
        )
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        val state = viewModel.uiState.value
        assertEquals(listOf("light.living_room"), state.currentTiles.map { it.entityId })
        assertEquals("Living room", state.currentTiles.single().friendlyName)
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
            initialLayout = layoutWithTiles("light.kitchen"),
        )
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.removeTile("light.kitchen")

        assertTrue(viewModel.uiState.value.currentTiles.isEmpty())
    }

    @Test
    fun `move up and down reorder tiles`() = runTest {
        val (viewModel, _, _) = TestScopeViewModel(
            entities = listOf(entity("light.a", "A"), entity("light.b", "B"), entity("light.c", "C")),
            initialLayout = layoutWithTiles("light.a", "light.b", "light.c"),
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
            initialLayout = layoutWithTiles("light.kitchen"),
        )
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.setLabel("light.kitchen", "Cocina")
        assertEquals("Cocina", viewModel.uiState.value.currentTiles.single().label)

        viewModel.setLabel("light.kitchen", "  ")
        assertNull(viewModel.uiState.value.currentTiles.single().label)
    }

    @Test
    fun `save persists the working tile list`() = runTest {
        val (viewModel, _, layoutStore) = TestScopeViewModel(entities = listOf(entity("light.kitchen", "Kitchen")))
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.addTile("light.kitchen")
        var saved = false
        viewModel.save { saved = true }

        assertTrue(saved)
        val savedEntityIds = layoutStore.layout.value.views.single().tiles
            .mapNotNull { (it.content as? TileContent.Entity)?.entityId }
        assertEquals(listOf("light.kitchen"), savedEntityIds)
    }

    @Test
    fun `save preserves other views and non-entity tiles untouched`() = runTest {
        val otherView = DashboardView(id = "view-2", name = "Bedroom")
        val principal = DashboardView(
            id = "view-1",
            name = "Principal",
            tiles = listOf(newDashboardTile(TileContent.Spacer)),
        )
        val (viewModel, _, layoutStore) = TestScopeViewModel(
            entities = listOf(entity("light.kitchen", "Kitchen")),
            initialLayout = DashboardLayout(views = listOf(principal, otherView)),
        )
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        viewModel.addTile("light.kitchen")
        viewModel.save {}

        val saved = layoutStore.layout.value
        assertEquals(listOf("view-1", "view-2"), saved.views.map { it.id })
        assertEquals("Bedroom", saved.views[1].name)
        assertEquals(TileContent.Spacer, saved.views[0].tiles.first().content)
        assertEquals(
            "light.kitchen",
            (saved.views[0].tiles.last().content as TileContent.Entity).entityId,
        )
    }

    @Test
    fun `isLoaded is false until the stored tiles arrive`() = runTest {
        val repository = FakeHaRepository(initialEntities = listOf(entity("light.kitchen", "Kitchen")))
        val layoutStore = GatedDashboardLayoutStore(defaultDashboardLayout())
        val viewModel = EditorViewModel(repository, layoutStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        assertFalse(viewModel.uiState.value.isLoaded)

        layoutStore.release()

        assertTrue(viewModel.uiState.value.isLoaded)
    }

    @Test
    fun `tile added before the stored load arrives is kept alongside the loaded tiles`() = runTest {
        val repository = FakeHaRepository(
            initialEntities = listOf(entity("light.kitchen", "Kitchen"), entity("light.living_room", "Living room")),
        )
        val layoutStore = GatedDashboardLayoutStore(layoutWithTiles("light.living_room"))
        val viewModel = EditorViewModel(repository, layoutStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        // The user adds a tile before the stored ("light.living_room") list has loaded.
        viewModel.addTile("light.kitchen")
        assertEquals(listOf("light.kitchen"), viewModel.uiState.value.currentTiles.map { it.entityId })

        layoutStore.release()

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
        val layoutStore = GatedDashboardLayoutStore(layoutWithTiles("light.living_room", "light.kitchen"))
        val viewModel = EditorViewModel(repository, layoutStore)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }

        // Removing a tile that isn't loaded yet queues the edit; nothing to remove yet.
        viewModel.removeTile("light.kitchen")
        assertTrue(viewModel.uiState.value.currentTiles.isEmpty())

        layoutStore.release()

        assertEquals(listOf("light.living_room"), viewModel.uiState.value.currentTiles.map { it.entityId })
    }
}
