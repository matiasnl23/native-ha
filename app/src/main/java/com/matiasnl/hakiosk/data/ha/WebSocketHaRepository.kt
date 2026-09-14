package com.matiasnl.hakiosk.data.ha

import com.matiasnl.hakiosk.data.ha.rest.HaRestClient
import com.matiasnl.hakiosk.data.ha.ws.Backoff
import com.matiasnl.hakiosk.data.ha.ws.ExponentialBackoff
import com.matiasnl.hakiosk.data.ha.ws.HaAuthInvalidException
import com.matiasnl.hakiosk.data.ha.ws.HaClientSettings
import com.matiasnl.hakiosk.data.ha.ws.HaConnectionCallbacks
import com.matiasnl.hakiosk.data.ha.ws.HaProtocol
import com.matiasnl.hakiosk.data.ha.ws.HaWebSocketConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
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
                        resetEntities(gen, server = null)
                        setState(gen, HaConnectionState.Idle)
                    } else {
                        val server = HaUrls.normalizeBaseUrl(config.baseUrl)
                        if (server != entitiesServer) resetEntities(gen, server)
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

    override suspend fun testConnection(config: HaServerConfig): HaConnectionTestResult =
        restClient.testConnection(config)

    private suspend fun runConnectionLoop(gen: Long, config: HaServerConfig): Nothing {
        var attempt = 0
        while (true) {
            setState(gen, HaConnectionState.Connecting)
            val connection = HaWebSocketConnection(okHttpClient, config, settings)
            val failure: String = try {
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
                    }

                    override fun onStateChanged(entityId: String, newState: HaEntity?) {
                        if (newState == null) {
                            workingEntities.remove(entityId)
                        } else {
                            workingEntities[entityId] = newState
                        }
                        entitiesDirty = true
                    }

                    override fun onMessagesProcessed() = publishEntities(gen)
                })
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

    private fun resetEntities(gen: Long, server: String?) {
        workingEntities.clear()
        entitiesDirty = true
        entitiesServer = server
        publishEntities(gen)
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
