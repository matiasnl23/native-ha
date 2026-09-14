package com.matiasnl.hakiosk.data.device.mqtt

import com.matiasnl.hakiosk.data.device.MqttConfig
import com.matiasnl.hakiosk.data.device.MqttConfigStore
import com.matiasnl.hakiosk.data.device.MqttConnectionState
import com.matiasnl.hakiosk.data.device.MqttRemoteControl
import com.matiasnl.hakiosk.data.device.MqttTestResult
import com.matiasnl.hakiosk.data.device.store.MqttDeviceIdProvider
import com.matiasnl.hakiosk.data.ha.ws.Backoff
import com.matiasnl.hakiosk.data.ha.ws.ExponentialBackoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * Low-level MQTT access for the rest of the device layer (stage 2: Discovery, commands, state).
 * Works across reconnects: subscriptions are re-sent on every new connection.
 */
interface MqttMessaging {
    val connectionState: StateFlow<MqttConnectionState>

    /** Topic scheme of this device (loads the device id on first use). */
    suspend fun topics(): MqttTopics

    /**
     * Publishes on the current connection. Returns false (and drops the message) when not connected or
     * the publish failed: callers republish their state when [connectionState] becomes Connected.
     */
    suspend fun publish(
        topic: String,
        payload: String,
        retain: Boolean = false,
        qos: MqttQosLevel = MqttQosLevel.AT_LEAST_ONCE,
    ): Boolean

    /**
     * Messages matching [topicFilter] (MQTT wildcards allowed) while collected. The SUBSCRIBE is sent
     * now if connected and again after each reconnect; UNSUBSCRIBE when the last collector of that
     * filter stops. Retained messages are redelivered after each reconnect. Slow collectors lose the
     * oldest messages beyond a small buffer.
     */
    fun subscribe(topicFilter: String, qos: MqttQosLevel = MqttQosLevel.AT_LEAST_ONCE): Flow<MqttMessage>
}

/**
 * Keeps one MQTT connection to the stored broker config alive and exposes it through
 * [MqttRemoteControl] and [MqttMessaging].
 *
 * - [start] observes the config: none → [MqttConnectionState.Disabled] and the service stopped;
 *   present → service started and connection kept alive; changed → reconnect with the new config.
 * - Failures retry with [backoff] (1s doubling to 60s, jittered), reset after a successful connect.
 *   Rejected credentials → [MqttConnectionState.AuthFailed] with no retries until the config changes
 *   (or [start] is called again, e.g. when the activity comes back to the foreground).
 * - A network change (new default network or network regained) reconnects right away instead of
 *   waiting for the keepalive to notice the dead socket or for the backoff delay.
 * - Availability: Last Will `offline` (retained) on [MqttTopics.availability]; `online` (retained)
 *   published after each connect; `offline` published before a clean disconnect ([stop], config change).
 *
 * [start] is meant to be called from the foreground activity: it may start a foreground service,
 * which Android 12+ doesn't allow from the background ([serviceController] must swallow that).
 */
class MqttConnectionManager(
    private val configStore: MqttConfigStore,
    private val deviceIdProvider: MqttDeviceIdProvider,
    private val clientFactory: MqttClientFactory,
    private val networkMonitor: NetworkMonitor,
    private val serviceController: RemoteControlServiceController,
    private val scope: CoroutineScope,
    private val backoff: Backoff = ExponentialBackoff(initialMillis = 1_000, maxMillis = 60_000),
    private val random: Random = Random.Default,
    private val log: (String) -> Unit = {},
) : MqttRemoteControl, MqttMessaging {

    private val _connectionState = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disabled)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val lock = Any()

    /** Guarded by [lock]. Incremented by every start/stop so a stale loop never overwrites the state. */
    private var generation = 0
    private var loopJob: Job? = null
    private val subscriptions = mutableListOf<Subscription>()

    @Volatile
    private var session: MqttSession? = null

    @Volatile
    private var cachedTopics: MqttTopics? = null

    private class Subscription(val filter: String, val qos: MqttQosLevel, val channel: SendChannel<MqttMessage>)

    private class NetworkChangedException : Exception("Network changed")

    override fun start() {
        synchronized(lock) {
            val running = loopJob?.isActive == true
            if (running && _connectionState.value !is MqttConnectionState.AuthFailed) return
            val previous = loopJob
            previous?.cancel()
            val myGeneration = ++generation
            loopJob = scope.launch { runLoop(myGeneration, previous) }
        }
    }

    override fun stop() {
        synchronized(lock) {
            generation++
            loopJob?.cancel()
            loopJob = null
            _connectionState.value = MqttConnectionState.Disabled
        }
        serviceController.stopService()
    }

    override suspend fun topics(): MqttTopics =
        cachedTopics ?: MqttTopics(deviceIdProvider.deviceId()).also { cachedTopics = it }

    override suspend fun publish(topic: String, payload: String, retain: Boolean, qos: MqttQosLevel): Boolean {
        val current = session ?: return false
        return try {
            current.publish(topic, payload.toByteArray(Charsets.UTF_8), qos, retain)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("MQTT publish to $topic failed: ${MqttErrors.describe(e)}")
            false
        }
    }

    override fun subscribe(topicFilter: String, qos: MqttQosLevel): Flow<MqttMessage> = callbackFlow {
        val subscription = Subscription(topicFilter, qos, channel)
        synchronized(lock) { subscriptions += subscription }
        session?.let { current -> quietly("subscribe to $topicFilter") { current.subscribe(topicFilter, qos) } }
        awaitClose {
            val stillUsed = synchronized(lock) {
                subscriptions.remove(subscription)
                subscriptions.any { it.filter == topicFilter }
            }
            val current = session
            if (!stillUsed && current != null) {
                scope.launch { quietly("unsubscribe from $topicFilter") { current.unsubscribe(topicFilter) } }
            }
        }
    }.buffer(SUBSCRIPTION_BUFFER, BufferOverflow.DROP_OLDEST)

    override suspend fun testConnection(config: MqttConfig): MqttTestResult {
        if (config.host.isBlank()) return MqttTestResult.Unreachable("Host is empty")
        val clientId = "${MqttTopics.ROOT}-test-${random.nextLong().toULong().toString(16)}"
        return try {
            val testSession = withTimeout(TEST_TIMEOUT_MILLIS) {
                clientFactory.connect(connectParams(config, clientId, will = null)) { }
            }
            withContext(NonCancellable) {
                withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { quietly("test disconnect") { testSession.disconnect() } }
            }
            MqttTestResult.Success
        } catch (e: MqttAuthException) {
            MqttTestResult.AuthFailed(e.message ?: AUTH_FAILED_MESSAGE)
        } catch (e: TimeoutCancellationException) {
            MqttTestResult.Unreachable(MqttErrors.describe(e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MqttTestResult.Unreachable(MqttErrors.describe(e))
        }
    }

    private suspend fun runLoop(myGeneration: Int, previous: Job?) {
        // The previous loop must finish its clean disconnect first: two clients with the same client id
        // would kick each other, and a late "offline" would overwrite our "online".
        previous?.join()
        val topics = topics()
        configStore.config.collectLatest { config ->
            if (config == null) {
                setState(myGeneration, MqttConnectionState.Disabled)
                serviceController.stopService()
                return@collectLatest
            }
            serviceController.startService()
            maintainConnection(myGeneration, config, topics)
        }
    }

    private suspend fun maintainConnection(myGeneration: Int, config: MqttConfig, topics: MqttTopics): Unit = coroutineScope {
        val network = networkMonitor.defaultNetwork().stateIn(this)
        val params = connectParams(config, topics.clientId, MqttWill(topics.availability, MqttTopics.PAYLOAD_OFFLINE, MqttQosLevel.AT_LEAST_ONCE, retain = true))
        var attempt = 0
        while (true) {
            if (network.value == null) {
                setState(myGeneration, MqttConnectionState.Disconnected(NO_NETWORK_MESSAGE, retryInMillis = 0))
                network.first { it != null }
            }
            setState(myGeneration, MqttConnectionState.Connecting)
            val connectedNetwork = network.value
            val newSession = try {
                clientFactory.connect(params, ::deliver)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MqttAuthException) {
                log("MQTT credentials rejected by ${config.host}")
                setState(myGeneration, MqttConnectionState.AuthFailed(e.message ?: AUTH_FAILED_MESSAGE))
                awaitCancellation()
            } catch (e: Exception) {
                val delayMillis = backoff.delayMillis(attempt++)
                setState(myGeneration, MqttConnectionState.Disconnected(MqttErrors.describe(e), delayMillis))
                waitBeforeRetry(delayMillis, network)
                continue
            }

            val cause: Throwable = try {
                // Online first so it's never published after the subscriptions trigger retained deliveries
                // that stage 2 may answer with state.
                newSession.publish(topics.availability, MqttTopics.PAYLOAD_ONLINE.toByteArray(), MqttQosLevel.AT_LEAST_ONCE, retain = true)
                session = newSession
                resubscribeAll(newSession)
                attempt = 0
                log("MQTT connected to ${config.host}:${config.port}")
                setState(myGeneration, MqttConnectionState.Connected)
                merge<Throwable>(
                    flow { emit(newSession.awaitClosed()) },
                    network.filter { it != connectedNetwork }.map { NetworkChangedException() },
                ).first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            } finally {
                session = null
                val stopping = !currentCoroutineContext().isActive
                withContext(NonCancellable) { closeSession(newSession, topics, publishOffline = stopping) }
            }

            if (cause is NetworkChangedException) {
                // Straight back to the top: reconnects now on the new network, or waits for one if none.
                log("MQTT reconnecting: default network changed")
                attempt = 0
                continue
            }
            val delayMillis = backoff.delayMillis(attempt++)
            log("MQTT connection lost: ${MqttErrors.describe(cause)}")
            setState(myGeneration, MqttConnectionState.Disconnected(MqttErrors.describe(cause), delayMillis))
            waitBeforeRetry(delayMillis, network)
        }
    }

    /** Waits [delayMillis], or less if a (different) network becomes available meanwhile. */
    private suspend fun waitBeforeRetry(delayMillis: Long, network: StateFlow<Long?>) {
        val startNetwork = network.value
        withTimeoutOrNull(delayMillis) { network.first { it != null && it != startNetwork } }
    }

    private suspend fun resubscribeAll(target: MqttSession) {
        val snapshot = synchronized(lock) { subscriptions.map { it.filter to it.qos }.distinct() }
        snapshot.forEach { (filter, qos) -> quietly("subscribe to $filter") { target.subscribe(filter, qos) } }
    }

    private suspend fun closeSession(target: MqttSession, topics: MqttTopics, publishOffline: Boolean) {
        withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) {
            if (publishOffline) {
                quietly("publish offline") {
                    target.publish(topics.availability, MqttTopics.PAYLOAD_OFFLINE.toByteArray(), MqttQosLevel.AT_LEAST_ONCE, retain = true)
                }
            }
            quietly("disconnect") { target.disconnect() }
        }
    }

    private fun deliver(message: MqttMessage) {
        val targets = synchronized(lock) { subscriptions.filter { MqttTopics.matches(it.filter, message.topic) } }
        targets.forEach { it.channel.trySend(message) }
    }

    private fun setState(myGeneration: Int, state: MqttConnectionState) {
        synchronized(lock) {
            if (myGeneration == generation) _connectionState.value = state
        }
    }

    private inline fun quietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("MQTT $what failed: ${MqttErrors.describe(e)}")
        }
    }

    private fun connectParams(config: MqttConfig, clientId: String, will: MqttWill?) = MqttConnectParams(
        host = config.host.trim(),
        port = config.port,
        useTls = config.useTls,
        clientId = clientId,
        username = config.username?.takeIf { it.isNotBlank() },
        password = config.password?.takeIf { it.isNotEmpty() },
        keepAliveSeconds = KEEP_ALIVE_SECONDS,
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
        will = will,
    )

    companion object {
        const val KEEP_ALIVE_SECONDS = 60
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
        const val TEST_TIMEOUT_MILLIS = 15_000L
        const val CLOSE_TIMEOUT_MILLIS = 2_000L
        const val NO_NETWORK_MESSAGE = "No network"
        private const val AUTH_FAILED_MESSAGE = "Not authorized"
        private const val SUBSCRIPTION_BUFFER = 64
    }
}
