package com.matiasnl.hakiosk.camera

import com.matiasnl.hakiosk.data.ha.camera.HaCameraSource
import com.matiasnl.hakiosk.data.ha.camera.HaCameraStreamType
import com.matiasnl.hakiosk.data.ha.camera.HaIceCandidate
import com.matiasnl.hakiosk.data.ha.camera.HaIceServer
import com.matiasnl.hakiosk.data.ha.camera.HaWebRtcClientConfig
import com.matiasnl.hakiosk.data.ha.camera.HaWebRtcEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * One signaling session opened through [ScriptedHaCameraSource.webRtcSession] (HA, [stream] null) or
 * [ScriptedHaCameraSource.go2rtcWebRtcSession]; the test pushes the remote events.
 */
class ScriptedWebRtcSession(val entityId: String, val offerSdp: String, val stream: String? = null) {
    private val events = Channel<HaWebRtcEvent>(Channel.UNLIMITED)

    /** Local candidates the client sent over a go2rtc session, in order. */
    val localCandidates: MutableList<HaIceCandidate> = Collections.synchronizedList(mutableListOf())

    /** False once the collector cancelled or the flow completed. */
    var isActive = true
        internal set

    fun send(event: HaWebRtcEvent) {
        events.trySend(event)
    }

    fun end() {
        events.close()
    }

    internal suspend fun forward(emit: suspend (HaWebRtcEvent) -> Unit) {
        try {
            for (event in events) {
                emit(event)
                if (event is HaWebRtcEvent.Error) break
            }
        } finally {
            isActive = false
        }
    }
}

data class SentCandidate(val entityId: String, val sessionId: String, val candidate: HaIceCandidate)

/** Camera source whose answers the test controls step by step. */
class ScriptedHaCameraSource(
    var streamTypesResult: Result<Set<HaCameraStreamType>> = Result.success(setOf(HaCameraStreamType.WEB_RTC)),
    var clientConfigResult: Result<HaWebRtcClientConfig> =
        Result.success(HaWebRtcClientConfig(listOf(HaIceServer(listOf("stun:stun.example.org:3478"))))),
    var go2rtcStreamsResult: Result<List<String>> = Result.success(listOf("front", "front_sub")),
) : HaCameraSource {
    /** Consumed in order; when empty, [defaultSnapshot] is returned. */
    val snapshotResults = ArrayDeque<Result<ByteArray>>()
    var defaultSnapshot: Result<ByteArray> = Result.success(byteArrayOf(1, 2, 3))
    val snapshotRequests = mutableListOf<Pair<String, Int?>>()

    /** HA sessions and go2rtc sessions alike, in the order they were opened. */
    val sessions = mutableListOf<ScriptedWebRtcSession>()
    val sentCandidates = mutableListOf<SentCandidate>()

    override suspend fun fetchSnapshot(entityId: String, width: Int?): Result<ByteArray> {
        snapshotRequests += entityId to width
        return snapshotResults.removeFirstOrNull() ?: defaultSnapshot
    }

    override suspend fun streamTypes(entityId: String): Result<Set<HaCameraStreamType>> = streamTypesResult

    override suspend fun webRtcClientConfig(entityId: String): Result<HaWebRtcClientConfig> = clientConfigResult

    override fun webRtcSession(entityId: String, offerSdp: String): Flow<HaWebRtcEvent> = flow {
        val session = ScriptedWebRtcSession(entityId, offerSdp)
        sessions += session
        session.forward { emit(it) }
    }

    override suspend fun sendWebRtcCandidate(
        entityId: String,
        sessionId: String,
        candidate: HaIceCandidate,
    ): Result<Unit> {
        sentCandidates += SentCandidate(entityId, sessionId, candidate)
        return Result.success(Unit)
    }

    override suspend fun go2rtcStreams(entityId: String): Result<List<String>> = go2rtcStreamsResult

    override fun go2rtcWebRtcSession(
        entityId: String,
        stream: String,
        offerSdp: String,
        localCandidates: Flow<HaIceCandidate>,
    ): Flow<HaWebRtcEvent> = flow {
        val session = ScriptedWebRtcSession(entityId, offerSdp, stream)
        sessions += session
        coroutineScope {
            val sender = launch { localCandidates.collect { session.localCandidates += it } }
            try {
                session.forward { emit(it) }
            } finally {
                sender.cancel()
            }
        }
    }
}
