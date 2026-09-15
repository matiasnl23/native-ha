package com.matiasnl.hakiosk.data.ha.camera

import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.HaUrls
import com.matiasnl.hakiosk.data.ha.ws.HaProtocol.stringOrNull
import com.matiasnl.hakiosk.data.ha.ws.HaRequestException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Frigate go2rtc access through the Frigate HA integration's authenticated proxy (integration
 * v5.15.3+, Frigate 0.18+). Verified against frigate-hass-integration v5.15.6 `views.py`, Frigate
 * v0.18.0 nginx and go2rtc `api/ws`:
 *
 * - Frigate camera entities carry the `client_id` attribute (the Frigate MQTT client id; the literal
 *   `"None"` when MQTT has none) that selects the Frigate instance in proxy paths.
 * - `GET /api/frigate/<client_id>/go2rtc/api/streams` → Frigate `/api/go2rtc/streams`: a JSON object
 *   keyed by stream name.
 */
internal object FrigateGo2rtcProtocol {
    const val CODE_NOT_FRIGATE_CAMERA = "not_frigate_camera"

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

    /** `<base>/api/frigate/<client_id>/go2rtc/api/streams`, keeping any base path prefix. */
    fun streamsUrl(baseUrl: String, clientId: String): HttpUrl? =
        proxyUrl(baseUrl, clientId)?.addPathSegments("go2rtc/api/streams")?.build()

    /** Stream names (the object's keys), sorted case-insensitively; null when [body] isn't a JSON object. */
    fun parseStreams(body: String): List<String>? {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        return root.keys.sortedWith(String.CASE_INSENSITIVE_ORDER.thenBy { it })
    }

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
}
