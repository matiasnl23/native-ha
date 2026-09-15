package com.matiasnl.hakiosk.ui.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.matiasnl.hakiosk.camera.CameraModule
import com.matiasnl.hakiosk.camera.thumbnail.THUMBNAIL_REFRESH_INTERVAL_MS
import com.matiasnl.hakiosk.camera.thumbnail.playingVideo
import com.matiasnl.hakiosk.camera.thumbnail.runLiveThumbnail
import com.matiasnl.hakiosk.camera.webrtc.RemoteVideoTrack
import com.matiasnl.hakiosk.camera.webrtc.TextureVideoRenderer
import com.matiasnl.hakiosk.data.dashboard.CameraTileOptions
import com.matiasnl.hakiosk.data.ha.camera.CameraLiveSource

/**
 * A dashboard camera tile's picture, per its [options]: snapshots at the tile's own interval, or live
 * video while [active] when [CameraTileOptions.thumbnailLive] is set.
 */
@Composable
fun CameraTileThumbnail(
    entityId: String,
    options: CameraTileOptions?,
    active: Boolean,
    camera: CameraModule,
    modifier: Modifier = Modifier,
) {
    val refreshIntervalMillis = thumbnailRefreshIntervalMillis(options)
    if (options?.thumbnailLive == true) {
        LiveCameraTileThumbnail(
            entityId = entityId,
            source = CameraLiveSource.of(entityId, options.thumbnailStream),
            active = active,
            camera = camera,
            refreshIntervalMillis = refreshIntervalMillis,
            modifier = modifier,
        )
    } else {
        CameraThumbnail(
            entityId = entityId,
            snapshots = camera.snapshots,
            modifier = modifier,
            active = active,
            refreshIntervalMillis = refreshIntervalMillis,
        )
    }
}

/**
 * Live video in a tile, in its own WebRTC session (no app-wide cap). The session runs only while
 * [active] and the lifecycle is at least STARTED, and is stopped (peer closed) as soon as either turns
 * false or this leaves composition. Snapshots show until the first frame is on screen, whenever live
 * isn't playing, and during the retry delay after a failure.
 */
@Composable
private fun LiveCameraTileThumbnail(
    entityId: String,
    source: CameraLiveSource,
    active: Boolean,
    camera: CameraModule,
    refreshIntervalMillis: Long,
    modifier: Modifier = Modifier,
) {
    val manager = remember(camera) { camera.newThumbnailSessionManager() }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val running = active && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(manager, source, running) {
        // Cancelled when any key changes or on dispose; runLiveThumbnail always stops the session then.
        if (running) runLiveThumbnail(manager, source)
    }

    val streamState by manager.state.collectAsState()
    val video = streamState.playingVideo(entityId)?.takeIf { running }
    // The track whose first frame has reached the screen; a new track starts hidden behind the snapshot.
    var renderedVideo by remember { mutableStateOf<RemoteVideoTrack?>(null) }
    val showingVideo = video != null && renderedVideo === video

    Box(modifier = modifier.clipToBounds()) {
        if (video != null) {
            LiveVideo(video, onFirstFrame = { renderedVideo = video }, modifier = Modifier.fillMaxSize())
        }
        // Drawn above the video (not hidden with alpha: a TextureView that isn't drawn gets no frames).
        if (!showingVideo) {
            CameraThumbnail(
                entityId = entityId,
                snapshots = camera.snapshots,
                modifier = Modifier.fillMaxSize(),
                active = active,
                refreshIntervalMillis = refreshIntervalMillis,
            )
        }
    }
}

/** Aspect-fill video in a TextureView, so it scrolls, pages and clips like any other content. */
@Composable
private fun LiveVideo(video: RemoteVideoTrack, onFirstFrame: () -> Unit, modifier: Modifier = Modifier) {
    key(video) {
        AndroidView(
            factory = { context ->
                TextureVideoRenderer(context).also { renderer ->
                    renderer.onFirstFrameRendered = onFirstFrame
                    video.bind(renderer)
                }
            },
            onRelease = { renderer -> video.unbind(renderer) },
            modifier = modifier,
        )
    }
}

/** The tile's snapshot interval in milliseconds: its own setting, never below the minimum, or the app default. */
fun thumbnailRefreshIntervalMillis(options: CameraTileOptions?): Long =
    options?.thumbnailRefreshSeconds
        ?.coerceAtLeast(CameraTileOptions.MIN_THUMBNAIL_REFRESH_SECONDS)
        ?.let { it * 1_000L }
        ?: THUMBNAIL_REFRESH_INTERVAL_MS
