package com.matiasnl.hakiosk.data.ha.fake

import com.matiasnl.hakiosk.data.ha.camera.HaCameraSource
import com.matiasnl.hakiosk.data.ha.camera.HaCameraStreamType
import com.matiasnl.hakiosk.data.ha.camera.HaIceCandidate
import com.matiasnl.hakiosk.data.ha.camera.HaIceServer
import com.matiasnl.hakiosk.data.ha.camera.HaWebRtcClientConfig
import com.matiasnl.hakiosk.data.ha.camera.HaWebRtcEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Camera source for previews and tests. Snapshots return [snapshotJpeg] (empty by default, so
 * decoders should treat it as "no image"); WebRTC sessions end with an error since there is no peer.
 */
class FakeHaCameraSource(
    private val snapshotJpeg: ByteArray = ByteArray(0),
    private val streamTypes: Set<HaCameraStreamType> = setOf(HaCameraStreamType.WEB_RTC),
    private val go2rtcStreams: List<String> = listOf("front", "front_sub"),
) : HaCameraSource {
    override suspend fun go2rtcStreams(entityId: String): Result<List<String>> = Result.success(go2rtcStreams)

    override fun go2rtcWebRtcSession(
        entityId: String,
        stream: String,
        offerSdp: String,
        localCandidates: Flow<HaIceCandidate>,
    ): Flow<HaWebRtcEvent> = flowOf(HaWebRtcEvent.Error("fake", "No WebRTC peer in fake camera source"))

    override suspend fun fetchSnapshot(entityId: String, width: Int?): Result<ByteArray> =
        Result.success(snapshotJpeg)

    override suspend fun streamTypes(entityId: String): Result<Set<HaCameraStreamType>> =
        Result.success(streamTypes)

    override suspend fun webRtcClientConfig(entityId: String): Result<HaWebRtcClientConfig> =
        Result.success(HaWebRtcClientConfig(listOf(HaIceServer(listOf("stun:stun.l.google.com:19302")))))

    override fun webRtcSession(entityId: String, offerSdp: String): Flow<HaWebRtcEvent> = flowOf(
        HaWebRtcEvent.Session("fake-session"),
        HaWebRtcEvent.Error("fake", "No WebRTC peer in fake camera source"),
    )

    override suspend fun sendWebRtcCandidate(
        entityId: String,
        sessionId: String,
        candidate: HaIceCandidate,
    ): Result<Unit> = Result.success(Unit)
}
