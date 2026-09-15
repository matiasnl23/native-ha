package com.matiasnl.hakiosk.camera

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import com.matiasnl.hakiosk.camera.thumbnail.BitmapSnapshotDecoder
import com.matiasnl.hakiosk.camera.thumbnail.CameraSnapshotRepository
import com.matiasnl.hakiosk.camera.thumbnail.byteCount
import com.matiasnl.hakiosk.camera.webrtc.LibWebRtcPeerFactory
import com.matiasnl.hakiosk.camera.webrtc.WebRtcSessionManager
import com.matiasnl.hakiosk.data.ha.camera.HaCameraSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private const val TAG = "HaKioskCamera"

/** Upper bound of the thumbnail memory cache; the real budget is also capped at 1/16 of the app heap class. */
private const val MAX_THUMBNAIL_CACHE_BYTES = 8L * 1024 * 1024

/**
 * Wires camera streaming. One instance per process: a single app-wide focus session
 * ([webRtcSessionManager]) plus one independent session per live thumbnail ([newThumbnailSessionManager]).
 */
class CameraModule(context: Context, val cameraSource: HaCameraSource) {
    private val appContext = context.applicationContext

    /** Single-threaded: every libwebrtc signaling/dispose call is serialized, off the main thread. */
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    /** Shared by every session, so all peers and renderers use one reference-counted root EGL context. */
    private val peerFactory by lazy { LibWebRtcPeerFactory(appContext) }

    /** The focus view's session; starting it closes whatever it was playing before. */
    val webRtcSessionManager: WebRtcSessionManager by lazy {
        WebRtcSessionManager(
            source = cameraSource,
            peerFactory = peerFactory,
            scope = sessionScope,
            log = { Log.w(TAG, it) },
        )
    }

    /**
     * A new, independent session manager for one live thumbnail: at most one session of its own and
     * video only (thumbnails are silent). Not capped by design; the caller must stop it as soon as the
     * thumbnail stops being active. Holds no native resources while stopped.
     */
    fun newThumbnailSessionManager(): WebRtcSessionManager = WebRtcSessionManager(
        source = cameraSource,
        peerFactory = peerFactory,
        scope = sessionScope,
        receiveAudio = false,
        log = { Log.w(TAG, "Thumbnail: $it") },
    )

    val snapshots: CameraSnapshotRepository<ImageBitmap> by lazy {
        val memoryClassBytes = appContext.getSystemService(ActivityManager::class.java).memoryClass * 1024L * 1024L
        CameraSnapshotRepository(
            source = cameraSource,
            decoder = BitmapSnapshotDecoder(),
            // At most 2 decodes at once: bounds peak memory when a whole grid refreshes together.
            decodeDispatcher = Dispatchers.Default.limitedParallelism(2),
            maxCacheBytes = minOf(MAX_THUMBNAIL_CACHE_BYTES, memoryClassBytes / 16),
            sizeOf = { it.byteCount() },
            clock = SystemClock::elapsedRealtime,
        )
    }
}
