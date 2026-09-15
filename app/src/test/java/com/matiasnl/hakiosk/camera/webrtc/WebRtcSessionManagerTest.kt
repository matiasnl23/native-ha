package com.matiasnl.hakiosk.camera.webrtc

import com.matiasnl.hakiosk.camera.ScriptedHaCameraSource
import com.matiasnl.hakiosk.camera.SentCandidate
import com.matiasnl.hakiosk.data.ha.camera.CameraLiveSource
import com.matiasnl.hakiosk.data.ha.camera.HaIceCandidate
import com.matiasnl.hakiosk.data.ha.camera.HaWebRtcEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CAMERA = "camera.front_door"

@OptIn(ExperimentalCoroutinesApi::class)
class WebRtcSessionManagerTest {

    private val source = ScriptedHaCameraSource()
    private val factory = FakeWebRtcPeerFactory()

    private fun TestScope.manager(receiveAudio: Boolean = true) = WebRtcSessionManager(
        source = source,
        peerFactory = factory,
        scope = backgroundScope,
        mediaTimeoutMillis = 15_000,
        disconnectGraceMillis = 4_000,
        maxReconnects = 1,
        receiveAudio = receiveAudio,
    )

    private val go2rtc = CameraLiveSource.Go2rtc(CAMERA, "front_sub")

    private fun candidate(value: String) = HaIceCandidate("candidate:$value", "0", 0)

    /** Runs signaling up to the first video frame and returns the rendered track. */
    private fun TestScope.connect(manager: WebRtcSessionManager, entityId: String = CAMERA): FakeRemoteVideoTrack {
        manager.start(entityId)
        runCurrent()
        val session = source.sessions.last()
        session.send(HaWebRtcEvent.Session("session-${source.sessions.size}"))
        session.send(HaWebRtcEvent.Answer("answer-sdp"))
        runCurrent()
        val video = FakeRemoteVideoTrack()
        factory.lastPeer.emit(PeerEvent.VideoTrackAdded(video))
        factory.lastPeer.emit(PeerEvent.IceStateChanged(PeerIceState.CONNECTED))
        factory.lastPeer.emit(PeerEvent.FirstVideoFrame)
        runCurrent()
        return video
    }

    @Test
    fun `happy path goes from connecting to playing with the offer and answer exchanged`() = runTest {
        val manager = manager()

        manager.start(CAMERA)
        assertEquals(CameraStreamState.Connecting(CAMERA), manager.state.value)
        runCurrent()

        val session = source.sessions.single()
        assertEquals(CAMERA, session.entityId)
        assertEquals("offer-1", session.offerSdp)
        assertEquals("stun:stun.example.org:3478", factory.lastPeer.config.iceServers.single().urls.single())

        session.send(HaWebRtcEvent.Session("s1"))
        session.send(HaWebRtcEvent.Answer("answer-sdp"))
        runCurrent()
        assertEquals(listOf("offer", "answer:answer-sdp"), factory.lastPeer.log)
        assertEquals(CameraStreamState.Connecting(CAMERA), manager.state.value)

        val video = FakeRemoteVideoTrack()
        factory.lastPeer.emit(PeerEvent.VideoTrackAdded(video))
        factory.lastPeer.emit(PeerEvent.FirstVideoFrame)
        runCurrent()

        assertEquals(CameraStreamState.Playing(CAMERA, video, hasAudio = false, muted = true), manager.state.value)
    }

    @Test
    fun `local candidates are buffered until the session id arrives`() = runTest {
        val manager = manager()
        manager.start(CAMERA)
        runCurrent()
        val peer = factory.lastPeer
        val session = source.sessions.single()

        peer.emit(PeerEvent.LocalCandidate(candidate("a")))
        peer.emit(PeerEvent.LocalCandidate(candidate("b")))
        runCurrent()
        assertTrue(source.sentCandidates.isEmpty())

        session.send(HaWebRtcEvent.Session("s1"))
        runCurrent()
        assertEquals(
            listOf(SentCandidate(CAMERA, "s1", candidate("a")), SentCandidate(CAMERA, "s1", candidate("b"))),
            source.sentCandidates,
        )

        peer.emit(PeerEvent.LocalCandidate(candidate("c")))
        runCurrent()
        assertEquals(SentCandidate(CAMERA, "s1", candidate("c")), source.sentCandidates.last())
    }

    @Test
    fun `remote candidates are buffered until the remote description is set`() = runTest {
        val manager = manager()
        manager.start(CAMERA)
        runCurrent()
        val peer = factory.lastPeer
        val session = source.sessions.single()

        session.send(HaWebRtcEvent.Session("s1"))
        session.send(HaWebRtcEvent.RemoteCandidate(candidate("r1")))
        runCurrent()
        assertEquals(listOf("offer"), peer.log)

        session.send(HaWebRtcEvent.Answer("answer-sdp"))
        session.send(HaWebRtcEvent.RemoteCandidate(candidate("r2")))
        runCurrent()
        assertEquals(
            listOf("offer", "answer:answer-sdp", "candidate:candidate:r1", "candidate:candidate:r2"),
            peer.log,
        )
    }

    @Test
    fun `error event fails the session and releases the peer`() = runTest {
        val manager = manager()
        manager.start(CAMERA)
        runCurrent()

        source.sessions.single().send(HaWebRtcEvent.Session("s1"))
        source.sessions.single().send(HaWebRtcEvent.Error("webrtc_offer_failed", "Camera has no WebRTC"))
        runCurrent()

        assertEquals(
            CameraStreamState.Failed(CAMERA, CameraStreamError.Signaling("webrtc_offer_failed", "Camera has no WebRTC")),
            manager.state.value,
        )
        assertTrue(factory.lastPeer.isClosed)
        assertEquals(1, factory.peers.size) // Signaling errors are not retried.
    }

    @Test
    fun `missing ICE config fails with a setup error without creating a peer`() = runTest {
        source.clientConfigResult = Result.failure(IllegalStateException("not connected"))
        val manager = manager()

        manager.start(CAMERA)
        runCurrent()

        val state = manager.state.value as CameraStreamState.Failed
        assertTrue(state.error is CameraStreamError.Setup)
        assertTrue(factory.peers.isEmpty())
    }

    @Test
    fun `no video within the timeout fails the session`() = runTest {
        val manager = manager()
        manager.start(CAMERA)
        runCurrent()
        source.sessions.single().send(HaWebRtcEvent.Session("s1"))
        source.sessions.single().send(HaWebRtcEvent.Answer("answer-sdp"))

        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(CameraStreamState.Connecting(CAMERA), manager.state.value)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(CameraStreamState.Failed(CAMERA, CameraStreamError.Timeout(15_000)), manager.state.value)
        assertTrue(factory.lastPeer.isClosed)
        assertFalse(source.sessions.single().isActive)
    }

    @Test
    fun `playing session is not failed by the media timeout`() = runTest {
        val manager = manager()
        val video = connect(manager)

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(CameraStreamState.Playing(CAMERA, video, hasAudio = false, muted = true), manager.state.value)
    }

    @Test
    fun `starting another camera closes the previous session before creating the new peer`() = runTest {
        val manager = manager()
        connect(manager, "camera.a")
        val firstPeer = factory.lastPeer
        val firstSession = source.sessions.last()

        manager.start("camera.b")
        assertEquals(CameraStreamState.Connecting("camera.b"), manager.state.value)
        runCurrent()

        assertTrue(firstPeer.isClosed)
        assertFalse(firstSession.isActive)
        assertEquals(listOf(0, 0), factory.openPeersAtCreation)
        assertEquals("camera.b", source.sessions.last().entityId)
        assertEquals(1, factory.peers.count { !it.isClosed })
    }

    @Test
    fun `stop releases the peer and the signaling session`() = runTest {
        val manager = manager()
        connect(manager)

        manager.stop()
        assertEquals(CameraStreamState.Idle, manager.state.value)
        runCurrent()

        assertTrue(factory.lastPeer.isClosed)
        assertFalse(source.sessions.single().isActive)
        assertEquals(CameraStreamState.Idle, manager.state.value)
    }

    @Test
    fun `stop for a different camera is ignored`() = runTest {
        val manager = manager()
        val video = connect(manager, "camera.b")

        manager.stop("camera.a")
        runCurrent()

        assertFalse(factory.lastPeer.isClosed)
        assertEquals(CameraStreamState.Playing("camera.b", video, hasAudio = false, muted = true), manager.state.value)
    }

    @Test
    fun `ICE failure reconnects once and then fails`() = runTest {
        val manager = manager()
        manager.start(CAMERA)
        runCurrent()

        factory.lastPeer.emit(PeerEvent.IceStateChanged(PeerIceState.FAILED))
        runCurrent()
        assertEquals(2, factory.peers.size)
        assertTrue(factory.peers[0].isClosed)
        assertEquals(CameraStreamState.Connecting(CAMERA), manager.state.value)

        factory.lastPeer.emit(PeerEvent.IceStateChanged(PeerIceState.FAILED))
        runCurrent()
        assertEquals(2, factory.peers.size)
        assertEquals(CameraStreamState.Failed(CAMERA, CameraStreamError.ConnectionLost), manager.state.value)
        assertTrue(factory.peers.all { it.isClosed })
    }

    @Test
    fun `short ICE disconnection recovers without reconnecting`() = runTest {
        val manager = manager()
        val video = connect(manager)

        factory.lastPeer.emit(PeerEvent.IceStateChanged(PeerIceState.DISCONNECTED))
        runCurrent()
        advanceTimeBy(3_000)
        factory.lastPeer.emit(PeerEvent.IceStateChanged(PeerIceState.CONNECTED))
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(1, factory.peers.size)
        assertEquals(CameraStreamState.Playing(CAMERA, video, hasAudio = false, muted = true), manager.state.value)
    }

    @Test
    fun `long ICE disconnection while playing reconnects`() = runTest {
        val manager = manager()
        connect(manager)

        factory.lastPeer.emit(PeerEvent.IceStateChanged(PeerIceState.DISCONNECTED))
        runCurrent()
        advanceTimeBy(4_001)
        runCurrent()

        assertEquals(2, factory.peers.size)
        assertTrue(factory.peers[0].isClosed)
        assertEquals(CameraStreamState.Connecting(CAMERA), manager.state.value)
    }

    @Test
    fun `audio starts muted and can be unmuted`() = runTest {
        val manager = manager()
        val video = connect(manager)

        factory.lastPeer.emit(PeerEvent.AudioTrackAdded)
        runCurrent()
        assertEquals(CameraStreamState.Playing(CAMERA, video, hasAudio = true, muted = true), manager.state.value)
        assertEquals(false, factory.lastPeer.audioEnabled)

        manager.setMuted(false)
        runCurrent()
        assertEquals(true, factory.lastPeer.audioEnabled)
        assertEquals(CameraStreamState.Playing(CAMERA, video, hasAudio = true, muted = false), manager.state.value)
    }

    @Test
    fun `peer creation failure is reported as a setup error`() = runTest {
        factory.failWith = IllegalStateException("no EGL")
        val manager = manager()

        manager.start(CAMERA)
        runCurrent()

        assertEquals(CameraStreamState.Failed(CAMERA, CameraStreamError.Setup("Peer connection: no EGL")), manager.state.value)
    }

    @Test
    fun `restarting the same camera keeps a single open peer`() = runTest {
        val manager = manager()
        connect(manager)
        val first = factory.lastPeer

        manager.start(CAMERA)
        runCurrent()

        assertTrue(first.isClosed)
        assertEquals(2, factory.peers.size)
        assertEquals(CameraStreamState.Connecting(CAMERA), manager.state.value)
        assertEquals(1, factory.peers.count { !it.isClosed })
    }

    // --- go2rtc through the Frigate proxy ---

    @Test
    fun `go2rtc source opens a go2rtc session with the offer and never uses the HA signaling`() = runTest {
        val manager = manager()

        manager.start(go2rtc)
        assertEquals(CameraStreamState.Connecting(CAMERA), manager.state.value)
        runCurrent()

        val session = source.sessions.single()
        assertEquals(CAMERA, session.entityId)
        assertEquals("front_sub", session.stream)
        assertEquals("offer-1", session.offerSdp)
        assertEquals("stun:stun.example.org:3478", factory.lastPeer.config.iceServers.single().urls.single())

        session.send(HaWebRtcEvent.Answer("answer-sdp"))
        session.send(HaWebRtcEvent.RemoteCandidate(candidate("r1")))
        runCurrent()
        assertEquals(listOf("offer", "answer:answer-sdp", "candidate:candidate:r1"), factory.lastPeer.log)

        val video = FakeRemoteVideoTrack()
        factory.lastPeer.emit(PeerEvent.VideoTrackAdded(video))
        factory.lastPeer.emit(PeerEvent.FirstVideoFrame)
        runCurrent()
        assertEquals(CameraStreamState.Playing(CAMERA, video, hasAudio = false, muted = true), manager.state.value)
        assertTrue(source.sentCandidates.isEmpty())
    }

    @Test
    fun `go2rtc local candidates are forwarded before and after the answer`() = runTest {
        val manager = manager()
        manager.start(go2rtc)
        runCurrent()
        val peer = factory.lastPeer
        val session = source.sessions.single()

        peer.emit(PeerEvent.LocalCandidate(candidate("a")))
        peer.emit(PeerEvent.LocalCandidate(candidate("b")))
        runCurrent()
        assertEquals(listOf(candidate("a"), candidate("b")), session.localCandidates.toList())

        session.send(HaWebRtcEvent.Answer("answer-sdp"))
        peer.emit(PeerEvent.LocalCandidate(candidate("c")))
        runCurrent()
        assertEquals(listOf(candidate("a"), candidate("b"), candidate("c")), session.localCandidates.toList())
        assertTrue(source.sentCandidates.isEmpty())
    }

    @Test
    fun `go2rtc blank remote candidate is ignored`() = runTest {
        val manager = manager()
        manager.start(go2rtc)
        runCurrent()
        val session = source.sessions.single()

        session.send(HaWebRtcEvent.Answer("answer-sdp"))
        session.send(HaWebRtcEvent.RemoteCandidate(HaIceCandidate("", "0", 0)))
        runCurrent()

        assertEquals(listOf("offer", "answer:answer-sdp"), factory.lastPeer.log)
    }

    @Test
    fun `go2rtc error fails the session and releases the peer and the socket`() = runTest {
        val manager = manager()
        manager.start(go2rtc)
        runCurrent()

        source.sessions.single().send(HaWebRtcEvent.Error("stream_not_found", "streams: unknown src"))
        runCurrent()

        assertEquals(
            CameraStreamState.Failed(CAMERA, CameraStreamError.Signaling("stream_not_found", "streams: unknown src")),
            manager.state.value,
        )
        assertTrue(factory.lastPeer.isClosed)
        assertFalse(source.sessions.single().isActive)
        assertEquals(1, factory.peers.size)
    }

    @Test
    fun `go2rtc socket closing before the answer fails the session`() = runTest {
        val manager = manager()
        manager.start(go2rtc)
        runCurrent()

        source.sessions.single().end()
        runCurrent()

        val state = manager.state.value as CameraStreamState.Failed
        assertTrue(state.error is CameraStreamError.Signaling)
        assertTrue(factory.lastPeer.isClosed)
    }

    @Test
    fun `go2rtc without an ICE config still connects with no ICE servers`() = runTest {
        source.clientConfigResult = Result.failure(IllegalStateException("not connected"))
        val manager = manager()

        manager.start(go2rtc)
        runCurrent()

        assertTrue(factory.lastPeer.config.iceServers.isEmpty())
        assertEquals("front_sub", source.sessions.single().stream)
        assertEquals(CameraStreamState.Connecting(CAMERA), manager.state.value)
    }

    @Test
    fun `go2rtc no video within the timeout fails the session`() = runTest {
        val manager = manager()
        manager.start(go2rtc)
        runCurrent()
        source.sessions.single().send(HaWebRtcEvent.Answer("answer-sdp"))

        advanceTimeBy(15_001)
        runCurrent()

        assertEquals(CameraStreamState.Failed(CAMERA, CameraStreamError.Timeout(15_000)), manager.state.value)
        assertTrue(factory.lastPeer.isClosed)
        assertFalse(source.sessions.single().isActive)
    }

    @Test
    fun `go2rtc ICE failure reconnects with a new go2rtc session`() = runTest {
        val manager = manager()
        manager.start(go2rtc)
        runCurrent()
        source.sessions.single().send(HaWebRtcEvent.Answer("answer-sdp"))
        runCurrent()

        factory.lastPeer.emit(PeerEvent.IceStateChanged(PeerIceState.FAILED))
        runCurrent()

        assertEquals(2, factory.peers.size)
        assertTrue(factory.peers[0].isClosed)
        assertFalse(source.sessions[0].isActive)
        assertEquals(listOf("front_sub", "front_sub"), source.sessions.map { it.stream })
        assertEquals("offer-2", source.sessions[1].offerSdp)
        assertEquals(CameraStreamState.Connecting(CAMERA), manager.state.value)
    }

    @Test
    fun `go2rtc stop releases the peer and closes the socket`() = runTest {
        val manager = manager()
        manager.start(go2rtc)
        runCurrent()
        source.sessions.single().send(HaWebRtcEvent.Answer("answer-sdp"))
        runCurrent()

        manager.stop(CAMERA)
        assertEquals(CameraStreamState.Idle, manager.state.value)
        runCurrent()

        assertTrue(factory.lastPeer.isClosed)
        assertFalse(source.sessions.single().isActive)
    }

    @Test
    fun `switching from HA stream to go2rtc stream of the same camera keeps a single open peer`() = runTest {
        val manager = manager()
        connect(manager)
        val first = factory.lastPeer

        manager.start(go2rtc)
        runCurrent()

        assertTrue(first.isClosed)
        assertEquals(listOf(null, "front_sub"), source.sessions.map { it.stream })
        assertEquals(1, factory.peers.count { !it.isClosed })
        assertEquals(listOf(0, 0), factory.openPeersAtCreation)
    }

    @Test
    fun `video-only manager creates peers without audio`() = runTest {
        val manager = manager(receiveAudio = false)

        manager.start(go2rtc)
        runCurrent()

        assertFalse(factory.lastPeer.receiveAudio)
    }
}
