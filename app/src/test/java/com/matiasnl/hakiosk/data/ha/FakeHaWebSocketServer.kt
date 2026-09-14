package com.matiasnl.hakiosk.data.ha

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Minimal scripted Home Assistant WebSocket server on top of MockWebServer. */
class FakeHaWebSocketServer : WebSocketListener() {
    val server = MockWebServer()
    val connections = AtomicInteger(0)
    val received = LinkedBlockingQueue<JsonObject>()
    private val sockets = LinkedBlockingQueue<WebSocket>()

    @Volatile var validToken = "good-token"
    @Volatile var respondToPing = true
    @Volatile var serviceError: String? = null
    @Volatile var states: List<JsonObject> = listOf(state("light.kitchen", "off"), state("sensor.temp", "21"))

    /** Registry command type -> `result` payload. Missing commands answer `unknown_command`. */
    @Volatile var registry: Map<String, JsonElement> = mapOf(
        "config/floor_registry/list" to JsonArray(emptyList()),
        "config/area_registry/list" to JsonArray(emptyList()),
        "config/device_registry/list" to JsonArray(emptyList()),
        "config/entity_registry/list_for_display" to Json.parseToJsonElement("""{"entities":[]}"""),
    )

    /** Registry commands that answer with an error result. */
    @Volatile var failingCommands: Set<String> = emptySet()

    /** How many times each registry command was received. */
    val commandCounts = ConcurrentHashMap<String, AtomicInteger>()

    fun commandCount(type: String): Int = commandCounts[type]?.get() ?: 0

    @Volatile
    private var current: WebSocket? = null

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                connections.incrementAndGet()
                return MockResponse().withWebSocketUpgrade(this@FakeHaWebSocketServer)
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun shutdown() {
        // Server-side sockets can't be cancel()ed; close them so shutdown doesn't wait on open sockets.
        sockets.forEach { runCatching { it.close(1001, "shutdown") } }
        server.shutdown()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        current = webSocket
        sockets.add(webSocket)
        webSocket.send("""{"type":"auth_required","ha_version":"2026.9.0"}""")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val message = Json.parseToJsonElement(text) as JsonObject
        received.add(message)
        val id = (message["id"] as? JsonPrimitive)?.contentOrNull?.toInt()
        when ((message["type"] as JsonPrimitive).content) {
            "auth" -> {
                if ((message["access_token"] as JsonPrimitive).content == validToken) {
                    webSocket.send("""{"type":"auth_ok","ha_version":"2026.9.0"}""")
                } else {
                    webSocket.send("""{"type":"auth_invalid","message":"Invalid password"}""")
                    webSocket.close(1000, null)
                }
            }
            "subscribe_events" -> webSocket.send(result(id!!, JsonNull))
            "get_states" -> webSocket.send(result(id!!, JsonArray(states)))
            "ping" -> if (respondToPing) webSocket.send("""{"id":$id,"type":"pong"}""")
            in REGISTRY_COMMANDS -> {
                val type = (message["type"] as JsonPrimitive).content
                commandCounts.computeIfAbsent(type) { AtomicInteger() }.incrementAndGet()
                val response = registry[type]
                if (type in failingCommands || response == null) {
                    webSocket.send("""{"id":$id,"type":"result","success":false,"error":{"code":"unknown_command","message":"Unknown command."}}""")
                } else {
                    webSocket.send(result(id!!, response))
                }
            }
            "call_service" -> {
                val error = serviceError
                if (error == null) {
                    webSocket.send(result(id!!, buildJsonObject { put("context", buildJsonObject { put("id", "ctx") }) }))
                } else {
                    webSocket.send("""{"id":$id,"type":"result","success":false,"error":{"code":"not_found","message":"$error"}}""")
                }
            }
        }
    }

    /** Sends a `state_changed` event on the current connection; [newState] null means removal. */
    fun sendStateChanged(entityId: String, newState: JsonObject?, subscriptionId: Int = 1) {
        val event = buildJsonObject {
            put("id", subscriptionId)
            put("type", "event")
            put("event", buildJsonObject {
                put("event_type", "state_changed")
                put("data", buildJsonObject {
                    put("entity_id", entityId)
                    put("new_state", newState ?: JsonNull)
                    put("old_state", JsonNull)
                })
            })
        }
        current!!.send(event.toString())
    }

    /** Sends a bare event of [eventType] (e.g. `area_registry_updated`) on the current connection. */
    fun sendEvent(eventType: String, subscriptionId: Int = 2) {
        val event = buildJsonObject {
            put("id", subscriptionId)
            put("type", "event")
            put("event", buildJsonObject {
                put("event_type", eventType)
                put("data", buildJsonObject { put("action", "update") })
            })
        }
        current!!.send(event.toString())
    }

    /** Server-side close, e.g. HA restarting. */
    fun dropConnection() {
        current!!.close(1001, "restarting")
    }

    suspend fun awaitMessage(type: String): JsonObject = withContext(Dispatchers.IO) {
        while (true) {
            val message = received.poll(5, TimeUnit.SECONDS) ?: error("Timed out waiting for '$type'")
            if ((message["type"] as JsonPrimitive).content == type) return@withContext message
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun result(id: Int, result: JsonElement) = buildJsonObject {
        put("id", id)
        put("type", "result")
        put("success", true)
        put("result", result)
    }.toString()

    companion object {
        val REGISTRY_COMMANDS = setOf(
            "config/floor_registry/list",
            "config/area_registry/list",
            "config/device_registry/list",
            "config/entity_registry/list_for_display",
            "config/entity_registry/list",
        )

        fun state(entityId: String, state: String) = buildJsonObject {
            put("entity_id", entityId)
            put("state", state)
            put("attributes", buildJsonObject { put("friendly_name", entityId) })
            put("last_changed", "2026-09-13T10:00:00+00:00")
        }
    }
}
