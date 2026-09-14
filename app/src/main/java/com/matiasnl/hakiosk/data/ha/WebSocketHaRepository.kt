package com.matiasnl.hakiosk.data.ha

import com.matiasnl.hakiosk.data.ha.rest.HaRestClient
import com.matiasnl.hakiosk.data.ha.ws.Backoff
import com.matiasnl.hakiosk.data.ha.ws.ExponentialBackoff
import com.matiasnl.hakiosk.data.ha.ws.HaAuthInvalidException
import com.matiasnl.hakiosk.data.ha.ws.HaClientSettings
import com.matiasnl.hakiosk.data.ha.ws.HaConnectionCallbacks
import com.matiasnl.hakiosk.data.ha.ws.HaProtocol
import com.matiasnl.hakiosk.data.ha.ws.HaRegistryParser
import com.matiasnl.hakiosk.data.ha.ws.HaRequestException
import com.matiasnl.hakiosk.data.ha.ws.HaWebSocketConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/**
 * [HaRepository] over the Home Assistant WebSocket API.
 *
 * - [start] observes [configStore]: connects when a config exists, reconnects when it changes and
 *   goes Idle when it is cleared.
 * - Dropped connections are retried with [backoff]; the attempt counter resets after a successful
 *   auth. `auth_invalid` stops retrying until the config changes (or stop/start).
 * - Only the latest state per entity is kept; a full `get_states` re-sync happens on every connection.
 * - The floor/area registry is fetched after every states snapshot and refetched (debounced) on
 *   `*_registry_updated` events, in a side coroutine: it never delays `state_changed` handling or
 *   the Connected state, and its failures never drop the connection (each part keeps its last good
 *   value). Only the reduced [HaRegistry] is retained.
 * - Entities and registry survive [stop]; they are cleared when the config is cleared or points to
 *   a different server.
 */
class WebSocketHaRepository(
    private val configStore: HaConfigStore,
    private val okHttpClient: OkHttpClient,
    private val restClient: HaRestClient = HaRestClient(okHttpClient),
    private val settings: HaClientSettings = HaClientSettings(),
    private val backoff: Backoff = ExponentialBackoff(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HaRepository {

    // Single-threaded confinement: the entity working map is only touched from this dispatcher.
    private val scope = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))

    private val _connectionState = MutableStateFlow<HaConnectionState>(HaConnectionState.Idle)
    override val connectionState: StateFlow<HaConnectionState> = _connectionState.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, HaEntity>>(emptyMap())
    override val entities: StateFlow<Map<String, HaEntity>> = _entities.asStateFlow()

    private val _registry = MutableStateFlow(HaRegistry())
    override val registry: StateFlow<HaRegistry> = _registry.asStateFlow()

    private val lock = Any()
    private var job: Job? = null
    private var generation = 0L

    @Volatile
    private var activeConnection: HaWebSocketConnection? = null

    private val workingEntities = HashMap<String, HaEntity>()
    private var entitiesDirty = false
    private var entitiesServer: String? = null

    override fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            val gen = ++generation
            job = scope.launch {
                configStore.config.distinctUntilChanged().collectLatest { config ->
                    if (config == null) {
                        resetServerData(gen, server = null)
                        setState(gen, HaConnectionState.Idle)
                    } else {
                        val server = HaUrls.normalizeBaseUrl(config.baseUrl)
                        if (server != entitiesServer) resetServerData(gen, server)
                        runConnectionLoop(gen, config)
                    }
                }
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            generation++
            job?.cancel()
            job = null
            activeConnection = null
            _connectionState.value = HaConnectionState.Idle
        }
    }

    override suspend fun callService(
        domain: String,
        service: String,
        entityId: String,
        data: JsonObject,
    ): Result<Unit> {
        val connection = activeConnection
            ?: return Result.failure(IllegalStateException("Not connected to Home Assistant"))
        return try {
            // Timeouts are measured on the repository's dispatcher, independent of the caller's clock.
            withContext(dispatcher) {
                connection.request(settings.requestTimeoutMillis) { id ->
                    HaProtocol.callService(id, domain, service, entityId, data)
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * The connection while [connectionState] is Connected, else null. For other data-layer clients
     * (e.g. the camera source) that send their own commands; never exposed outside `data.ha`.
     */
    internal fun activeConnection(): HaWebSocketConnection? = activeConnection

    override suspend fun testConnection(config: HaServerConfig): HaConnectionTestResult =
        restClient.testConnection(config)

    private suspend fun runConnectionLoop(gen: Long, config: HaServerConfig): Nothing {
        var attempt = 0
        while (true) {
            setState(gen, HaConnectionState.Connecting)
            val connection = HaWebSocketConnection(okHttpClient, config, settings)
            val failure: String = try {
                coroutineScope {
                    // Conflated: any number of pending triggers collapse into one refetch.
                    val registryTriggers = Channel<Unit>(Channel.CONFLATED)
                    launch { syncRegistry(gen, connection, registryTriggers) }
                    connection.run(object : HaConnectionCallbacks {
                        override fun onAuthenticated(haVersion: String?) {
                            attempt = 0
                        }

                        override fun onStatesSnapshot(entities: List<HaEntity>) {
                            workingEntities.clear()
                            entities.associateByTo(workingEntities) { it.entityId }
                            entitiesDirty = true
                            publishEntities(gen)
                            activeConnection = connection
                            setState(gen, HaConnectionState.Connected)
                            registryTriggers.trySend(Unit)
                        }

                        override fun onStateChanged(entityId: String, newState: HaEntity?) {
                            if (newState == null) {
                                workingEntities.remove(entityId)
                            } else {
                                workingEntities[entityId] = newState
                            }
                            entitiesDirty = true
                        }

                        override fun onRegistryUpdated() {
                            registryTriggers.trySend(Unit)
                        }

                        override fun onMessagesProcessed() = publishEntities(gen)
                    })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HaAuthInvalidException) {
                setState(gen, HaConnectionState.AuthFailed(e.message ?: "Invalid access token"))
                awaitCancellation()
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            } finally {
                if (activeConnection === connection) activeConnection = null
            }
            val retryIn = backoff.delayMillis(attempt)
            attempt++
            setState(gen, HaConnectionState.Disconnected(failure, retryIn))
            delay(retryIn)
        }
    }

    /**
     * Runs alongside one connection and is cancelled with it. Must not throw: a failure here would
     * tear down the connection.
     */
    private suspend fun syncRegistry(gen: Long, connection: HaWebSocketConnection, triggers: ReceiveChannel<Unit>) {
        triggers.receive() // initial sync once the states snapshot is in
        // Subscribe before fetching so changes made during the fetch trigger a refetch.
        for (eventType in HaProtocol.REGISTRY_EVENTS) {
            try {
                connection.request { id -> HaProtocol.subscribeEvents(id, eventType) }
            } catch (_: HaRequestException) {
                // No live updates for this registry; it is still refetched on reconnect.
            }
        }
        while (true) {
            fetchRegistry(gen, connection)
            triggers.receive()
            delay(settings.registryRefreshDebounceMillis)
            triggers.tryReceive() // drop triggers coalesced during the quiet period
        }
    }

    /**
     * Fetches the four registry lists one at a time, reducing each payload before requesting the next
     * so at most one raw result is alive. A failed or malformed part keeps its last good value.
     */
    private suspend fun fetchRegistry(gen: Long, connection: HaWebSocketConnection) {
        val floors = fetchPart(connection, HaProtocol.CMD_FLOOR_REGISTRY_LIST, HaRegistryParser::parseFloors)
        val areas = fetchPart(connection, HaProtocol.CMD_AREA_REGISTRY_LIST, HaRegistryParser::parseAreas)
        val deviceAreas = fetchPart(connection, HaProtocol.CMD_DEVICE_REGISTRY_LIST, HaRegistryParser::parseDeviceAreas)
        val entityAreas = deviceAreas?.let { devices ->
            fetchPart(connection, HaProtocol.CMD_ENTITY_REGISTRY_LIST_FOR_DISPLAY) {
                HaRegistryParser.parseEntityAreas(it, devices)
            } ?: fetchPart(connection, HaProtocol.CMD_ENTITY_REGISTRY_LIST) {
                HaRegistryParser.parseEntityAreas(it, devices)
            }
        }
        if (floors == null && areas == null && entityAreas == null) return
        synchronized(lock) {
            if (gen != generation) return
            val previous = _registry.value
            _registry.value = HaRegistry(
                floors = floors ?: previous.floors,
                areas = areas ?: previous.areas,
                entityAreas = entityAreas ?: previous.entityAreas,
            )
        }
    }

    /** Null when the request failed or the payload was malformed. Parsing runs off the confined thread. */
    private suspend fun <T : Any> fetchPart(
        connection: HaWebSocketConnection,
        command: String,
        parse: (JsonElement) -> T?,
    ): T? {
        val result = try {
            connection.request { id -> HaProtocol.command(id, command) }
        } catch (_: HaRequestException) {
            return null
        }
        return withContext(dispatcher) { parse(result) }
    }

    private fun resetServerData(gen: Long, server: String?) {
        workingEntities.clear()
        entitiesDirty = true
        entitiesServer = server
        publishEntities(gen)
        synchronized(lock) {
            if (gen == generation) _registry.value = HaRegistry()
        }
    }

    private fun publishEntities(gen: Long) {
        if (!entitiesDirty) return
        entitiesDirty = false
        val snapshot = workingEntities.toMap()
        synchronized(lock) {
            if (gen == generation) _entities.value = snapshot
        }
    }

    private fun setState(gen: Long, state: HaConnectionState) {
        synchronized(lock) {
            if (gen == generation) _connectionState.value = state
        }
    }
}
