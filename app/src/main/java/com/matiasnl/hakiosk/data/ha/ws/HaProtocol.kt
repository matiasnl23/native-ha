package com.matiasnl.hakiosk.data.ha.ws

import com.matiasnl.hakiosk.data.ha.HaEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Message builders and parsers for the Home Assistant WebSocket API
 * (https://developers.home-assistant.io/docs/api/websocket).
 */
internal object HaProtocol {
    const val TYPE_AUTH_REQUIRED = "auth_required"
    const val TYPE_AUTH_OK = "auth_ok"
    const val TYPE_AUTH_INVALID = "auth_invalid"
    const val TYPE_RESULT = "result"
    const val TYPE_EVENT = "event"
    const val TYPE_PONG = "pong"
    const val EVENT_STATE_CHANGED = "state_changed"

    const val CMD_FLOOR_REGISTRY_LIST = "config/floor_registry/list"
    const val CMD_AREA_REGISTRY_LIST = "config/area_registry/list"
    const val CMD_DEVICE_REGISTRY_LIST = "config/device_registry/list"

    /** Compact entity registry (`ei`/`ai`/`di` keys, disabled entities excluded). HA 2023.3+. */
    const val CMD_ENTITY_REGISTRY_LIST_FOR_DISPLAY = "config/entity_registry/list_for_display"

    /** Full entity registry; only used as a fallback when [CMD_ENTITY_REGISTRY_LIST_FOR_DISPLAY] fails. */
    const val CMD_ENTITY_REGISTRY_LIST = "config/entity_registry/list"

    /** Registry change events; all are in HA's subscribe allowlist for non-admin users. */
    val REGISTRY_EVENTS: List<String> = listOf(
        "floor_registry_updated",
        "area_registry_updated",
        "device_registry_updated",
        "entity_registry_updated",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The auth message carries the token: never log its string form. */
    fun auth(token: String): JsonObject = buildJsonObject {
        put("type", "auth")
        put("access_token", token)
    }

    fun subscribeStateChanged(id: Int): JsonObject = subscribeEvents(id, EVENT_STATE_CHANGED)

    fun subscribeEvents(id: Int, eventType: String): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "subscribe_events")
        put("event_type", eventType)
    }

    /**
     * Ends the subscription started by request [subscriptionId]. HA's handler works for any entry in
     * the connection's subscriptions, not only `subscribe_events` (e.g. it closes a WebRTC session).
     */
    fun unsubscribeEvents(id: Int, subscriptionId: Int): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "unsubscribe_events")
        put("subscription", subscriptionId)
    }

    /** A parameterless command such as [CMD_AREA_REGISTRY_LIST]. */
    fun command(id: Int, type: String): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type)
    }

    fun getStates(id: Int): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "get_states")
    }

    fun ping(id: Int): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "ping")
    }

    fun callService(
        id: Int,
        domain: String,
        service: String,
        entityId: String,
        serviceData: JsonObject,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "call_service")
        put("domain", domain)
        put("service", service)
        put("service_data", serviceData)
        put("target", buildJsonObject { put("entity_id", entityId) })
    }

    /** Parses a frame into messages. HA may coalesce several messages into a JSON array. */
    fun parseFrame(text: String): List<JsonObject> {
        val element = try {
            json.parseToJsonElement(text)
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }
        return when (element) {
            is JsonObject -> listOf(element)
            is JsonArray -> element.filterIsInstance<JsonObject>()
            else -> emptyList()
        }
    }

    fun parseEntity(element: JsonElement?): HaEntity? {
        val obj = element as? JsonObject ?: return null
        val entityId = obj["entity_id"].stringOrNull() ?: return null
        return HaEntity(
            entityId = entityId,
            state = obj["state"].stringOrNull() ?: "unknown",
            attributes = obj["attributes"] as? JsonObject ?: JsonObject(emptyMap()),
            lastChanged = obj["last_changed"].stringOrNull() ?: "",
        )
    }

    fun JsonObject.type(): String? = this["type"].stringOrNull()

    fun JsonObject.id(): Int? = (this["id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
}
