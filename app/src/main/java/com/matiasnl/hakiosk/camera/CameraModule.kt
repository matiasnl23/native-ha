package com.matiasnl.hakiosk.camera

import android.content.Context
import android.util.Log
import com.matiasnl.hakiosk.camera.webrtc.LibWebRtcPeerFactory
import com.matiasnl.hakiosk.camera.webrtc.WebRtcSessionManager
import com.matiasnl.hakiosk.data.ha.camera.HaCameraSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private const val TAG = "HaKioskCamera"

/** Wires camera streaming. One instance per process, so there is a single WebRTC session app-wide. */
class CameraModule(context: Context, cameraSource: HaCameraSource) {
    private val appContext = context.applicationContext

    /** Single-threaded: every libwebrtc signaling/dispose call is serialized, off the main thread. */
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    val webRtcSessionManager: WebRtcSessionManager by lazy {
        WebRtcSessionManager(
            source = cameraSource,
            peerFactory = LibWebRtcPeerFactory(appContext),
            scope = sessionScope,
            log = { Log.w(TAG, it) },
        )
    }
}
