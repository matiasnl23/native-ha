package com.matiasnl.hakiosk.camera.webrtc

/** State of the single app-wide WebRTC camera session. */
sealed interface CameraStreamState {
    data object Idle : CameraStreamState

    data class Connecting(val entityId: String) : CameraStreamState

    data class Playing(
        val entityId: String,
        val video: RemoteVideoTrack,
        val hasAudio: Boolean,
        val muted: Boolean,
    ) : CameraStreamState

    /** Terminal until [WebRtcSessionManager.start] is called again. */
    data class Failed(val entityId: String, val error: CameraStreamError) : CameraStreamState
}

/** Why a session failed. [message] is a developer-facing English description; the UI maps the type to Spanish text. */
sealed interface CameraStreamError {
    val message: String

    /** Could not get the ICE config, build the peer connection or create/apply the SDP. */
    data class Setup(override val message: String) : CameraStreamError

    /** Home Assistant/go2rtc rejected the offer or ended the signaling session. */
    data class Signaling(val code: String, override val message: String) : CameraStreamError

    /** Signaling may have worked but no video frame arrived in time. */
    data class Timeout(val timeoutMillis: Long) : CameraStreamError {
        override val message: String get() = "No video received within ${timeoutMillis / 1000} s"
    }

    /** ICE failed (or stayed disconnected) and the automatic reconnect did not help. */
    data object ConnectionLost : CameraStreamError {
        override val message: String get() = "WebRTC connection lost"
    }
}
