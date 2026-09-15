package com.matiasnl.hakiosk.data.ha.camera

import com.matiasnl.hakiosk.data.ha.HaNetworkErrors
import com.matiasnl.hakiosk.data.ha.HaServerConfig
import com.matiasnl.hakiosk.data.ha.rest.HaHttpException
import com.matiasnl.hakiosk.data.ha.rest.await
import com.matiasnl.hakiosk.data.ha.ws.HaClientSettings
import com.matiasnl.hakiosk.data.ha.ws.HaRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP and WebSocket client for Frigate's go2rtc behind the Frigate HA integration proxy, authenticated
 * with the HA token as a Bearer header (never in URLs or messages). Protocol details in
 * [FrigateGo2rtcProtocol].
 */
class FrigateGo2rtcClient(
    client: OkHttpClient,
    private val settings: HaClientSettings = HaClientSettings(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // Derived client shares the connection pool and dispatcher with the shared one.
    private val restClient = client.newBuilder().callTimeout(settings.requestTimeoutMillis, TimeUnit.MILLISECONDS).build()

    // No call timeout (it would bound the whole session); the shared client's ping interval detects dead sockets.
    private val webSocketClient = client

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
                    code == 404 -> PROXY_NOT_FOUND
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

    /**
     * One go2rtc WebRTC signaling session for [stream] of Frigate instance [clientId]; see
     * [HaCameraSource.go2rtcWebRtcSession]. Failures end with a terminal [HaWebRtcEvent.Error], never an
     * exception. The wait for the handshake and the answer is bounded by [HaClientSettings.requestTimeoutMillis].
     */
    fun webRtcSession(
        config: HaServerConfig,
        clientId: String,
        stream: String,
        offerSdp: String,
        localCandidates: Flow<HaIceCandidate>,
    ): Flow<HaWebRtcEvent> = flow {
        val url = FrigateGo2rtcProtocol.signalingUrl(config.baseUrl, clientId, stream)
        val request = url?.let { authorizedRequest(config, it.toString()) }
        if (request == null) {
            val message = if (url == null) "Invalid server URL" else "Invalid access token"
            emit(HaWebRtcEvent.Error(CODE_CONNECTION_FAILED, message))
            return@flow
        }
        val signals = Channel<Signal>(Channel.UNLIMITED)
        val socket = webSocketClient.newWebSocket(request, SignalListener(signals))
        try {
            // OkHttp queues frames until the upgrade completes, so the offer always goes first.
            socket.send(FrigateGo2rtcProtocol.offerMessage(offerSdp))
            coroutineScope {
                val forwarder = launch { forwardCandidates(socket, localCandidates) }
                try {
                    this@flow.relay(signals)
                } finally {
                    forwarder.cancel()
                }
            }
        } finally {
            if (!socket.close(NORMAL_CLOSURE, null)) socket.cancel()
        }
    }

    private suspend fun forwardCandidates(socket: WebSocket, candidates: Flow<HaIceCandidate>) {
        try {
            candidates.collect { candidate ->
                // go2rtc queues candidates that arrive before its answer.
                if (candidate.candidate.isNotBlank()) socket.send(FrigateGo2rtcProtocol.candidateMessage(candidate.candidate))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The candidate source failed: stop forwarding; ICE failure surfaces in the peer connection.
        }
    }

    private suspend fun FlowCollector<HaWebRtcEvent>.relay(signals: Channel<Signal>) {
        // The timeout runs on the IO dispatcher, independent of the collector's clock.
        val first = withContext(ioDispatcher) {
            withTimeoutOrNull(settings.requestTimeoutMillis) { awaitAnswer(signals) }
        } ?: HaWebRtcEvent.Error(HaRequestException.CODE_TIMEOUT, "go2rtc did not answer the offer in time")
        emit(first)
        if (first !is HaWebRtcEvent.Answer) return
        val mid = FrigateGo2rtcProtocol.firstMid(first.sdp)
        while (true) {
            val event = when (val signal = signals.receive()) {
                is Signal.Message -> FrigateGo2rtcProtocol.parseEvent(signal.text, mid)
                is Signal.Closed, is Signal.Failed ->
                    HaWebRtcEvent.Error(HaRequestException.CODE_CONNECTION_LOST, "go2rtc connection closed")
            }
            if (event == null || event is HaWebRtcEvent.Answer) continue
            emit(event)
            if (event is HaWebRtcEvent.Error) return
        }
    }

    private suspend fun awaitAnswer(signals: Channel<Signal>): HaWebRtcEvent {
        while (true) {
            when (val signal = signals.receive()) {
                is Signal.Message -> {
                    val event = FrigateGo2rtcProtocol.parseEvent(signal.text, sdpMid = "0")
                    // Candidates never precede the answer; anything else before it is skipped.
                    if (event is HaWebRtcEvent.Answer || event is HaWebRtcEvent.Error) return event
                }
                is Signal.Failed -> return handshakeError(signal)
                is Signal.Closed -> return HaWebRtcEvent.Error(
                    HaRequestException.CODE_CONNECTION_LOST,
                    "go2rtc closed the connection before answering (code ${signal.code})",
                )
            }
        }
    }

    private fun handshakeError(failure: Signal.Failed): HaWebRtcEvent.Error = when (val code = failure.httpCode) {
        401 -> HaWebRtcEvent.Error(CODE_UNAUTHORIZED, "Invalid access token (HTTP 401)")
        403 -> HaWebRtcEvent.Error(CODE_FORBIDDEN, "Access denied (HTTP 403)")
        404 -> HaWebRtcEvent.Error(CODE_PROXY_NOT_FOUND, PROXY_NOT_FOUND)
        null -> HaWebRtcEvent.Error(
            CODE_CONNECTION_FAILED,
            "go2rtc signaling failed: ${HaNetworkErrors.describe(failure.error)}",
        )
        else -> HaWebRtcEvent.Error(CODE_CONNECTION_FAILED, "go2rtc signaling handshake failed (HTTP $code)")
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

    private sealed interface Signal {
        data class Message(val text: String) : Signal
        data class Closed(val code: Int) : Signal
        class Failed(val httpCode: Int?, val error: Throwable) : Signal
    }

    /** Bridges OkHttp callbacks (its own threads) into the session's channel. */
    private class SignalListener(private val signals: Channel<Signal>) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            signals.trySend(Signal.Message(text))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            signals.trySend(Signal.Closed(code))
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            signals.trySend(Signal.Closed(code))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            signals.trySend(Signal.Failed(response?.code, t))
        }
    }

    companion object {
        /** A stream list with producers/consumers is a few KB per camera; this leaves ample room. */
        const val MAX_STREAMS_BYTES: Long = 1024L * 1024

        const val CODE_UNAUTHORIZED = "unauthorized"
        const val CODE_FORBIDDEN = "forbidden"
        const val CODE_PROXY_NOT_FOUND = "proxy_not_found"
        const val CODE_CONNECTION_FAILED = "connection_failed"

        private const val NORMAL_CLOSURE = 1000
        private const val PROXY_NOT_FOUND =
            "Frigate integration proxy not found; Frigate integration v5.15.3+ is required (HTTP 404)"
    }
}
