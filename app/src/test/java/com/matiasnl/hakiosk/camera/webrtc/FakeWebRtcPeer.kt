package com.matiasnl.hakiosk.camera.webrtc

import com.matiasnl.hakiosk.data.ha.camera.HaIceCandidate
import com.matiasnl.hakiosk.data.ha.camera.HaWebRtcClientConfig
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.webrtc.SurfaceViewRenderer

class FakeRemoteVideoTrack(val name: String = "video") : RemoteVideoTrack {
    override fun bind(renderer: SurfaceViewRenderer): Boolean = false
    override fun unbind(renderer: SurfaceViewRenderer) = Unit
}

/** Peer that records every call in [log] and lets the test emit libwebrtc events. */
class FakeWebRtcPeer(
    val config: HaWebRtcClientConfig,
    private val offerSdp: String,
    val receiveAudio: Boolean = true,
) : WebRtcPeer {
    private val channel = Channel<PeerEvent>(Channel.UNLIMITED)
    override val events: Flow<PeerEvent> = channel.receiveAsFlow()

    val log = mutableListOf<String>()
    var answerResult: Result<Unit> = Result.success(Unit)
    var audioEnabled: Boolean? = null
        private set
    var isClosed = false
        private set

    fun emit(event: PeerEvent) {
        channel.trySend(event)
    }

    override suspend fun createOffer(): Result<String> {
        check(!isClosed) { "createOffer after close" }
        log += "offer"
        return Result.success(offerSdp)
    }

    override suspend fun setRemoteAnswer(sdp: String): Result<Unit> {
        check(!isClosed) { "setRemoteAnswer after close" }
        log += "answer:$sdp"
        return answerResult
    }

    override fun addRemoteCandidate(candidate: HaIceCandidate): Boolean {
        check(!isClosed) { "addRemoteCandidate after close" }
        log += "candidate:${candidate.candidate}"
        return true
    }

    override fun setAudioEnabled(enabled: Boolean) {
        audioEnabled = enabled
    }

    override fun close() {
        isClosed = true
        channel.close()
    }
}

class FakeWebRtcPeerFactory : WebRtcPeerFactory {
    val peers = mutableListOf<FakeWebRtcPeer>()

    /** For each created peer, how many earlier peers were still open at that moment. */
    val openPeersAtCreation = mutableListOf<Int>()
    var failWith: Exception? = null

    val lastPeer: FakeWebRtcPeer get() = peers.last()

    override fun create(config: HaWebRtcClientConfig, receiveAudio: Boolean): WebRtcPeer {
        failWith?.let { throw it }
        openPeersAtCreation += peers.count { !it.isClosed }
        return FakeWebRtcPeer(config, offerSdp = "offer-${peers.size + 1}", receiveAudio).also { peers += it }
    }
}
