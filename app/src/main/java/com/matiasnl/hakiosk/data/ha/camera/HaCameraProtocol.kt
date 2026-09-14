package com.matiasnl.hakiosk.data.ha.camera

import com.matiasnl.hakiosk.data.ha.ws.HaProtocol.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Camera WebSocket commands, verified against `homeassistant/components/camera/webrtc.py` and
 * `__init__.py` in home-assistant/core. Requires HA 2024.12+ (2024.11 took the candidate as a plain
 * string; parsing accepts both forms).
 *
 * - `camera/capabilities` → `{"frontend_stream_types": ["hls", "web_rtc"]}`
 * - `camera/webrtc/get_client_config` → `{"configuration": {"iceServers": [...]}, "dataChannel"?: ...}`
 * - `camera/webrtc/offer` is a subscription: an empty `result`, then events typed `session`
 *   (`session_id`), `answer` (`answer`), `candidate` (`candidate`: RTCIceCandidateInit with camelCase
 *   `candidate`/`sdpMid`/`sdpMLineIndex`) and `error` (`code`/`message`). `unsubscribe_events`
 *   closes the session.
 * - `camera/webrtc/candidate` takes `session_id` and an RTCIceCandidateInit object.
 */
internal object HaCameraProtocol {
    const val CMD_CAPABILITIES = "camera/capabilities"
    const val CMD_WEBRTC_CLIENT_CONFIG = "camera/webrtc/get_client_config"
    const val CMD_WEBRTC_OFFER = "camera/webrtc/offer"
    const val CMD_WEBRTC_CANDIDATE = "camera/webrtc/candidate"

    fun capabilities(id: Int, entityId: String): JsonObject = entityCommand(id, CMD_CAPABILITIES, entityId)

    fun webRtcClientConfig(id: Int, entityId: String): JsonObject =
        entityCommand(id, CMD_WEBRTC_CLIENT_CONFIG, entityId)

    fun webRtcOffer(id: Int, entityId: String, offerSdp: String): JsonObject = buildJsonObject {
        put("id", id)
        put("type", CMD_WEBRTC_OFFER)
        put("entity_id", entityId)
        put("offer", offerSdp)
    }

    /** Null fields are omitted: HA's RTCIceCandidateInit defaults them (index 0 when both are missing). */
    fun webRtcCandidate(id: Int, entityId: String, sessionId: String, candidate: HaIceCandidate): JsonObject =
        buildJsonObject {
            put("id", id)
            put("type", CMD_WEBRTC_CANDIDATE)
            put("entity_id", entityId)
            put("session_id", sessionId)
            put("candidate", buildJsonObject {
                put("candidate", candidate.candidate)
                candidate.sdpMid?.let { put("sdpMid", it) }
                candidate.sdpMLineIndex?.let { put("sdpMLineIndex", it) }
            })
        }

    /** Null when the payload is malformed. Unknown stream types are ignored. */
    fun parseStreamTypes(result: JsonElement): Set<HaCameraStreamType>? {
        val types = (result as? JsonObject)?.get("frontend_stream_types") as? JsonArray ?: return null
        return types.mapNotNullTo(mutableSetOf()) { type ->
            when (type.stringOrNull()) {
                "web_rtc" -> HaCameraStreamType.WEB_RTC
                "hls" -> HaCameraStreamType.HLS
                else -> null
            }
        }
    }

    /** Null when the payload is not an object. Servers without usable `urls` are skipped. */
    fun parseClientConfig(result: JsonElement): HaWebRtcClientConfig? {
        val obj = result as? JsonObject ?: return null
        val servers = ((obj["configuration"] as? JsonObject)?.get("iceServers") as? JsonArray).orEmpty()
        return HaWebRtcClientConfig(servers.mapNotNull(::parseIceServer))
    }

    /** Maps an `event` payload of `camera/webrtc/offer`. Null for unknown or malformed events. */
    fun parseWebRtcEvent(event: JsonObject): HaWebRtcEvent? = when (event["type"].stringOrNull()) {
        "session" -> event["session_id"].stringOrNull()?.takeIf { it.isNotEmpty() }?.let(HaWebRtcEvent::Session)
        "answer" -> event["answer"].stringOrNull()?.let(HaWebRtcEvent::Answer)
        "candidate" -> parseCandidate(event["candidate"])?.let(HaWebRtcEvent::RemoteCandidate)
        "error" -> HaWebRtcEvent.Error(
            code = event["code"].stringOrNull() ?: "unknown_error",
            message = event["message"].stringOrNull() ?: "WebRTC session failed",
        )
        else -> null
    }

    /**
     * Accepts an RTCIceCandidateInit object or (HA 2024.11) a bare string. Empty candidates
     * (end-of-candidates markers) are dropped; a negative or non-numeric index becomes null.
     */
    fun parseCandidate(element: JsonElement?): HaIceCandidate? {
        if (element is JsonPrimitive) {
            return element.stringOrNull()?.takeIf { element.isString && it.isNotBlank() }?.let { HaIceCandidate(it, null, null) }
        }
        val obj = element as? JsonObject ?: return null
        val candidate = obj["candidate"].stringOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return HaIceCandidate(
            candidate = candidate,
            sdpMid = obj["sdpMid"].stringOrNull(),
            sdpMLineIndex = (obj["sdpMLineIndex"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it >= 0 },
        )
    }

    private fun parseIceServer(element: JsonElement): HaIceServer? {
        val obj = element as? JsonObject ?: return null
        val urls = when (val raw = obj["urls"]) {
            is JsonArray -> raw.mapNotNull { url -> url.stringOrNull()?.takeIf { it.isNotBlank() } }
            is JsonPrimitive -> listOfNotNull(raw.stringOrNull()?.takeIf { raw.isString && it.isNotBlank() })
            else -> emptyList()
        }
        if (urls.isEmpty()) return null
        return HaIceServer(
            urls = urls,
            username = obj["username"].stringOrNull(),
            credential = obj["credential"].stringOrNull(),
        )
    }

    private fun entityCommand(id: Int, type: String, entityId: String): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type)
        put("entity_id", entityId)
    }
}
