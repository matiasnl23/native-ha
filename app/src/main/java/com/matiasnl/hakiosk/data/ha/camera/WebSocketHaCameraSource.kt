package com.matiasnl.hakiosk.data.ha.camera

import com.matiasnl.hakiosk.data.ha.HaConfigStore
import com.matiasnl.hakiosk.data.ha.WebSocketHaRepository
import com.matiasnl.hakiosk.data.ha.rest.HaRestClient
import com.matiasnl.hakiosk.data.ha.ws.HaClientSettings
import com.matiasnl.hakiosk.data.ha.ws.HaRequestException
import com.matiasnl.hakiosk.data.ha.ws.HaSubscription
import com.matiasnl.hakiosk.data.ha.ws.HaWebSocketConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/**
 * [HaCameraSource] over the repository's live WebSocket connection (capabilities and WebRTC
 * signaling) and REST (`camera_proxy` snapshots, which work even while the WebSocket is down).
 * Command details are documented in [HaCameraProtocol]. Holds no per-camera state.
 */
class WebSocketHaCameraSource internal constructor(
    private val activeConnection: () -> HaWebSocketConnection?,
    private val configStore: HaConfigStore,
    private val restClient: HaRestClient,
    private val settings: HaClientSettings = HaClientSettings(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HaCameraSource {

    constructor(repository: WebSocketHaRepository, configStore: HaConfigStore, okHttpClient: OkHttpClient) :
        this(repository::activeConnection, configStore, HaRestClient(okHttpClient))

    override suspend fun fetchSnapshot(entityId: String, width: Int?): Result<ByteArray> {
        val config = configStore.config.first()
            ?: return Result.failure(IllegalStateException("Home Assistant is not configured"))
        return restClient.fetchCameraSnapshot(config, entityId, width)
    }

    override suspend fun streamTypes(entityId: String): Result<Set<HaCameraStreamType>> =
        command(HaCameraProtocol::parseStreamTypes) { id -> HaCameraProtocol.capabilities(id, entityId) }

    override suspend fun webRtcClientConfig(entityId: String): Result<HaWebRtcClientConfig> =
        command(HaCameraProtocol::parseClientConfig) { id -> HaCameraProtocol.webRtcClientConfig(id, entityId) }

    override suspend fun sendWebRtcCandidate(
        entityId: String,
        sessionId: String,
        candidate: HaIceCandidate,
    ): Result<Unit> = command({ }) { id -> HaCameraProtocol.webRtcCandidate(id, entityId, sessionId, candidate) }

    override fun webRtcSession(entityId: String, offerSdp: String): Flow<HaWebRtcEvent> = flow {
        val connection = activeConnection()
        if (connection == null) {
            emit(HaWebRtcEvent.Error(HaRequestException.CODE_NOT_CONNECTED, NOT_CONNECTED))
            return@flow
        }
        var started: HaSubscription? = null
        try {
            // Timeouts run on the IO dispatcher, independent of the collector's clock.
            withContext(dispatcher) {
                started = connection.subscribe(settings.requestTimeoutMillis) { id ->
                    HaCameraProtocol.webRtcOffer(id, entityId, offerSdp)
                }
            }
        } catch (e: CancellationException) {
            // Cancelled right after HA accepted the offer: don't leave the session open.
            started?.close()
            throw e
        } catch (e: HaRequestException) {
            emit(HaWebRtcEvent.Error(e.code ?: OFFER_FAILED, e.message ?: "WebRTC offer rejected"))
            return@flow
        }
        val subscription = checkNotNull(started)
        try {
            while (true) {
                val received = subscription.events.receiveCatching()
                if (received.isClosed) {
                    val cause = received.exceptionOrNull()
                    emit(
                        HaWebRtcEvent.Error(
                            code = (cause as? HaRequestException)?.code ?: HaRequestException.CODE_CONNECTION_LOST,
                            message = cause?.message ?: "Connection closed",
                        ),
                    )
                    return@flow
                }
                val event = HaCameraProtocol.parseWebRtcEvent(received.getOrThrow()) ?: continue
                emit(event)
                if (event is HaWebRtcEvent.Error) return@flow
            }
        } finally {
            // Stops routing and sends unsubscribe_events, which makes HA close the WebRTC session.
            subscription.close()
        }
    }

    private suspend fun <T : Any> command(parse: (JsonElement) -> T?, message: (id: Int) -> JsonObject): Result<T> {
        val connection = activeConnection()
            ?: return Result.failure(HaRequestException(NOT_CONNECTED, HaRequestException.CODE_NOT_CONNECTED))
        return try {
            val result = withContext(dispatcher) { connection.request(settings.requestTimeoutMillis, message = message) }
            parse(result)?.let { Result.success(it) }
                ?: Result.failure(HaRequestException("Invalid response from Home Assistant"))
        } catch (e: HaRequestException) {
            Result.failure(e)
        }
    }

    private companion object {
        const val NOT_CONNECTED = "Not connected to Home Assistant"
        const val OFFER_FAILED = "webrtc_offer_failed"
    }
}
