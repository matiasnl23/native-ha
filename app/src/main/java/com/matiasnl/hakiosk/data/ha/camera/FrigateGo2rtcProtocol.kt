package com.matiasnl.hakiosk.data.ha.camera

import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.HaUrls
import com.matiasnl.hakiosk.data.ha.ws.HaProtocol.stringOrNull
import com.matiasnl.hakiosk.data.ha.ws.HaRequestException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Frigate go2rtc access through the Frigate HA integration's authenticated proxy (integration
 * v5.12+, Frigate 0.17+). Verified against frigate-hass-integration v5.15.6 `views.py`, Frigate
 * v0.18.0 nginx and go2rtc `api/ws`:
 *
 * - Frigate camera entities carry the `client_id` attribute (the Frigate MQTT client id; the literal
 *   `"None"` when MQTT has none) that selects the Frigate instance in proxy paths.
 * - `GET /api/frigate/<client_id>/go2rtc/streams` → Frigate `/api/go2rtc/streams`: a JSON object
 *   keyed by stream name.
 * - WebSocket `/api/frigate/<client_id>/webrtc/api/ws?src=<stream>` → Frigate `/live/webrtc/api/ws` →
 *   go2rtc `/api/ws`. Every frame is `{"type": ..., "value": ...}`: the client sends `webrtc/offer`
 *   (raw SDP) and `webrtc/candidate` (candidate string); go2rtc answers `webrtc/answer` before any
 *   `webrtc/candidate`, never signals end of candidates, and reports failures as `error` (e.g.
 *   `webrtc/offer: stream not found`) before closing.
 */
internal object FrigateGo2rtcProtocol {
    const val CODE_NOT_FRIGATE_CAMERA = "not_frigate_camera"
    const val CODE_STREAM_NOT_FOUND = "stream_not_found"
    const val CODE_GO2RTC_ERROR = "go2rtc_error"
    const val CODE_INVALID_ANSWER = "invalid_answer"

    private const val TYPE_OFFER = "webrtc/offer"
    private const val TYPE_ANSWER = "webrtc/answer"
    private const val TYPE_CANDIDATE = "webrtc/candidate"
    private const val TYPE_ERROR = "error"

    private val json = Json { ignoreUnknownKeys = true }

    /** The Frigate `client_id` of [entity], or a failure with [CODE_NOT_FRIGATE_CAMERA]. */
    fun clientId(entityId: String, entity: HaEntity?): Result<String> {
        val reason = when {
            entity == null -> "Unknown camera entity: $entityId"
            entity.domain != "camera" -> "Not a camera entity: $entityId"
            else -> null
        }
        if (reason != null) return Result.failure(HaRequestException(reason, CODE_NOT_FRIGATE_CAMERA))
        val clientId = entity!!.attributes["client_id"].stringOrNull()?.trim()
        if (clientId.isNullOrEmpty() || clientId == "None") {
            return Result.failure(
                HaRequestException("Not a Frigate camera (no client_id attribute): $entityId", CODE_NOT_FRIGATE_CAMERA),
            )
        }
        return Result.success(clientId)
    }

    /**
     * `<base>/api/frigate/<client_id>/go2rtc/streams`, keeping any base path prefix. The integration maps
     * `go2rtc/<path>` to Frigate's `api/go2rtc/<path>`, so this always reaches Frigate's own
     * `/api/go2rtc/streams` (Frigate 0.17+). The `go2rtc/api/streams` form depends on the integration
     * detecting Frigate 0.18 (v5.15.3+ and a migrated config version); otherwise it's forwarded to a
     * route 0.18 removed and Frigate answers `{"detail":"Not Found"}`.
     */
    fun streamsUrl(baseUrl: String, clientId: String): HttpUrl? =
        proxyUrl(baseUrl, clientId)?.addPathSegments("go2rtc/streams")?.build()

    /**
     * `<base>/api/frigate/<client_id>/webrtc/api/ws?src=<stream>` as http(s); OkHttp upgrades it to
     * ws(s) when opening the WebSocket.
     */
    fun signalingUrl(baseUrl: String, clientId: String, stream: String): HttpUrl? =
        proxyUrl(baseUrl, clientId)?.addPathSegments("webrtc/api/ws")?.addQueryParameter("src", stream)?.build()

    /** Stream names (the object's keys), sorted case-insensitively; null when [body] isn't a JSON object. */
    fun parseStreams(body: String): List<String>? {
        val root = parseObject(body) ?: return null
        return root.keys.sortedWith(String.CASE_INSENSITIVE_ORDER.thenBy { it })
    }

    fun offerMessage(sdp: String): String = message(TYPE_OFFER, sdp)

    fun candidateMessage(candidate: String): String = message(TYPE_CANDIDATE, candidate)

    /**
     * Maps a go2rtc frame to an event; null for frames to skip (unknown types, blank candidates). go2rtc
     * candidates carry no mid, so they get [sdpMid] (see [firstMid]) and m-line index 0.
     */
    fun parseEvent(text: String, sdpMid: String): HaWebRtcEvent? {
        val frame = parseObject(text) ?: return null
        val value = frame["value"].stringOrNull()
        return when (frame["type"].stringOrNull()) {
            TYPE_ANSWER ->
                if (value.isNullOrBlank()) {
                    HaWebRtcEvent.Error(CODE_INVALID_ANSWER, "Empty go2rtc answer")
                } else {
                    HaWebRtcEvent.Answer(value)
                }
            TYPE_CANDIDATE -> value?.takeIf { it.isNotBlank() }?.let {
                HaWebRtcEvent.RemoteCandidate(HaIceCandidate(it, sdpMid, 0))
            }
            TYPE_ERROR -> {
                val message = value?.takeIf { it.isNotBlank() } ?: "go2rtc error"
                val code = if (message.contains("stream not found", ignoreCase = true)) CODE_STREAM_NOT_FOUND else CODE_GO2RTC_ERROR
                HaWebRtcEvent.Error(code, message)
            }
            else -> null
        }
    }

    /** The first `a=mid:` of [sdp], or `"0"` when there is none. */
    fun firstMid(sdp: String): String = sdp.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("a=mid:") }
        ?.removePrefix("a=mid:")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "0"

    /** `<base>/api/frigate/<client_id>` as an http(s) URL builder. ws/wss base URLs map to http/https. */
    internal fun proxyUrl(baseUrl: String, clientId: String): HttpUrl.Builder? {
        val normalized = HaUrls.normalizeBaseUrl(baseUrl)
        val scheme = normalized.substringBefore("://", missingDelimiterValue = "")
        val httpBase = when (scheme.lowercase()) {
            "ws" -> "http://" + normalized.substringAfter("://")
            "wss" -> "https://" + normalized.substringAfter("://")
            else -> normalized
        }
        return HaUrls.apiUrl(httpBase, "frigate").toHttpUrlOrNull()?.newBuilder()?.addPathSegment(clientId)
    }

    private fun message(type: String, value: String): String = buildJsonObject {
        put("type", type)
        put("value", value)
    }.toString()

    private fun parseObject(text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }
}
