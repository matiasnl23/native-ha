package com.matiasnl.hakiosk.data.ha

import app.cash.turbine.test
import com.matiasnl.hakiosk.data.ha.FakeHaWebSocketServer.Companion.state
import com.matiasnl.hakiosk.data.ha.fake.InMemoryHaConfigStore
import com.matiasnl.hakiosk.data.ha.ws.Backoff
import com.matiasnl.hakiosk.data.ha.ws.HaClientSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class WebSocketHaRepositoryTest {
    private val ha = FakeHaWebSocketServer()
    private val configStore = InMemoryHaConfigStore(HaServerConfig(ha.baseUrl, "good-token"))
    private val okHttp = OkHttpClient()

    private fun repository(
        backoffMillis: Long = 50,
        settings: HaClientSettings = HaClientSettings(heartbeatIntervalMillis = 0),
    ) = WebSocketHaRepository(
        configStore = configStore,
        okHttpClient = okHttp,
        settings = settings,
        backoff = Backoff { backoffMillis },
    )

    private var repo: WebSocketHaRepository? = null

    @After
    fun tearDown() {
        repo?.stop()
        ha.shutdown()
    }

    /** Waits (wall clock, via Turbine) until [flow] emits a value matching [predicate]. */
    private suspend fun <T> awaitValue(flow: Flow<T>, predicate: (T) -> Boolean): T {
        var found: T? = null
        flow.test(timeout = 5.seconds) {
            while (found == null) {
                val item = awaitItem()
                if (predicate(item)) found = item
            }
            cancelAndIgnoreRemainingEvents()
        }
        @Suppress("UNCHECKED_CAST")
        return found as T
    }

    @Test
    fun authOkLoadsInitialStatesAndConnects() = runTest {
        val repo = repository().also { repo = it }
        repo.start()
        repo.start() // idempotent

        awaitValue(repo.connectionState) { it == HaConnectionState.Connected }
        val entities = awaitValue(repo.entities) { it.size == 2 }
        assertEquals("off", entities.getValue("light.kitchen").state)
        assertEquals("21", entities.getValue("sensor.temp").state)

        assertEquals("good-token", ha.awaitMessage("auth")["access_token"]!!.jsonPrimitive.content)
        val subscribe = ha.awaitMessage("subscribe_events")
        assertEquals("state_changed", subscribe["event_type"]!!.jsonPrimitive.content)
        val getStates = ha.awaitMessage("get_states")
        assertTrue(getStates["id"]!!.jsonPrimitive.int > subscribe["id"]!!.jsonPrimitive.int)
        assertEquals(1, ha.connections.get())
    }

    @Test
    fun stateChangedEventsAreAppliedIncrementally() = runTest {
        val repo = repository().also { repo = it }
        repo.start()
        awaitValue(repo.connectionState) { it == HaConnectionState.Connected }

        ha.sendStateChanged("light.kitchen", state("light.kitchen", "on"))
        awaitValue(repo.entities) { it["light.kitchen"]?.state == "on" }

        ha.sendStateChanged("switch.new", state("switch.new", "off"))
        ha.sendStateChanged("sensor.temp", null)
        val entities = awaitValue(repo.entities) { "switch.new" in it && "sensor.temp" !in it }
        assertEquals(setOf("light.kitchen", "switch.new"), entities.keys)
    }

    @Test
    fun authInvalidGoesToAuthFailedWithoutRetryUntilConfigChanges() = runTest {
        configStore.save(HaServerConfig(ha.baseUrl, "wrong-token"))
        val repo = repository(backoffMillis = 10).also { repo = it }
        repo.start()

        val failed = awaitValue(repo.connectionState) { it is HaConnectionState.AuthFailed }
        assertEquals(HaConnectionState.AuthFailed("Invalid password"), failed)
        withContext(Dispatchers.IO) { Thread.sleep(300) }
        assertEquals(1, ha.connections.get())
        assertTrue(repo.connectionState.value is HaConnectionState.AuthFailed)

        configStore.save(HaServerConfig(ha.baseUrl, "good-token"))
        awaitValue(repo.connectionState) { it == HaConnectionState.Connected }
        assertEquals(2, ha.connections.get())
    }

    @Test
    fun disconnectReconnectsWithBackoffAndResyncs() = runTest {
        val repo = repository(backoffMillis = 300).also { repo = it }
        repo.start()
        awaitValue(repo.connectionState) { it == HaConnectionState.Connected }
        awaitValue(repo.entities) { it.size == 2 }

        ha.states = listOf(state("light.kitchen", "on"), state("light.hall", "off"))
        repo.connectionState.test(timeout = 5.seconds) {
            assertEquals(HaConnectionState.Connected, awaitItem())
            ha.dropConnection()
            val disconnected = awaitItem()
            assertTrue(disconnected is HaConnectionState.Disconnected)
            assertEquals(300L, (disconnected as HaConnectionState.Disconnected).retryInMillis)
            assertEquals(HaConnectionState.Connecting, awaitItem())
            assertEquals(HaConnectionState.Connected, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        val entities = awaitValue(repo.entities) { "light.hall" in it }
        assertEquals(setOf("light.kitchen", "light.hall"), entities.keys)
        assertEquals("on", entities.getValue("light.kitchen").state)
        assertEquals(2, ha.connections.get())
    }

    @Test
    fun callServiceSendsMessageAndCompletesOnResult() = runTest {
        val repo = repository().also { repo = it }
        assertTrue(repo.callService("light", "toggle", "light.kitchen").isFailure) // not connected

        repo.start()
        awaitValue(repo.connectionState) { it == HaConnectionState.Connected }

        val data = buildJsonObject { put("brightness_pct", 40) }
        val result = repo.callService("light", "turn_on", "light.kitchen", data)
        assertTrue(result.isSuccess)

        val message = ha.awaitMessage("call_service")
        assertEquals("light", message["domain"]!!.jsonPrimitive.content)
        assertEquals("turn_on", message["service"]!!.jsonPrimitive.content)
        assertEquals("light.kitchen", message["target"]!!.jsonObject["entity_id"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(40), message["service_data"]!!.jsonObject["brightness_pct"])

        ha.serviceError = "Service not found"
        val failure = repo.callService("light", "nope", "light.kitchen")
        assertEquals("Service not found", failure.exceptionOrNull()?.message)
    }

    @Test
    fun missingPongTriggersReconnect() = runTest {
        ha.respondToPing = false
        val repo = repository(
            backoffMillis = 10,
            settings = HaClientSettings(heartbeatIntervalMillis = 100, heartbeatTimeoutMillis = 100),
        ).also { repo = it }
        repo.start()

        val disconnected = awaitValue(repo.connectionState) { it is HaConnectionState.Disconnected }
        assertEquals("Heartbeat timed out", (disconnected as HaConnectionState.Disconnected).message)
        awaitValue(repo.connectionState) { it == HaConnectionState.Connected }
        assertTrue(ha.connections.get() >= 2)
    }

    private fun seedRegistry() {
        ha.registry = mapOf(
            FLOORS to Json.parseToJsonElement(
                """[
                {"floor_id":"up","name":"Upstairs","level":1,"aliases":[],"icon":null},
                {"floor_id":"attic","name":"Attic","level":null},
                {"floor_id":"ground","name":"Ground","level":0},
                {"floor_id":"annex","name":"Annex","level":0}
                ]""",
            ),
            AREAS to Json.parseToJsonElement(
                """[
                {"area_id":"kitchen","name":"Kitchen","floor_id":"ground","picture":null},
                {"area_id":"living","name":"Living room","floor_id":"ground"},
                {"area_id":"garage","name":"Garage","floor_id":null}
                ]""",
            ),
            DEVICES to Json.parseToJsonElement(
                """[
                {"id":"d1","area_id":"kitchen","name":"Hub","identifiers":[["hue","1"]]},
                {"id":"d2","area_id":null},
                {"id":"d3","area_id":"garage"}
                ]""",
            ),
            ENTITIES_DISPLAY to Json.parseToJsonElement(
                """{"entity_categories":{"0":"config"},"entities":[
                {"ei":"light.kitchen","pl":"hue","di":"d1"},
                {"ei":"sensor.temp","ai":"living","di":"d1"},
                {"ei":"switch.no_area","di":"d2"},
                {"ei":"switch.orphan"},
                {"ei":"cover.garage","di":"d3","hb":true}
                ]}""",
            ),
        )
    }

    @Test
    fun initialRegistryFetchBuildsSortedRegistry() = runTest {
        seedRegistry()
        val repo = repository().also { repo = it }
        repo.start()

        val registry = awaitValue(repo.registry) { it.areas.isNotEmpty() }
        assertEquals(listOf("annex", "ground", "up", "attic"), registry.floors.map { it.floorId })
        assertEquals(HaFloor("attic", "Attic", null), registry.floors.last())
        assertEquals(
            listOf(HaArea("garage", "Garage", null), HaArea("kitchen", "Kitchen", "ground"), HaArea("living", "Living room", "ground")),
            registry.areas,
        )
        assertEquals(
            mapOf("light.kitchen" to "kitchen", "sensor.temp" to "living", "cover.garage" to "garage"),
            registry.entityAreas,
        )
        assertEquals(HaConnectionState.Connected, repo.connectionState.value)
        val subscribed = generateSequence { ha.received.poll() }
            .filter { it["type"]!!.jsonPrimitive.content == "subscribe_events" }
            .map { it["event_type"]!!.jsonPrimitive.content }
            .toSet()
        assertEquals(
            setOf("state_changed", "floor_registry_updated", "area_registry_updated", "device_registry_updated", "entity_registry_updated"),
            subscribed,
        )
    }

    @Test
    fun registryUpdateEventsTriggerOneDebouncedRefetch() = runTest {
        seedRegistry()
        val repo = repository(settings = HaClientSettings(heartbeatIntervalMillis = 0, registryRefreshDebounceMillis = 300))
            .also { repo = it }
        repo.start()
        awaitValue(repo.registry) { it.areas.size == 3 }
        assertEquals(1, ha.commandCount(AREAS))

        ha.registry = ha.registry + (AREAS to Json.parseToJsonElement("""[{"area_id":"hall","name":"Hall"}]"""))
        ha.sendEvent("area_registry_updated")
        ha.sendEvent("entity_registry_updated")
        ha.sendEvent("area_registry_updated")

        val registry = awaitValue(repo.registry) { it.areas.size == 1 }
        assertEquals(listOf(HaArea("hall", "Hall", null)), registry.areas)
        assertEquals(2, ha.commandCount(AREAS))
        assertEquals(2, ha.commandCount(DEVICES))
        assertEquals(HaConnectionState.Connected, repo.connectionState.value)
    }

    @Test
    fun floorRegistryFailureKeepsAreasAndConnection() = runTest {
        seedRegistry()
        ha.failingCommands = setOf(FLOORS)
        val repo = repository().also { repo = it }
        repo.start()

        val registry = awaitValue(repo.registry) { it.areas.isNotEmpty() }
        assertTrue(registry.floors.isEmpty())
        assertEquals(3, registry.entityAreas.size)
        withContext(Dispatchers.IO) { Thread.sleep(200) }
        assertEquals(HaConnectionState.Connected, repo.connectionState.value)
        assertEquals(1, ha.connections.get())

        // Service calls still work on the same connection.
        assertTrue(repo.callService("light", "toggle", "light.kitchen").isSuccess)
    }

    @Test
    fun entityRegistryFallsBackToFullListWhenDisplayListFails() = runTest {
        seedRegistry()
        ha.failingCommands = setOf(ENTITIES_DISPLAY)
        ha.registry = ha.registry + (
            ENTITIES to Json.parseToJsonElement(
                """[
                {"entity_id":"light.kitchen","area_id":null,"device_id":"d1","disabled_by":null},
                {"entity_id":"light.disabled","area_id":"garage","device_id":null,"disabled_by":"user"}
                ]""",
            )
            )
        val repo = repository().also { repo = it }
        repo.start()

        val registry = awaitValue(repo.registry) { it.entityAreas.isNotEmpty() }
        assertEquals(mapOf("light.kitchen" to "kitchen"), registry.entityAreas)
        assertEquals(1, ha.connections.get())
    }

    @Test
    fun registryKeptAfterStopAndClearedOnServerChangeOrConfigClear() = runTest {
        seedRegistry()
        val repo = repository().also { repo = it }
        repo.start()
        val synced = awaitValue(repo.registry) { it.areas.isNotEmpty() }

        repo.stop()
        assertEquals(synced, repo.registry.value)

        repo.start()
        awaitValue(repo.connectionState) { it == HaConnectionState.Connected }
        assertEquals(synced, repo.registry.value)

        // A different server that serves no registry at all: only a reset can empty it.
        val other = FakeHaWebSocketServer()
        try {
            other.failingCommands = FakeHaWebSocketServer.REGISTRY_COMMANDS
            configStore.save(HaServerConfig(other.baseUrl, "good-token"))
            awaitValue(repo.registry) { it == HaRegistry() }
            awaitValue(repo.connectionState) { it == HaConnectionState.Connected }
            assertEquals(HaRegistry(), repo.registry.value)

            configStore.save(HaServerConfig(ha.baseUrl, "good-token"))
            awaitValue(repo.registry) { it.areas.isNotEmpty() }
            configStore.clear()
            awaitValue(repo.registry) { it == HaRegistry() }
        } finally {
            repo.stop()
            other.shutdown()
        }
    }

    private companion object {
        const val FLOORS = "config/floor_registry/list"
        const val AREAS = "config/area_registry/list"
        const val DEVICES = "config/device_registry/list"
        const val ENTITIES_DISPLAY = "config/entity_registry/list_for_display"
        const val ENTITIES = "config/entity_registry/list"
    }

    @Test
    fun stopGoesIdleAndClearingConfigDropsEntities() = runTest {
        val repo = repository().also { repo = it }
        repo.start()
        awaitValue(repo.connectionState) { it == HaConnectionState.Connected }

        repo.stop()
        assertEquals(HaConnectionState.Idle, repo.connectionState.value)
        assertTrue(repo.callService("light", "toggle", "light.kitchen").isFailure)

        repo.start()
        awaitValue(repo.connectionState) { it == HaConnectionState.Connected }
        assertEquals(2, ha.connections.get())

        configStore.clear()
        awaitValue(repo.connectionState) { it == HaConnectionState.Idle }
        val entities = awaitValue(repo.entities) { it.isEmpty() }
        assertNull(entities["light.kitchen"])
    }
}
