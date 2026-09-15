package com.matiasnl.hakiosk.ui.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.matiasnl.hakiosk.camera.webrtc.CameraStreamError
import com.matiasnl.hakiosk.camera.webrtc.CameraStreamState
import com.matiasnl.hakiosk.camera.webrtc.RemoteVideoTrack
import com.matiasnl.hakiosk.camera.webrtc.WebRtcSessionManager
import com.matiasnl.hakiosk.data.ha.HaRepository
import com.matiasnl.hakiosk.data.ha.camera.CameraLiveSource
import com.matiasnl.hakiosk.data.ha.camera.HaCameraSource
import com.matiasnl.hakiosk.data.ha.camera.HaCameraStreamType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface CameraScreenMode {
    /** Asking HA which stream types the camera supports. */
    data object Loading : CameraScreenMode

    data object Connecting : CameraScreenMode

    data class Playing(val video: RemoteVideoTrack, val hasAudio: Boolean, val muted: Boolean) : CameraScreenMode

    /** No live video: the screen shows refreshing snapshots instead. */
    data class SnapshotFallback(val reason: FallbackReason) : CameraScreenMode
}

sealed interface FallbackReason {
    /** The camera doesn't list WebRTC in HA (no go2rtc / unsupported stream). Never for a chosen go2rtc stream. */
    data object NoWebRtc : FallbackReason

    /** WebRTC was tried and failed; the user can retry. */
    data class LiveFailed(val error: CameraStreamError) : FallbackReason
}

data class CameraUiState(val title: String, val mode: CameraScreenMode)

/**
 * Focus view of one camera. Plays the go2rtc [stream] through Frigate when one is chosen, otherwise
 * Home Assistant's own WebRTC stream. Streams through the app-wide [WebRtcSessionManager] only between
 * [onStart] and [onStop] (the screen ties them to its lifecycle), so leaving the screen or
 * backgrounding the app always releases the WebRTC connection.
 */
class CameraViewModel(
    private val entityId: String,
    private val label: String?,
    haRepository: HaRepository,
    private val cameraSource: HaCameraSource,
    private val sessionManager: WebRtcSessionManager,
    /** go2rtc stream to play through Frigate; null or blank plays Home Assistant's own stream. */
    stream: String? = null,
) : ViewModel() {

    private val liveSource = CameraLiveSource.of(entityId, stream)

    /** null until known. */
    private val webRtcSupported = MutableStateFlow<Boolean?>(null)

    /** False when the stream types query failed; WebRTC is tried anyway and the query repeated next start. */
    private var supportResolved = false
    private var started = false
    private var startJob: Job? = null

    val uiState: StateFlow<CameraUiState> = combine(
        haRepository.entities,
        webRtcSupported,
        sessionManager.state,
    ) { entities, supported, stream ->
        CameraUiState(
            title = label ?: entities[entityId]?.friendlyName ?: entityId,
            mode = modeFor(supported, stream),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CameraUiState(label ?: haRepository.entities.value[entityId]?.friendlyName ?: entityId, CameraScreenMode.Loading),
    )

    private fun modeFor(supported: Boolean?, stream: CameraStreamState): CameraScreenMode = when (supported) {
        null -> CameraScreenMode.Loading
        false -> CameraScreenMode.SnapshotFallback(FallbackReason.NoWebRtc)
        true -> when {
            stream is CameraStreamState.Playing && stream.entityId == entityId ->
                CameraScreenMode.Playing(stream.video, stream.hasAudio, stream.muted)
            stream is CameraStreamState.Failed && stream.entityId == entityId ->
                CameraScreenMode.SnapshotFallback(FallbackReason.LiveFailed(stream.error))
            // Idle or another camera's leftover state: this screen is about to (re)start its session.
            else -> CameraScreenMode.Connecting
        }
    }

    fun onStart() {
        started = true
        connect()
    }

    fun onStop() {
        started = false
        startJob?.cancel()
        sessionManager.stop(entityId)
    }

    fun retry() {
        if (started) connect()
    }

    fun setMuted(muted: Boolean) = sessionManager.setMuted(muted)

    private fun connect() {
        startJob?.cancel()
        startJob = viewModelScope.launch {
            // `camera/capabilities` describes HA's own stream; a go2rtc stream is simply tried (failures fall back).
            if (liveSource is CameraLiveSource.Go2rtc) {
                webRtcSupported.value = true
            } else if (!supportResolved) {
                val types = try {
                    cameraSource.streamTypes(entityId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                supportResolved = types.isSuccess
                webRtcSupported.value = types.getOrNull()?.contains(HaCameraStreamType.WEB_RTC) ?: true
            }
            if (started && webRtcSupported.value == true) sessionManager.start(liveSource)
        }
    }

    override fun onCleared() {
        sessionManager.stop(entityId)
    }

    companion object {
        fun factory(
            entityId: String,
            label: String?,
            haRepository: HaRepository,
            cameraSource: HaCameraSource,
            sessionManager: WebRtcSessionManager,
            stream: String? = null,
        ) = viewModelFactory {
            initializer { CameraViewModel(entityId, label, haRepository, cameraSource, sessionManager, stream) }
        }
    }
}
