package com.matiasnl.hakiosk.data.ha.ws

import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.HaNetworkErrors
import com.matiasnl.hakiosk.data.ha.HaServerConfig
import com.matiasnl.hakiosk.data.ha.HaUrls
import com.matiasnl.hakiosk.data.ha.ws.HaProtocol.id
import com.matiasnl.hakiosk.data.ha.ws.HaProtocol.stringOrNull
import com.matiasnl.hakiosk.data.ha.ws.HaProtocol.type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap

/** Tunables for the WebSocket client. A [heartbeatIntervalMillis] of 0 disables the app-level ping. */
data class HaClientSettings(
    val handshakeTimeoutMillis: Long = 15_000,
    val requestTimeoutMillis: Long = 15_000,
    val heartbeatIntervalMillis: Long = 60_000,
    val heartbeatTimeoutMillis: Long = 10_000,
    /** Quiet period after a registry change event before refetching; bursts coalesce into one refetch. */
    val registryRefreshDebounceMillis: Long = 1_500,
)

/** The connection dropped or never completed; the caller should retry with backoff. */
class HaConnectionLostException(message: String) : Exception(message)

/** HA answered `auth_invalid`. Retrying with the same token is pointless. */
class HaAuthInvalidException(message: String) : Exception(message)

/**
 * A request got an error `result`, timed out, or could not be sent. [code] is HA's error code
 * (e.g. `not_found`) or a local one: [CODE_NOT_CONNECTED], [CODE_TIMEOUT], [CODE_CONNECTION_LOST].
 */
class HaRequestException(message: String, val code: String? = null) : Exception(message) {
    companion object {
        const val CODE_NOT_CONNECTED = "not_connected"
        const val CODE_TIMEOUT = "timeout"
        const val CODE_CONNECTION_LOST = "connection_lost"
    }
}

/**
 * Events of one per-request subscription (e.g. `camera/webrtc/offer`). [events] yields the `event`
 * payload of each message routed to [id]; it is closed with a [HaRequestException] (code
 * [HaRequestException.CODE_CONNECTION_LOST]) when the connection ends. [close] stops routing and
 * asks HA to end the subscription; it is idempotent and safe to call from a cancelled coroutine.
 */
internal interface HaSubscription {
    val id: Int
    val events: ReceiveChannel<JsonObject>
    fun close()
}

internal interface HaConnectionCallbacks {
    fun onAuthenticated(haVersion: String?)

    /** Full snapshot from `get_states`; the connection is fully synced after this. */
    fun onStatesSnapshot(entities: List<HaEntity>)

    /** [newState] is null when the entity was removed. */
    fun onStateChanged(entityId: String, newState: HaEntity?)

    /** A floor/area/device/entity registry `*_registry_updated` event arrived. */
    fun onRegistryUpdated()

    /** Called after a batch of incoming messages was processed; a good moment to publish. */
    fun onMessagesProcessed()
}

/**
 * A single WebSocket session with Home Assistant. Not reusable: create a new instance per attempt.
 * All callbacks are invoked from the coroutine that called [run], in message order.
 */
internal class HaWebSocketConnection(
    private val client: OkHttpClient,
    private val config: HaServerConfig,
    private val settings: HaClientSettings,
) {
    private sealed interface Frame {
        class Text(val text: String) : Frame
        class Closed(val reason: String) : Frame
    }

    private class Pending(
        val deferred: CompletableDeferred<JsonElement>,
        val onResult: ((JsonElement) -> Unit)?,
    )

    private val incoming = Channel<Frame>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val subscriptions = ConcurrentHashMap<Int, Channel<JsonObject>>()
    private val sendLock = Any()
    private var nextId = 1

    @Volatile
    private var webSocket: WebSocket? = null

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            incoming.trySend(Frame.Text(text))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            val suffix = if (reason.isBlank()) "" else ": $reason"
            incoming.trySend(Frame.Closed("Connection closed by server ($code$suffix)"))
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val reason = response?.let { "HTTP ${it.code}" } ?: HaNetworkErrors.describe(t)
            incoming.trySend(Frame.Closed(reason))
        }
    }

    /**
     * Connects, authenticates, subscribes to `state_changed` and loads the initial states, then keeps
     * processing messages. Never returns normally: throws [HaAuthInvalidException],
     * [HaConnectionLostException], or [CancellationException] when the caller is cancelled.
     */
    suspend fun run(callbacks: HaConnectionCallbacks): Nothing {
        val request = try {
            Request.Builder().url(HaUrls.webSocketUrl(config.baseUrl)).build()
        } catch (_: IllegalArgumentException) {
            throw HaConnectionLostException("Invalid server URL")
        }
        val ws = client.newWebSocket(request, listener)
        try {
            val authenticated = withTimeoutOrNull(settings.handshakeTimeoutMillis) {
                authenticate(ws, callbacks)
                true
            }
            if (authenticated == null) throw HaConnectionLostException("Timed out during authentication")
            webSocket = ws

            coroutineScope {
                launch { readLoop(callbacks) }
                request(settings.requestTimeoutMillis) { id -> HaProtocol.subscribeStateChanged(id) }
                request(
                    timeoutMillis = settings.requestTimeoutMillis,
                    onResult = { result ->
                        val states = (result as? JsonArray).orEmpty().mapNotNull(HaProtocol::parseEntity)
                        callbacks.onStatesSnapshot(states)
                    },
                ) { id -> HaProtocol.getStates(id) }
                if (settings.heartbeatIntervalMillis > 0) launch { heartbeatLoop() }
                awaitCancellation()
            }
        } catch (e: CancellationException) {
            ws.close(1000, null)
            throw e
        } catch (e: HaRequestException) {
            ws.cancel()
            throw HaConnectionLostException(e.message ?: "Request failed")
        } catch (e: Throwable) {
            ws.cancel()
            throw e
        } finally {
            // Under sendLock: nothing can register a request or subscription after this point.
            synchronized(sendLock) { webSocket = null }
            incoming.close()
            val closed = HaRequestException("Connection closed", HaRequestException.CODE_CONNECTION_LOST)
            pending.values.forEach { it.deferred.completeExceptionally(closed) }
            pending.clear()
            subscriptions.values.forEach { it.close(closed) }
            subscriptions.clear()
        }
    }

    /**
     * Sends a request with a fresh id and suspends until its `result` (or `pong`) arrives.
     * [onResult] runs inside the read loop before any later message is processed.
     * Throws [HaRequestException] on error result, timeout or closed connection.
     */
    suspend fun request(
        timeoutMillis: Long = settings.requestTimeoutMillis,
        onResult: ((JsonElement) -> Unit)? = null,
        message: (id: Int) -> JsonObject,
    ): JsonElement {
        val entry = Pending(CompletableDeferred(), onResult)
        val id = send(entry, message)
        try {
            return awaitResult(entry, timeoutMillis)
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Starts a subscription command whose events carry the request id (e.g. `camera/webrtc/offer`)
     * and suspends until its `result`. The event channel is registered before the request is sent,
     * so no event is lost. Throws [HaRequestException] on error result, timeout or closed connection.
     */
    suspend fun subscribe(
        timeoutMillis: Long = settings.requestTimeoutMillis,
        message: (id: Int) -> JsonObject,
    ): HaSubscription {
        val entry = Pending(CompletableDeferred(), null)
        val channel = Channel<JsonObject>(MAX_SUBSCRIPTION_BACKLOG)
        val id = send(entry, message) { id -> subscriptions[id] = channel }
        val subscription = Subscription(id, channel)
        try {
            awaitResult(entry, timeoutMillis)
            return subscription
        } catch (e: Throwable) {
            // On timeout (or cancellation) HA may have subscribed anyway: ask it to stop.
            subscription.close(notifyServer = e !is HaRequestException || e.code == HaRequestException.CODE_TIMEOUT)
            throw e
        } finally {
            pending.remove(id)
        }
    }

    /** Number of subscriptions whose events are currently routed. For tests. */
    internal val activeSubscriptionCount: Int get() = subscriptions.size

    private inner class Subscription(
        override val id: Int,
        private val channel: Channel<JsonObject>,
    ) : HaSubscription {
        override val events: ReceiveChannel<JsonObject> get() = channel

        override fun close() = close(notifyServer = true)

        fun close(notifyServer: Boolean) {
            if (!subscriptions.remove(id, channel)) return
            channel.close()
            if (notifyServer) sendWithoutReply { newId -> HaProtocol.unsubscribeEvents(newId, id) }
        }
    }

    /** Allocates an id and sends atomically: ids must be strictly increasing on the wire. */
    private fun send(entry: Pending, message: (id: Int) -> JsonObject, register: (Int) -> Unit = {}): Int {
        synchronized(sendLock) {
            val ws = webSocket ?: throw HaRequestException("Not connected", HaRequestException.CODE_NOT_CONNECTED)
            val id = nextId++
            pending[id] = entry
            register(id)
            if (!ws.send(message(id).toString())) {
                pending.remove(id)
                subscriptions.remove(id)
                throw HaRequestException("Connection closed", HaRequestException.CODE_CONNECTION_LOST)
            }
            return id
        }
    }

    /** Best-effort message whose `result` is ignored (unknown ids are dropped by the read loop). */
    private fun sendWithoutReply(message: (id: Int) -> JsonObject) {
        synchronized(sendLock) {
            val ws = webSocket ?: return
            ws.send(message(nextId++).toString())
        }
    }

    private suspend fun awaitResult(entry: Pending, timeoutMillis: Long): JsonElement =
        withTimeoutOrNull(timeoutMillis) { entry.deferred.await() }
            ?: throw HaRequestException("Request timed out", HaRequestException.CODE_TIMEOUT)

    private suspend fun authenticate(ws: WebSocket, callbacks: HaConnectionCallbacks) {
        val first = receiveMessage()
        if (first.type() != HaProtocol.TYPE_AUTH_REQUIRED) {
            throw HaConnectionLostException("Unexpected message during handshake: ${first.type()}")
        }
        if (!ws.send(HaProtocol.auth(config.token).toString())) {
            throw HaConnectionLostException("Connection closed during authentication")
        }
        val answer = receiveMessage()
        when (answer.type()) {
            HaProtocol.TYPE_AUTH_OK -> callbacks.onAuthenticated(answer["ha_version"].stringOrNull())
            HaProtocol.TYPE_AUTH_INVALID ->
                throw HaAuthInvalidException(answer["message"].stringOrNull() ?: "Invalid access token")
            else -> throw HaConnectionLostException("Unexpected message during handshake: ${answer.type()}")
        }
    }

    private suspend fun receiveMessage(): JsonObject {
        while (true) {
            when (val frame = incoming.receive()) {
                is Frame.Closed -> throw HaConnectionLostException(frame.reason)
                is Frame.Text -> HaProtocol.parseFrame(frame.text).firstOrNull()?.let { return it }
            }
        }
    }

    private suspend fun readLoop(callbacks: HaConnectionCallbacks) {
        while (true) {
            process(incoming.receive(), callbacks)
            // Drain what is already buffered so bursts of events publish a single snapshot.
            var drained = 0
            while (drained < MAX_BATCH) {
                val frame = incoming.tryReceive().getOrNull() ?: break
                process(frame, callbacks)
                drained++
            }
            callbacks.onMessagesProcessed()
        }
    }

    private fun process(frame: Frame, callbacks: HaConnectionCallbacks) {
        when (frame) {
            is Frame.Closed -> throw HaConnectionLostException(frame.reason)
            is Frame.Text -> HaProtocol.parseFrame(frame.text).forEach { handleMessage(it, callbacks) }
        }
    }

    private fun handleMessage(message: JsonObject, callbacks: HaConnectionCallbacks) {
        when (message.type()) {
            HaProtocol.TYPE_RESULT -> {
                val entry = message.id()?.let { pending.remove(it) } ?: return
                val success = (message["success"] as? JsonPrimitive)?.booleanOrNull == true
                if (success) {
                    val result = message["result"] ?: JsonNull
                    try {
                        entry.onResult?.invoke(result)
                        entry.deferred.complete(result)
                    } catch (e: Exception) {
                        entry.deferred.completeExceptionally(HaRequestException("Invalid result: ${e.message}"))
                    }
                } else {
                    val error = message["error"] as? JsonObject
                    val code = error?.get("code").stringOrNull()
                    val text = error?.get("message").stringOrNull() ?: code
                    entry.deferred.completeExceptionally(HaRequestException(text ?: "Request failed", code))
                }
            }

            HaProtocol.TYPE_PONG -> message.id()?.let { pending.remove(it) }?.deferred?.complete(JsonNull)

            HaProtocol.TYPE_EVENT -> {
                val event = message["event"] as? JsonObject ?: return
                val subscriptionId = message.id()
                if (subscriptionId != null) {
                    val subscription = subscriptions[subscriptionId]
                    if (subscription != null) {
                        routeSubscriptionEvent(subscriptionId, subscription, event)
                        return
                    }
                }
                val eventType = event["event_type"].stringOrNull()
                if (eventType in HaProtocol.REGISTRY_EVENTS) {
                    callbacks.onRegistryUpdated()
                    return
                }
                if (eventType != HaProtocol.EVENT_STATE_CHANGED) return
                val data = event["data"] as? JsonObject ?: return
                val entityId = data["entity_id"].stringOrNull() ?: return
                val newState = data["new_state"]
                callbacks.onStateChanged(
                    entityId,
                    if (newState == null || newState is JsonNull) null else HaProtocol.parseEntity(newState),
                )
            }
        }
    }

    private fun routeSubscriptionEvent(id: Int, subscription: Channel<JsonObject>, event: JsonObject) {
        if (subscription.trySend(event).isSuccess || !subscriptions.remove(id, subscription)) return
        // A collector this far behind is stuck; end its subscription instead of buffering without limit.
        subscription.close(HaRequestException("Subscription event backlog overflow"))
        sendWithoutReply { newId -> HaProtocol.unsubscribeEvents(newId, id) }
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            delay(settings.heartbeatIntervalMillis)
            try {
                request(settings.heartbeatTimeoutMillis) { id -> HaProtocol.ping(id) }
            } catch (_: HaRequestException) {
                throw HaConnectionLostException("Heartbeat timed out")
            }
        }
    }

    private companion object {
        const val MAX_BATCH = 256

        /** Unconsumed events kept per subscription; WebRTC signaling sends a few dozen at most. */
        const val MAX_SUBSCRIPTION_BACKLOG = 256
    }
}
