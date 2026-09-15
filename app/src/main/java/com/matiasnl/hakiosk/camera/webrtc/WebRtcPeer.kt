package com.matiasnl.hakiosk.camera.webrtc

import com.matiasnl.hakiosk.data.ha.camera.HaIceCandidate
import com.matiasnl.hakiosk.data.ha.camera.HaWebRtcClientConfig
import kotlinx.coroutines.flow.Flow
import org.webrtc.SurfaceViewRenderer

/**
 * Remote video of one peer connection, as seen by the UI. Rendering goes through [bind]/[unbind]
 * so the implementation controls the shared EGL context lifetime; test fakes never touch a renderer.
 */
interface RemoteVideoTrack {
    /**
     * Initializes [renderer] with the shared EGL context and starts feeding it frames. Returns false
     * when the track is already gone (the session closed meanwhile); the renderer is then untouched.
     * Main thread only.
     */
    fun bind(renderer: SurfaceViewRenderer): Boolean

    /** Stops feeding [renderer], releases it and drops its EGL reference. Safe after the peer closed. Main thread only. */
    fun unbind(renderer: SurfaceViewRenderer)
}

enum class PeerIceState { CHECKING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

/** Things a peer connection reports asynchronously (from libwebrtc's own threads). */
sealed interface PeerEvent {
    data class LocalCandidate(val candidate: HaIceCandidate) : PeerEvent

    /** The remote side is sending video; [track] can be rendered. */
    data class VideoTrackAdded(val track: RemoteVideoTrack) : PeerEvent

    /** The remote side is sending audio. The track starts disabled (muted). */
    data object AudioTrackAdded : PeerEvent

    /** A decoded video frame arrived: media is really flowing. Emitted once. */
    data object FirstVideoFrame : PeerEvent

    data class IceStateChanged(val state: PeerIceState) : PeerEvent
}

/**
 * A receive-only WebRTC peer connection (video and optionally audio transceivers). Abstracted so the signaling
 * state machine in [WebRtcSessionManager] runs on the JVM with a fake.
 *
 * Not thread-safe for concurrent signaling calls: the session manager drives it from one coroutine.
 */
interface WebRtcPeer {
    val events: Flow<PeerEvent>

    /** Creates the recvonly offer and sets it as local description. Returns the offer SDP. */
    suspend fun createOffer(): Result<String>

    suspend fun setRemoteAnswer(sdp: String): Result<Unit>

    /** Adds a remote ICE candidate. Must only be called after [setRemoteAnswer] succeeded. */
    fun addRemoteCandidate(candidate: HaIceCandidate): Boolean

    /** Enables/disables playout of the remote audio track, if any. */
    fun setAudioEnabled(enabled: Boolean)

    /** Releases the peer connection, its tracks, decoders and EGL reference. Idempotent. */
    fun close()
}

fun interface WebRtcPeerFactory {
    /**
     * Creates a recvonly peer with a video transceiver and, if [receiveAudio], an audio one.
     * May throw (e.g. native library missing on an unsupported ABI).
     */
    fun create(config: HaWebRtcClientConfig, receiveAudio: Boolean): WebRtcPeer
}
