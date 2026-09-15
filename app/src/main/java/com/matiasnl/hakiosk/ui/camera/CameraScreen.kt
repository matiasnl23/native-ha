package com.matiasnl.hakiosk.ui.camera

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.camera.thumbnail.CameraSnapshot
import com.matiasnl.hakiosk.camera.thumbnail.CameraSnapshotRepository
import com.matiasnl.hakiosk.camera.thumbnail.FOCUS_SNAPSHOT_REFRESH_INTERVAL_MS
import com.matiasnl.hakiosk.camera.webrtc.CameraStreamError
import com.matiasnl.hakiosk.camera.webrtc.FitSurfaceViewRenderer
import com.matiasnl.hakiosk.camera.webrtc.RemoteVideoTrack
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

/** Snapshots for the fallback are requested at most this wide; enough for a tablet, bounded RAM for 4K cameras. */
private const val MAX_FALLBACK_SNAPSHOT_WIDTH_PX = 1280

@Composable
fun CameraScreen(
    entityId: String,
    viewModel: CameraViewModel,
    snapshots: CameraSnapshotRepository<ImageBitmap>,
    onClose: () -> Unit,
    /** A touch anywhere on the screen; used to cancel a remote-command auto-close (see RemoteCommandNavigator). */
    onUserActivity: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Live only while this destination is STARTED: leaving it or backgrounding the app releases WebRTC.
    LifecycleStartEffect(viewModel) {
        viewModel.onStart()
        onStopOrDispose { viewModel.onStop() }
    }
    KeepScreenOn()
    ImmersiveSystemBars()

    var size by remember { mutableStateOf(IntSize.Zero) }
    val cachedThumbnail = remember(entityId) { CameraSnapshot(snapshots.cachedImage(entityId)) }
    val snapshot: CameraSnapshot<ImageBitmap> = when (uiState.mode) {
        is CameraScreenMode.SnapshotFallback -> {
            if (size.width > 0 && size.height > 0) {
                val width = minOf(size.width, MAX_FALLBACK_SNAPSHOT_WIDTH_PX)
                val height = size.height * width / size.width
                val flow = remember(entityId, width, height) {
                    snapshots.snapshots(entityId, width, height, FOCUS_SNAPSHOT_REFRESH_INTERVAL_MS, useCache = false)
                }
                flow.collectAsStateWithLifecycle(initialValue = null).value
            } else {
                null
            }
        }
        else -> null
    } ?: cachedThumbnail

    val latestOnUserActivity by rememberUpdatedState(onUserActivity)
    CameraContent(
        uiState = uiState,
        snapshot = snapshot,
        onClose = onClose,
        onRetry = viewModel::retry,
        onMutedChange = viewModel::setMuted,
        modifier = Modifier
            .onSizeChanged { size = it }
            // Initial pass, never consumed: doesn't interfere with the close/mute buttons or a swipe.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        latestOnUserActivity()
                    }
                }
            },
        videoContent = { video, modifier -> WebRtcVideo(video, modifier) },
    )
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * Hides the status and navigation bars while the camera is visible, so the video uses the whole
 * screen. An edge swipe reveals them temporarily; they come back when the screen stops or closes.
 */
@Composable
private fun ImmersiveSystemBars() {
    val view = LocalView.current
    LifecycleStartEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onStopOrDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Aspect-fit video: wrapContentSize gives the renderer AT_MOST constraints so it sizes itself to the frame. */
@Composable
private fun WebRtcVideo(video: RemoteVideoTrack, modifier: Modifier = Modifier) {
    key(video) {
        AndroidView(
            factory = { context -> FitSurfaceViewRenderer(context).also { video.bind(it) } },
            onRelease = { renderer -> video.unbind(renderer) },
            modifier = modifier.wrapContentSize(),
        )
    }
}

@Composable
private fun CameraContent(
    uiState: CameraUiState,
    snapshot: CameraSnapshot<ImageBitmap>?,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onMutedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    videoContent: @Composable (RemoteVideoTrack, Modifier) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        when (val mode = uiState.mode) {
            CameraScreenMode.Loading, CameraScreenMode.Connecting -> {
                snapshot?.image?.let { SnapshotImage(it, Modifier.fillMaxSize().alpha(0.4f)) }
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(stringResource(R.string.camera_connecting), color = Color.White)
                }
            }
            is CameraScreenMode.Playing -> videoContent(mode.video, Modifier.fillMaxSize())
            is CameraScreenMode.SnapshotFallback -> {
                snapshot?.image?.let { SnapshotImage(it, Modifier.fillMaxSize()) }
                FallbackPanel(
                    reason = mode.reason,
                    snapshot = snapshot,
                    onRetry = onRetry,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(16.dp),
                )
            }
        }

        // Floating controls instead of a bar, so the picture keeps the whole screen.
        val closeLabel = stringResource(R.string.camera_close)
        IconButton(
            onClick = onClose,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.35f),
                contentColor = Color.White,
            ),
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(12.dp)
                .size(40.dp)
                .semantics { contentDescription = closeLabel },
        ) {
            Text("✕", style = MaterialTheme.typography.titleMedium)
        }
        val playing = uiState.mode as? CameraScreenMode.Playing
        if (playing != null && playing.hasAudio) {
            FilledTonalButton(
                onClick = { onMutedChange(!playing.muted) },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.35f),
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(12.dp),
            ) {
                Text(stringResource(if (playing.muted) R.string.camera_unmute else R.string.camera_mute))
            }
        }
    }
}

@Composable
private fun SnapshotImage(image: ImageBitmap, modifier: Modifier = Modifier) {
    Image(
        bitmap = image,
        contentDescription = stringResource(R.string.camera_thumbnail_description),
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

@Composable
private fun FallbackPanel(
    reason: FallbackReason,
    snapshot: CameraSnapshot<ImageBitmap>?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.camera_no_live_video), color = Color.White, style = MaterialTheme.typography.titleSmall)
        val detail = when (reason) {
            FallbackReason.NoWebRtc -> stringResource(R.string.camera_no_webrtc)
            is FallbackReason.LiveFailed -> streamErrorText(reason.error)
        }
        Text(detail, color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        if (snapshot?.errorMessage != null && snapshot.image == null) {
            Text(
                stringResource(R.string.camera_snapshot_error, snapshot.errorMessage),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        if (reason is FallbackReason.LiveFailed) {
            Button(onClick = onRetry) { Text(stringResource(R.string.camera_retry)) }
        }
    }
}

@Composable
private fun streamErrorText(error: CameraStreamError): String = when (error) {
    is CameraStreamError.Setup -> stringResource(R.string.camera_error_setup, error.message)
    is CameraStreamError.Signaling -> stringResource(R.string.camera_error_signaling, error.message)
    is CameraStreamError.Timeout -> stringResource(R.string.camera_error_timeout, (error.timeoutMillis / 1000).toInt())
    CameraStreamError.ConnectionLost -> stringResource(R.string.camera_error_connection_lost)
}

@Preview(widthDp = 800, heightDp = 480)
@Composable
private fun CameraConnectingPreview() {
    HAKioskTheme {
        CameraContent(
            uiState = CameraUiState("Puerta de entrada", CameraScreenMode.Connecting),
            snapshot = null,
            onClose = {},
            onRetry = {},
            onMutedChange = {},
            videoContent = { _, _ -> },
        )
    }
}

@Preview(widthDp = 800, heightDp = 480)
@Composable
private fun CameraFailedPreview() {
    HAKioskTheme {
        CameraContent(
            uiState = CameraUiState(
                "Puerta de entrada",
                CameraScreenMode.SnapshotFallback(FallbackReason.LiveFailed(CameraStreamError.Timeout(15_000))),
            ),
            snapshot = CameraSnapshot(ImageBitmap(320, 180)),
            onClose = {},
            onRetry = {},
            onMutedChange = {},
            videoContent = { _, _ -> },
        )
    }
}

@Preview(widthDp = 800, heightDp = 480)
@Composable
private fun CameraNoWebRtcPreview() {
    HAKioskTheme {
        CameraContent(
            uiState = CameraUiState("Patio", CameraScreenMode.SnapshotFallback(FallbackReason.NoWebRtc)),
            snapshot = CameraSnapshot(null, "Cámara apagada"),
            onClose = {},
            onRetry = {},
            onMutedChange = {},
            videoContent = { _, _ -> },
        )
    }
}
