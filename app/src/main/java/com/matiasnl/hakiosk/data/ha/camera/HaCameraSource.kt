package com.matiasnl.hakiosk.data.ha.camera

import kotlinx.coroutines.flow.Flow

/** Stream types a camera supports in HA (`camera/capabilities` → `frontend_stream_types`). */
enum class HaCameraStreamType { WEB_RTC, HLS }

data class HaIceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

/** ICE configuration HA recommends for WebRTC clients (includes go2rtc/STUN servers when configured). */
data class HaWebRtcClientConfig(
    val iceServers: List<HaIceServer>,
)

data class HaIceCandidate(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int?,
)

/** Events of one WebRTC signaling session started with [HaCameraSource.webRtcSession]. */
sealed interface HaWebRtcEvent {
    /** HA assigned an id; needed to send local ICE candidates. Always the first event. */
    data class Session(val sessionId: String) : HaWebRtcEvent
    data class Answer(val sdp: String) : HaWebRtcEvent
    data class RemoteCandidate(val candidate: HaIceCandidate) : HaWebRtcEvent

    /** Terminal: the flow completes after emitting it. */
    data class Error(val code: String, val message: String) : HaWebRtcEvent
}

/**
 * Camera access through Home Assistant. Hides HTTP/WebSocket details; the WebRTC peer connection
 * and video rendering live in the camera-streaming module.
 */
interface HaCameraSource {
    /** Current JPEG from `/api/camera_proxy/<entityId>`. [width] asks HA to downscale when supported. */
    suspend fun fetchSnapshot(entityId: String, width: Int? = null): Result<ByteArray>

    suspend fun streamTypes(entityId: String): Result<Set<HaCameraStreamType>>

    suspend fun webRtcClientConfig(entityId: String): Result<HaWebRtcClientConfig>

    /**
     * Sends the local SDP offer and streams signaling events. Collect it for the lifetime of the
     * session; cancelling the collection ends the HA subscription. Fails with an [HaWebRtcEvent.Error]
     * (not an exception) when not connected or HA rejects the offer.
     */
    fun webRtcSession(entityId: String, offerSdp: String): Flow<HaWebRtcEvent>

    suspend fun sendWebRtcCandidate(entityId: String, sessionId: String, candidate: HaIceCandidate): Result<Unit>

    /**
     * go2rtc stream names reachable through the Frigate integration for the Frigate camera behind
     * [entityId] (its `client_id` attribute selects the Frigate instance), sorted. Fails when the entity
     * isn't a Frigate camera or the proxy can't be reached. Frigate integration v5.12+ with Frigate 0.17+.
     */
    suspend fun go2rtcStreams(entityId: String): Result<List<String>>

    /**
     * WebRTC signaling with go2rtc for [stream] through the Frigate integration's proxy
     * (`/api/frigate/<client_id>/webrtc/api/ws?src=<stream>`). Emits [HaWebRtcEvent.Answer] (always
     * before any candidate), [HaWebRtcEvent.RemoteCandidate] and a terminal [HaWebRtcEvent.Error], but
     * never [HaWebRtcEvent.Session]. Candidates from [localCandidates] are sent for as long as the flow
     * is collected; cancelling the collection closes the connection.
     */
    fun go2rtcWebRtcSession(
        entityId: String,
        stream: String,
        offerSdp: String,
        localCandidates: Flow<HaIceCandidate>,
    ): Flow<HaWebRtcEvent>
}

/** What a live view plays: Home Assistant's own WebRTC stream of the entity, or a named go2rtc stream through Frigate. */
sealed interface CameraLiveSource {
    val entityId: String

    data class HomeAssistant(override val entityId: String) : CameraLiveSource

    data class Go2rtc(override val entityId: String, val stream: String) : CameraLiveSource

    companion object {
        /** A blank or null [stream] means Home Assistant's own stream. */
        fun of(entityId: String, stream: String?): CameraLiveSource =
            stream?.trim()?.takeIf { it.isNotEmpty() }?.let { Go2rtc(entityId, it) } ?: HomeAssistant(entityId)
    }
}
