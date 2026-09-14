package com.matiasnl.hakiosk.data.ha.rest

import com.matiasnl.hakiosk.data.ha.HaConnectionTestResult
import com.matiasnl.hakiosk.data.ha.HaNetworkErrors
import com.matiasnl.hakiosk.data.ha.HaServerConfig
import com.matiasnl.hakiosk.data.ha.HaUrls
import com.matiasnl.hakiosk.data.ha.ws.HaProtocol.stringOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A REST call failed. [httpCode] is set when HA answered with an error status. */
class HaHttpException(message: String, val httpCode: Int? = null) : IOException(message)

/** One-off REST calls to the Home Assistant API (`/api/...`) authenticated with a Bearer token. */
class HaRestClient(
    client: OkHttpClient,
    callTimeoutMillis: Long = 15_000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // Derived client shares the connection pool and dispatcher with the shared one.
    private val client = client.newBuilder().callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS).build()

    /**
     * `GET /api/camera_proxy/<entityId>[?width=]`. HA uses `width` as a best-effort hint (its own
     * JPEG downscaling needs width and height; some integrations honor width alone). Bodies larger
     * than [maxBytes] fail without being fully read. The whole call is bounded by the call timeout.
     */
    suspend fun fetchCameraSnapshot(
        config: HaServerConfig,
        entityId: String,
        width: Int? = null,
        maxBytes: Long = MAX_SNAPSHOT_BYTES,
    ): Result<ByteArray> {
        val url = HaUrls.apiUrl(config.baseUrl, "camera_proxy").toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment(entityId)
            ?.apply { if (width != null && width > 0) addQueryParameter("width", width.toString()) }
            ?.build()
            ?: return Result.failure(HaHttpException("Invalid server URL"))
        val request = try {
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${config.token.trim()}")
                .get()
                .build()
        } catch (_: IllegalArgumentException) {
            return Result.failure(HaHttpException("Invalid access token"))
        }
        return try {
            client.newCall(request).await().use { response ->
                val code = response.code
                val error = when {
                    code == 401 -> "Invalid access token (HTTP 401)"
                    code == 403 -> "Access denied (HTTP 403)"
                    code == 404 -> "Camera not found: $entityId (HTTP 404)"
                    code == 503 -> "Camera is off or unavailable (HTTP 503)"
                    code >= 500 -> "Home Assistant could not get the camera image (HTTP $code)"
                    !response.isSuccessful -> "Snapshot request failed (HTTP $code)"
                    else -> null
                }
                if (error != null) {
                    Result.failure(HaHttpException(error, code))
                } else {
                    Result.success(withContext(ioDispatcher) { readLimited(response.body, maxBytes) })
                }
            }
        } catch (e: HaHttpException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(HaHttpException("Snapshot failed: ${HaNetworkErrors.describe(e)}"))
        }
    }

    private fun readLimited(body: ResponseBody, maxBytes: Long): ByteArray {
        val declared = body.contentLength()
        if (declared > maxBytes) throw HaHttpException("Snapshot too large: $declared bytes (limit $maxBytes)")
        val source = body.source()
        // request() buffers at most maxBytes + 1 bytes, so an oversized chunked body is never read in full.
        if (source.request(maxBytes + 1)) throw HaHttpException("Snapshot too large (limit $maxBytes bytes)")
        val bytes = source.readByteArray()
        if (bytes.isEmpty()) throw HaHttpException("Empty snapshot")
        return bytes
    }

    /** `GET /api/config`: validates URL and token and reads the HA version. Stores nothing. */
    suspend fun testConnection(config: HaServerConfig): HaConnectionTestResult {
        val url = HaUrls.apiUrl(config.baseUrl, "config").toHttpUrlOrNull()
            ?: return HaConnectionTestResult.Unreachable("Invalid URL")
        val request = try {
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${config.token.trim()}")
                .get()
                .build()
        } catch (_: IllegalArgumentException) {
            // Header values with illegal characters: the token can't be valid.
            return HaConnectionTestResult.InvalidToken
        }
        return try {
            client.newCall(request).await().use { response ->
                when {
                    response.code == 401 -> HaConnectionTestResult.InvalidToken
                    !response.isSuccessful -> HaConnectionTestResult.Unreachable("HTTP ${response.code}")
                    else -> {
                        val version = parseVersion(response.body.string())
                        if (version != null) {
                            HaConnectionTestResult.Success(version)
                        } else {
                            HaConnectionTestResult.Unreachable("Not a Home Assistant server")
                        }
                    }
                }
            }
        } catch (e: IOException) {
            HaConnectionTestResult.Unreachable(HaNetworkErrors.describe(e))
        }
    }

    private fun parseVersion(body: String): String? = try {
        (Json.parseToJsonElement(body) as? JsonObject)?.get("version").stringOrNull()
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        /** Camera stills are usually well under 1 MB; anything past this is not a sane thumbnail. */
        const val MAX_SNAPSHOT_BYTES: Long = 5L * 1024 * 1024
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
}
