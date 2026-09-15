package com.matiasnl.hakiosk.camera.webrtc

import com.matiasnl.hakiosk.data.ha.camera.CameraLiveSource
import com.matiasnl.hakiosk.data.ha.camera.HaCameraSource
import com.matiasnl.hakiosk.data.ha.camera.HaIceCandidate
import com.matiasnl.hakiosk.data.ha.camera.HaWebRtcClientConfig
import com.matiasnl.hakiosk.data.ha.camera.HaWebRtcEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** No video frame within this time after starting (or reconnecting) fails the session. */
const val WEBRTC_MEDIA_TIMEOUT_MS = 15_000L

/** ICE "disconnected" often recovers by itself; only reconnect if it lasts longer than this. */
const val WEBRTC_DISCONNECT_GRACE_MS = 4_000L

/** Automatic reconnects after ICE failure before giving up. Resets once media flows again. */
const val WEBRTC_MAX_RECONNECTS = 1

/**
 * Owns at most one WebRTC camera session. The app-wide instance in `CameraModule` is the focus view's
 * single session; live thumbnails get their own instances, each also limited to one session.
 *
 * Signaling: [HaCameraSource.webRtcClientConfig] → peer with those ICE servers → recvonly offer →
 * [HaCameraSource.webRtcSession] for [CameraLiveSource.HomeAssistant], or
 * [HaCameraSource.go2rtcWebRtcSession] for [CameraLiveSource.Go2rtc]. On the HA path local ICE
 * candidates are buffered until HA assigns a session id; go2rtc pulls them from a queue as soon as its
 * socket is open. Remote candidates are buffered until the answer is applied.
 *
 * [start] always tears down the previous peer (and waits for it to be closed) before creating a new
 * one; [stop] cancels the session and the peer is closed right after on [scope]. All peer calls happen
 * on [scope], which should be single-threaded (see `CameraModule`).
 */
class WebRtcSessionManager(
    private val source: HaCameraSource,
    private val peerFactory: WebRtcPeerFactory,
    private val scope: CoroutineScope,
    private val mediaTimeoutMillis: Long = WEBRTC_MEDIA_TIMEOUT_MS,
    private val disconnectGraceMillis: Long = WEBRTC_DISCONNECT_GRACE_MS,
    private val maxReconnects: Int = WEBRTC_MAX_RECONNECTS,
    /** False negotiates video only: no audio decoding or playout (live thumbnails are always silent). */
    private val receiveAudio: Boolean = true,
    /** Diagnostics sink (Logcat in the app); kept as a lambda so the class stays JVM-testable. */
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<CameraStreamState>(CameraStreamState.Idle)
    val state: StateFlow<CameraStreamState> = _state.asStateFlow()

    private val lock = Any()
    private var sessionJob: Job? = null
    private var sessionEntityId: String? = null

    /** Identity of the current session; a cancelled session still finishing a step can't publish. */
    private var sessionToken: Any? = null

    @Volatile
    private var muted = true

    @Volatile
    private var activePeer: WebRtcPeer? = null

    /** Starts streaming Home Assistant's own stream of [entityId]; see [start]. */
    fun start(entityId: String) = start(CameraLiveSource.HomeAssistant(entityId))

    /** Starts streaming [source], closing any other session first. Restarts if already running. */
    fun start(source: CameraLiveSource) {
        val entityId = source.entityId
        synchronized(lock) {
            val previous = sessionJob
            previous?.cancel()
            val token = Any()
            sessionToken = token
            sessionEntityId = entityId
            muted = true
            _state.value = CameraStreamState.Connecting(entityId)
            sessionJob = scope.launch {
                previous?.cancelAndJoin()
                runSession(token, source)
            }
        }
    }

    /**
     * Stops the session. With [entityId], only stops if that camera is the current one, so a screen
     * being disposed can't kill a session another screen just started.
     */
    fun stop(entityId: String? = null) {
        synchronized(lock) {
            if (entityId != null && entityId != sessionEntityId) return
            sessionJob?.cancel()
            sessionJob = null
            sessionEntityId = null
            sessionToken = null
            _state.value = CameraStreamState.Idle
        }
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
        scope.launch { activePeer?.setAudioEnabled(!muted) }
        _state.update { if (it is CameraStreamState.Playing) it.copy(muted = muted) else it }
    }

    private suspend fun runSession(token: Any, liveSource: CameraLiveSource) {
        val entityId = liveSource.entityId
        var reconnects = 0
        while (true) {
            publish(token, CameraStreamState.Connecting(entityId))
            val failure = runAttempt(token, liveSource)
            if (failure.reachedMedia) reconnects = 0
            if (failure.retryable && reconnects < maxReconnects) {
                reconnects++
                continue
            }
            publish(token, CameraStreamState.Failed(entityId, failure.error))
            return
        }
    }

    private fun publish(token: Any, state: CameraStreamState) {
        synchronized(lock) {
            if (sessionToken === token) _state.value = state
        }
    }

    private class AttemptFailure(
        val error: CameraStreamError,
        val retryable: Boolean = false,
        val reachedMedia: Boolean = false,
    )

    private suspend fun runAttempt(token: Any, liveSource: CameraLiveSource): AttemptFailure {
        val entityId = liveSource.entityId
        val config = callSource { source.webRtcClientConfig(entityId) }.getOrElse {
            when (liveSource) {
                is CameraLiveSource.HomeAssistant ->
                    return AttemptFailure(CameraStreamError.Setup("ICE config: ${it.message}"))
                // go2rtc usually answers with host candidates reachable on the LAN; try without STUN/TURN.
                is CameraLiveSource.Go2rtc -> {
                    log("ICE config unavailable, trying go2rtc without ICE servers: ${it.message}")
                    HaWebRtcClientConfig(emptyList())
                }
            }
        }
        val peer = try {
            peerFactory.create(config, receiveAudio)
        } catch (e: Exception) {
            return AttemptFailure(CameraStreamError.Setup("Peer connection: ${e.message}"))
        } catch (e: LinkageError) {
            return AttemptFailure(CameraStreamError.Setup("libwebrtc unavailable: ${e.message}"))
        }
        // No suspension point between create() and try: a cancellation can't leak the peer.
        activePeer = peer
        try {
            return coroutineScope {
                val failure = eventLoop(token, liveSource, peer)
                coroutineContext.cancelChildren()
                failure
            }
        } finally {
            activePeer = null
            peer.close()
        }
    }

    private sealed interface Signal {
        data class Ha(val event: HaWebRtcEvent) : Signal
        data class Peer(val event: PeerEvent) : Signal
        data class SignalingEnded(val cause: Throwable?) : Signal
        data object MediaTimeout : Signal
        data object DisconnectTimeout : Signal
    }

    private suspend fun CoroutineScope.eventLoop(
        token: Any,
        liveSource: CameraLiveSource,
        peer: WebRtcPeer,
    ): AttemptFailure {
        val entityId = liveSource.entityId
        val inbox = Channel<Signal>(Channel.UNLIMITED)
        launch { peer.events.collect { inbox.send(Signal.Peer(it)) } }

        val offer = peer.createOffer()
            .getOrElse { return AttemptFailure(CameraStreamError.Setup("Offer: ${it.message}")) }

        // go2rtc only: local candidates queued for the signaling flow, which sends them while collected.
        // Dropped with this attempt's scope; never read by a later attempt.
        val go2rtcLocalCandidates = (liveSource as? CameraLiveSource.Go2rtc)?.let { Channel<HaIceCandidate>(Channel.UNLIMITED) }
        val signaling = when (liveSource) {
            is CameraLiveSource.HomeAssistant -> source.webRtcSession(entityId, offer)
            is CameraLiveSource.Go2rtc ->
                source.go2rtcWebRtcSession(entityId, liveSource.stream, offer, go2rtcLocalCandidates!!.receiveAsFlow())
        }

        launch {
            try {
                signaling.collect { inbox.send(Signal.Ha(it)) }
                inbox.send(Signal.SignalingEnded(null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                inbox.send(Signal.SignalingEnded(e))
            }
        }
        val timeoutJob = launch {
            delay(mediaTimeoutMillis)
            inbox.send(Signal.MediaTimeout)
        }

        var sessionId: String? = null
        val pendingLocal = mutableListOf<HaIceCandidate>()
        var remoteDescriptionSet = false
        val pendingRemote = mutableListOf<HaIceCandidate>()
        var video: RemoteVideoTrack? = null
        var hasAudio = false
        var mediaFlowing = false
        var disconnectJob: Job? = null

        fun sendLocal(id: String, candidate: HaIceCandidate) {
            // Best effort (needs HA 2024.12+): the answer's candidates may still connect without ours.
            launch {
                callSource { source.sendWebRtcCandidate(entityId, id, candidate) }
                    .onFailure { log("Sending local ICE candidate failed: ${it.message}") }
            }
        }

        fun publishPlaying() {
            val track = video ?: return
            if (mediaFlowing) {
                publish(token, CameraStreamState.Playing(entityId, track, hasAudio, muted))
            }
        }

        for (signal in inbox) {
            when (signal) {
                is Signal.Ha -> when (val event = signal.event) {
                    is HaWebRtcEvent.Session -> {
                        sessionId = event.sessionId
                        pendingLocal.forEach { sendLocal(event.sessionId, it) }
                        pendingLocal.clear()
                    }
                    is HaWebRtcEvent.Answer -> {
                        peer.setRemoteAnswer(event.sdp).onFailure {
                            return AttemptFailure(CameraStreamError.Setup("Answer: ${it.message}"))
                        }
                        remoteDescriptionSet = true
                        pendingRemote.forEach { peer.addRemoteCandidate(it) }
                        pendingRemote.clear()
                    }
                    is HaWebRtcEvent.RemoteCandidate -> {
                        if (event.candidate.candidate.isBlank()) continue // end-of-candidates marker
                        if (remoteDescriptionSet) peer.addRemoteCandidate(event.candidate) else pendingRemote += event.candidate
                    }
                    // May arrive first, without a Session (e.g. webrtc_offer_failed, not_connected).
                    is HaWebRtcEvent.Error ->
                        return AttemptFailure(CameraStreamError.Signaling(event.code, event.message), reachedMedia = mediaFlowing)
                }
                is Signal.SignalingEnded -> {
                    // Once media flows the peer connection carries on by itself; ICE state tells if it dies.
                    if (!mediaFlowing) {
                        val message = signal.cause?.message ?: "Signaling session ended without an answer"
                        return AttemptFailure(CameraStreamError.Signaling("ended", message))
                    }
                }
                is Signal.Peer -> when (val event = signal.event) {
                    is PeerEvent.LocalCandidate -> if (go2rtcLocalCandidates != null) {
                        go2rtcLocalCandidates.trySend(event.candidate)
                    } else {
                        val id = sessionId
                        if (id != null) sendLocal(id, event.candidate) else pendingLocal += event.candidate
                    }
                    is PeerEvent.VideoTrackAdded -> {
                        video = event.track
                        publishPlaying()
                    }
                    PeerEvent.AudioTrackAdded -> {
                        hasAudio = true
                        peer.setAudioEnabled(!muted)
                        publishPlaying()
                    }
                    PeerEvent.FirstVideoFrame -> {
                        mediaFlowing = true
                        timeoutJob.cancel()
                        publishPlaying()
                    }
                    is PeerEvent.IceStateChanged -> when (event.state) {
                        PeerIceState.CONNECTED -> {
                            disconnectJob?.cancel()
                            disconnectJob = null
                        }
                        PeerIceState.DISCONNECTED -> if (disconnectJob == null) {
                            disconnectJob = launch {
                                delay(disconnectGraceMillis)
                                inbox.send(Signal.DisconnectTimeout)
                            }
                        }
                        PeerIceState.FAILED ->
                            return AttemptFailure(CameraStreamError.ConnectionLost, retryable = true, reachedMedia = mediaFlowing)
                        PeerIceState.CHECKING, PeerIceState.CLOSED -> Unit
                    }
                }
                Signal.MediaTimeout -> return AttemptFailure(CameraStreamError.Timeout(mediaTimeoutMillis))
                Signal.DisconnectTimeout ->
                    return AttemptFailure(CameraStreamError.ConnectionLost, retryable = true, reachedMedia = mediaFlowing)
            }
        }
        return AttemptFailure(CameraStreamError.Setup("Event loop ended"))
    }

    /** Treats exceptions thrown by the source like failed results, without swallowing cancellation. */
    private suspend fun <T> callSource(block: suspend () -> Result<T>): Result<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
