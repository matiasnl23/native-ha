package com.matiasnl.hakiosk.camera.thumbnail

import com.matiasnl.hakiosk.camera.webrtc.CameraStreamState
import com.matiasnl.hakiosk.camera.webrtc.RemoteVideoTrack
import com.matiasnl.hakiosk.camera.webrtc.WebRtcSessionManager
import com.matiasnl.hakiosk.data.ha.camera.CameraLiveSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * After a live thumbnail fails (including the session manager's own reconnect), it shows snapshots for
 * this long before trying live video again, as long as it is still active. Long enough not to hammer a
 * camera or go2rtc that is down, short enough that a tile recovers by itself on a 24/7 kiosk.
 */
const val LIVE_THUMBNAIL_RETRY_DELAY_MS = 30_000L

/**
 * Runs live video for one thumbnail on its own [manager] until cancelled: starts [source], waits for a
 * terminal failure, waits [retryDelayMillis] and starts again. Cancelling (tile no longer active,
 * composable disposed, lifecycle below STARTED) always stops the session, which closes its peer.
 */
suspend fun runLiveThumbnail(
    manager: WebRtcSessionManager,
    source: CameraLiveSource,
    retryDelayMillis: Long = LIVE_THUMBNAIL_RETRY_DELAY_MS,
): Nothing {
    try {
        while (true) {
            // start() publishes Connecting synchronously, so a previous Failed can't satisfy the wait.
            manager.start(source)
            manager.state.first { it is CameraStreamState.Failed }
            delay(retryDelayMillis)
        }
    } finally {
        manager.stop()
    }
}

/** The video to render for [entityId], or null when live isn't playing (show snapshots instead). */
fun CameraStreamState.playingVideo(entityId: String): RemoteVideoTrack? =
    (this as? CameraStreamState.Playing)?.takeIf { it.entityId == entityId }?.video
