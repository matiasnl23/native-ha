package com.matiasnl.hakiosk.camera.thumbnail

import com.matiasnl.hakiosk.camera.ScriptedHaCameraSource
import com.matiasnl.hakiosk.camera.webrtc.CameraStreamState
import com.matiasnl.hakiosk.camera.webrtc.FakeRemoteVideoTrack
import com.matiasnl.hakiosk.camera.webrtc.FakeWebRtcPeerFactory
import com.matiasnl.hakiosk.camera.webrtc.PeerEvent
import com.matiasnl.hakiosk.camera.webrtc.WebRtcSessionManager
import com.matiasnl.hakiosk.data.ha.camera.CameraLiveSource
import com.matiasnl.hakiosk.data.ha.camera.HaWebRtcEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CAMERA = "camera.garden"

@OptIn(ExperimentalCoroutinesApi::class)
class LiveThumbnailTest {

    private val source = ScriptedHaCameraSource()
    private val factory = FakeWebRtcPeerFactory()
    private val go2rtc = CameraLiveSource.Go2rtc(CAMERA, "garden_sub")

    private fun TestScope.manager() = WebRtcSessionManager(
        source = source,
        peerFactory = factory,
        scope = backgroundScope,
        mediaTimeoutMillis = 15_000,
        maxReconnects = 0,
        receiveAudio = false,
    )

    private fun TestScope.play(): FakeRemoteVideoTrack {
        source.sessions.last().send(HaWebRtcEvent.Answer("answer"))
        runCurrent()
        val video = FakeRemoteVideoTrack()
        factory.lastPeer.emit(PeerEvent.VideoTrackAdded(video))
        factory.lastPeer.emit(PeerEvent.FirstVideoFrame)
        runCurrent()
        return video
    }

    @Test
    fun `plays the tile's stream with a video-only peer`() = runTest {
        val manager = manager()
        launch { runLiveThumbnail(manager, go2rtc, retryDelayMillis = 30_000) }
        runCurrent()

        assertEquals("garden_sub", source.sessions.single().stream)
        assertFalse(factory.lastPeer.receiveAudio)
        assertNull(manager.state.value.playingVideo(CAMERA))

        val video = play()
        assertSame(video, manager.state.value.playingVideo(CAMERA))

        coroutineContext.cancelChildren()
    }

    @Test
    fun `cancelling releases the peer, the signaling and the state`() = runTest {
        val manager = manager()
        val job = launch { runLiveThumbnail(manager, go2rtc) }
        runCurrent()
        play()

        job.cancel()
        runCurrent()

        assertEquals(CameraStreamState.Idle, manager.state.value)
        assertTrue(factory.peers.all { it.isClosed })
        assertFalse(source.sessions.single().isActive)
        assertNull(manager.state.value.playingVideo(CAMERA))
    }

    @Test
    fun `cancelling while connecting releases the peer`() = runTest {
        val manager = manager()
        val job = launch { runLiveThumbnail(manager, go2rtc) }
        runCurrent()

        job.cancel()
        runCurrent()

        assertTrue(factory.peers.single().isClosed)
        assertEquals(CameraStreamState.Idle, manager.state.value)
    }

    @Test
    fun `failure falls back to snapshots and retries live after the delay`() = runTest {
        val manager = manager()
        val job = launch { runLiveThumbnail(manager, go2rtc, retryDelayMillis = 30_000) }
        runCurrent()

        source.sessions.single().send(HaWebRtcEvent.Error("stream_not_found", "unknown src"))
        runCurrent()
        assertTrue(manager.state.value is CameraStreamState.Failed)
        assertTrue(factory.lastPeer.isClosed)

        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(1, source.sessions.size)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, source.sessions.size)
        assertEquals(CameraStreamState.Connecting(CAMERA), manager.state.value)
        assertEquals(1, factory.peers.count { !it.isClosed })

        job.cancel()
        runCurrent()
        assertTrue(factory.peers.all { it.isClosed })
    }

    @Test
    fun `cancelling during the retry delay never starts again`() = runTest {
        val manager = manager()
        val job = launch { runLiveThumbnail(manager, go2rtc, retryDelayMillis = 30_000) }
        runCurrent()
        source.sessions.single().send(HaWebRtcEvent.Error("x", "down"))
        runCurrent()

        job.cancel()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, source.sessions.size)
        assertEquals(CameraStreamState.Idle, manager.state.value)
    }

    @Test
    fun `media timeout counts as a failure and is retried`() = runTest {
        val manager = manager()
        val job = launch { runLiveThumbnail(manager, go2rtc, retryDelayMillis = 30_000) }
        runCurrent()
        source.sessions.single().send(HaWebRtcEvent.Answer("answer"))

        advanceTimeBy(15_001)
        runCurrent()
        assertTrue(manager.state.value is CameraStreamState.Failed)

        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(2, source.sessions.size)

        job.cancel()
    }

    @Test
    fun `thumbnails of different cameras run independent sessions`() = runTest {
        val first = manager()
        val second = manager()
        launch { runLiveThumbnail(first, go2rtc) }
        val other = launch { runLiveThumbnail(second, CameraLiveSource.HomeAssistant("camera.door")) }
        runCurrent()

        assertEquals(listOf("garden_sub", null), source.sessions.map { it.stream })
        assertEquals(2, factory.peers.count { !it.isClosed })

        other.cancel()
        runCurrent()
        assertEquals(1, factory.peers.count { !it.isClosed })
        assertEquals(CameraStreamState.Connecting(CAMERA), first.state.value)

        coroutineContext.cancelChildren()
    }

    @Test
    fun `playing state of another camera is not rendered`() {
        val state = CameraStreamState.Playing("camera.other", FakeRemoteVideoTrack(), hasAudio = false, muted = true)
        assertNull(state.playingVideo(CAMERA))
    }
}
