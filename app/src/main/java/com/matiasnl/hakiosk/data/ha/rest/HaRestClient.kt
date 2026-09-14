package com.matiasnl.hakiosk.data.ha.rest

import com.matiasnl.hakiosk.data.ha.HaConnectionTestResult
import com.matiasnl.hakiosk.data.ha.HaNetworkErrors
import com.matiasnl.hakiosk.data.ha.HaServerConfig
import com.matiasnl.hakiosk.data.ha.HaUrls
import com.matiasnl.hakiosk.data.ha.ws.HaProtocol.stringOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
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

/** One-off REST calls to the Home Assistant API (`/api/...`) authenticated with a Bearer token. */
class HaRestClient(
    client: OkHttpClient,
    callTimeoutMillis: Long = 15_000,
) {
    // Derived client shares the connection pool and dispatcher with the shared one.
    private val client = client.newBuilder().callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS).build()

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
