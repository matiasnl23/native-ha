package com.matiasnl.hakiosk.data.ha.camera

import com.matiasnl.hakiosk.data.ha.HaNetworkErrors
import com.matiasnl.hakiosk.data.ha.HaServerConfig
import com.matiasnl.hakiosk.data.ha.rest.HaHttpException
import com.matiasnl.hakiosk.data.ha.rest.await
import com.matiasnl.hakiosk.data.ha.ws.HaClientSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for Frigate's go2rtc behind the Frigate HA integration proxy, authenticated with the
 * HA token as a Bearer header (never in URLs or messages). Protocol details in [FrigateGo2rtcProtocol].
 */
class FrigateGo2rtcClient(
    client: OkHttpClient,
    private val settings: HaClientSettings = HaClientSettings(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // Derived client shares the connection pool and dispatcher with the shared one.
    private val restClient = client.newBuilder().callTimeout(settings.requestTimeoutMillis, TimeUnit.MILLISECONDS).build()

    /** go2rtc stream names of the Frigate instance [clientId], sorted. */
    suspend fun streams(config: HaServerConfig, clientId: String): Result<List<String>> {
        val url = FrigateGo2rtcProtocol.streamsUrl(config.baseUrl, clientId)
            ?: return Result.failure(HaHttpException("Invalid server URL"))
        val request = authorizedRequest(config, url.toString())
            ?: return Result.failure(HaHttpException("Invalid access token"))
        return try {
            restClient.newCall(request).await().use { response ->
                val code = response.code
                val error = when {
                    code == 401 -> "Invalid access token (HTTP 401)"
                    code == 403 -> "Access denied (HTTP 403)"
                    code == 404 -> "Frigate integration proxy not found; Frigate integration v5.15.3+ is required (HTTP 404)"
                    code >= 500 -> "Frigate could not list go2rtc streams (HTTP $code)"
                    !response.isSuccessful -> "go2rtc streams request failed (HTTP $code)"
                    else -> null
                }
                if (error != null) return@use Result.failure(HaHttpException(error, code))
                val body = withContext(ioDispatcher) { readLimited(response.body, MAX_STREAMS_BYTES) }
                FrigateGo2rtcProtocol.parseStreams(body)?.let { Result.success(it) }
                    ?: Result.failure(HaHttpException("Invalid go2rtc streams response"))
            }
        } catch (e: HaHttpException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(HaHttpException("go2rtc streams request failed: ${HaNetworkErrors.describe(e)}"))
        }
    }

    /** Null when the token has characters that can't go in a header (so it can't be valid). */
    private fun authorizedRequest(config: HaServerConfig, url: String): Request? = try {
        Request.Builder().url(url).header("Authorization", "Bearer ${config.token.trim()}").build()
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun readLimited(body: ResponseBody, maxBytes: Long): String {
        val declared = body.contentLength()
        if (declared > maxBytes) throw HaHttpException("go2rtc streams response too large: $declared bytes")
        val source = body.source()
        // request() buffers at most maxBytes + 1 bytes, so an oversized chunked body is never read in full.
        if (source.request(maxBytes + 1)) throw HaHttpException("go2rtc streams response too large (limit $maxBytes bytes)")
        return source.readUtf8()
    }

    companion object {
        /** A stream list with producers/consumers is a few KB per camera; this leaves ample room. */
        const val MAX_STREAMS_BYTES: Long = 1024L * 1024
    }
}
