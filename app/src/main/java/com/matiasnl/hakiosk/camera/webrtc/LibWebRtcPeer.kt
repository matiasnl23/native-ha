package com.matiasnl.hakiosk.camera.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import com.matiasnl.hakiosk.data.ha.camera.HaIceCandidate
import com.matiasnl.hakiosk.data.ha.camera.HaWebRtcClientConfig
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val TAG = "HaKioskWebRtc"

/**
 * Root EGL context shared by the hardware decoder and the renderer. Reference counted so it lives
 * exactly as long as a peer or a bound renderer uses it, and is released when the last live view closes.
 */
internal class SharedEglBase {
    private var eglBase: EglBase? = null
    private var references = 0

    @Synchronized
    fun acquire(): EglBase.Context {
        val egl = eglBase ?: EglBase.create().also { eglBase = it }
        references++
        return egl.eglBaseContext
    }

    @Synchronized
    fun release() {
        if (references == 0) return
        references--
        if (references == 0) {
            eglBase?.release()
            eglBase = null
        }
    }
}

internal class LibWebRtcVideoTrack(
    private val track: VideoTrack,
    private val sharedEgl: SharedEglBase,
) : RemoteVideoTrack {
    private val boundRenderers = mutableSetOf<VideoRenderer>()
    private var disposed = false

    @Synchronized
    override fun bind(renderer: VideoRenderer): Boolean {
        if (disposed || renderer in boundRenderers) return false
        renderer.initRenderer(sharedEgl.acquire())
        track.addSink(renderer)
        boundRenderers += renderer
        return true
    }

    override fun unbind(renderer: VideoRenderer) {
        val wasBound = synchronized(this) {
            val removed = boundRenderers.remove(renderer)
            if (removed && !disposed) track.removeSink(renderer)
            removed
        }
        if (wasBound) {
            renderer.release() // Blocks until the render thread dropped its EGL context.
            sharedEgl.release()
        }
    }

    /** Detaches renderers before the native track is disposed. Renderers keep their EGL reference until unbound. */
    @Synchronized
    fun dispose() {
        if (disposed) return
        boundRenderers.forEach { track.removeSink(it) }
        disposed = true
    }
}

/** Creates [LibWebRtcPeer]s. Loads the native library once per process, on first use. */
class LibWebRtcPeerFactory(context: Context) : WebRtcPeerFactory {
    private val appContext = context.applicationContext
    private val sharedEgl = SharedEglBase()
    private var initialized = false

    override fun create(config: HaWebRtcClientConfig, receiveAudio: Boolean): WebRtcPeer {
        synchronized(this) {
            if (!initialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
                )
                initialized = true
            }
        }
        return LibWebRtcPeer(appContext, config, receiveAudio, sharedEgl)
    }
}

/**
 * One receive-only peer connection with its own factory, audio device module and decoders, so
 * [close] frees everything (a kiosk runs 24/7; nothing may accumulate across sessions).
 */
internal class LibWebRtcPeer(
    context: Context,
    config: HaWebRtcClientConfig,
    receiveAudio: Boolean,
    private val sharedEgl: SharedEglBase,
) : WebRtcPeer {
    private val eventChannel = Channel<PeerEvent>(Channel.UNLIMITED)
    override val events: Flow<PeerEvent> = eventChannel.receiveAsFlow()

    private val lock = Any()

    @Volatile
    private var closed = false
    private val firstFrame = AtomicBoolean(false)

    private val firstFrameSink = VideoSink {
        if (firstFrame.compareAndSet(false, true)) eventChannel.trySend(PeerEvent.FirstVideoFrame)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            eventChannel.trySend(
                PeerEvent.LocalCandidate(HaIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)),
            )
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            val mapped = when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> PeerIceState.CONNECTED
                PeerConnection.IceConnectionState.DISCONNECTED -> PeerIceState.DISCONNECTED
                PeerConnection.IceConnectionState.FAILED -> PeerIceState.FAILED
                PeerConnection.IceConnectionState.CLOSED -> PeerIceState.CLOSED
                else -> PeerIceState.CHECKING
            }
            eventChannel.trySend(PeerEvent.IceStateChanged(mapped))
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            // The callback's transceiver wrapper is owned by the JNI observer; use our own cached tracks.
            when (transceiver.mediaType) {
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> eventChannel.trySend(PeerEvent.VideoTrackAdded(videoHandle))
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> eventChannel.trySend(PeerEvent.AudioTrackAdded)
                else -> Unit
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private val eglContext: EglBase.Context = sharedEgl.acquire()
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private val peerConnection: PeerConnection
    private val remoteVideoTrack: VideoTrack
    private val remoteAudioTrack: AudioTrack?
    private val videoHandle: LibWebRtcVideoTrack

    init {
        try {
            val adm = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .createAudioDeviceModule()
            audioDeviceModule = adm
            val pcFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                // Hardware (MediaCodec) decoders first, software fallback (libvpx/dav1d/platform).
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
                // Never used for recvonly; avoids a null encoder factory in the media engine.
                .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
                .createPeerConnectionFactory()
            factory = pcFactory

            val rtcConfig = PeerConnection.RTCConfiguration(
                config.iceServers.map { server ->
                    PeerConnection.IceServer.builder(server.urls)
                        .apply {
                            server.username?.let(::setUsername)
                            server.credential?.let(::setPassword)
                        }
                        .createIceServer()
                },
            ).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            }
            peerConnection = pcFactory.createPeerConnection(rtcConfig, observer)
                ?: error("createPeerConnection returned null")

            val recvOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            val videoTransceiver = peerConnection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recvOnly)
            // Without an audio m-line the remote never sends audio, so nothing is decoded or played out.
            val audioTransceiver = if (receiveAudio) {
                peerConnection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnly)
            } else {
                null
            }
            remoteVideoTrack = videoTransceiver.receiver.track() as VideoTrack
            remoteAudioTrack = audioTransceiver?.receiver?.track() as? AudioTrack
            remoteAudioTrack?.setEnabled(false) // Muted by default.
            videoHandle = LibWebRtcVideoTrack(remoteVideoTrack, sharedEgl)
            remoteVideoTrack.addSink(firstFrameSink)
        } catch (e: Throwable) {
            factory?.dispose()
            audioDeviceModule?.release()
            sharedEgl.release()
            throw e
        }
    }

    override suspend fun createOffer(): Result<String> = suspendCancellableCoroutine { continuation ->
        if (closed) {
            continuation.resume(Result.failure(IllegalStateException("Peer closed")))
            return@suspendCancellableCoroutine
        }
        peerConnection.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription) {
                if (closed) return
                peerConnection.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() = continuation.resume(Result.success(description.description))
                    override fun onSetFailure(error: String?) =
                        continuation.resume(Result.failure(IllegalStateException("setLocalDescription: $error")))
                }, description)
            }

            override fun onCreateFailure(error: String?) =
                continuation.resume(Result.failure(IllegalStateException("createOffer: $error")))
        }, MediaConstraints())
    }

    override suspend fun setRemoteAnswer(sdp: String): Result<Unit> = suspendCancellableCoroutine { continuation ->
        if (closed) {
            continuation.resume(Result.failure(IllegalStateException("Peer closed")))
            return@suspendCancellableCoroutine
        }
        peerConnection.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() = continuation.resume(Result.success(Unit))
            override fun onSetFailure(error: String?) =
                continuation.resume(Result.failure(IllegalStateException("setRemoteDescription: $error")))
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    override fun addRemoteCandidate(candidate: HaIceCandidate): Boolean = synchronized(lock) {
        if (closed) return false
        val added = peerConnection.addIceCandidate(
            IceCandidate(candidate.sdpMid.orEmpty(), candidate.sdpMLineIndex ?: 0, candidate.candidate),
        )
        if (!added) Log.w(TAG, "Remote ICE candidate rejected: ${candidate.candidate}")
        added
    }

    override fun setAudioEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (!closed) remoteAudioTrack?.setEnabled(enabled)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        // Never hold [lock] while disposing: dispose() waits for libwebrtc's signaling thread.
        videoHandle.dispose()
        remoteVideoTrack.removeSink(firstFrameSink)
        eventChannel.close()
        peerConnection.dispose() // Also disposes transceivers, receivers and their remote tracks.
        factory?.dispose()
        audioDeviceModule?.release()
        sharedEgl.release()
    }
}

private abstract class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = Unit
    override fun onSetFailure(error: String?) = Unit
}
