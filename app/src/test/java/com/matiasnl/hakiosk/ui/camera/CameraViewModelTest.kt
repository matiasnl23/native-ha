package com.matiasnl.hakiosk.ui.camera

import com.matiasnl.hakiosk.camera.ScriptedHaCameraSource
import com.matiasnl.hakiosk.camera.webrtc.CameraStreamError
import com.matiasnl.hakiosk.camera.webrtc.CameraStreamState
import com.matiasnl.hakiosk.camera.webrtc.FakeRemoteVideoTrack
import com.matiasnl.hakiosk.camera.webrtc.FakeWebRtcPeerFactory
import com.matiasnl.hakiosk.camera.webrtc.PeerEvent
import com.matiasnl.hakiosk.camera.webrtc.WebRtcSessionManager
import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.camera.HaCameraStreamType
import com.matiasnl.hakiosk.data.ha.camera.HaWebRtcEvent
import com.matiasnl.hakiosk.data.ha.fake.FakeHaRepository
import com.matiasnl.hakiosk.ui.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private const val CAMERA = "camera.front_door"

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val source = ScriptedHaCameraSource()
    private val peers = FakeWebRtcPeerFactory()
    private val repository = FakeHaRepository(
        initialEntities = listOf(
            HaEntity(CAMERA, "idle", JsonObject(mapOf("friendly_name" to JsonPrimitive("Front door"))), "2026-01-01T00:00:00+00:00"),
        ),
    )

    private fun TestScope.viewModel(label: String? = null): Pair<CameraViewModel, WebRtcSessionManager> {
        val manager = WebRtcSessionManager(source, peers, backgroundScope)
        val viewModel = CameraViewModel(CAMERA, label, repository, source, manager)
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        return viewModel to manager
    }

    @Test
    fun `title prefers the tile label, then the friendly name`() = runTest {
        assertEquals("Entrada", viewModel(label = "Entrada").first.uiState.value.title)
        assertEquals("Front door", viewModel().first.uiState.value.title)
    }

    @Test
    fun `camera without WebRTC falls back to snapshots and never opens a session`() = runTest {
        source.streamTypesResult = Result.success(setOf(HaCameraStreamType.HLS))
        val (viewModel, _) = viewModel()

        viewModel.onStart()
        runCurrent()

        assertEquals(CameraScreenMode.SnapshotFallback(FallbackReason.NoWebRtc), viewModel.uiState.value.mode)
        assertTrue(peers.peers.isEmpty())
        assertTrue(source.sessions.isEmpty())
    }

    @Test
    fun `WebRTC camera connects on start and plays`() = runTest {
        val (viewModel, _) = viewModel()

        viewModel.onStart()
        runCurrent()
        assertEquals(CameraScreenMode.Connecting, viewModel.uiState.value.mode)

        source.sessions.single().send(HaWebRtcEvent.Session("s1"))
        source.sessions.single().send(HaWebRtcEvent.Answer("answer"))
        val video = FakeRemoteVideoTrack()
        peers.lastPeer.emit(PeerEvent.VideoTrackAdded(video))
        peers.lastPeer.emit(PeerEvent.FirstVideoFrame)
        runCurrent()

        assertEquals(CameraScreenMode.Playing(video, hasAudio = false, muted = true), viewModel.uiState.value.mode)
    }

    @Test
    fun `failed WebRTC shows the snapshot fallback and retry reconnects`() = runTest {
        val (viewModel, _) = viewModel()
        viewModel.onStart()
        runCurrent()

        source.sessions.single().send(HaWebRtcEvent.Error("webrtc_offer_failed", "No go2rtc"))
        runCurrent()
        assertEquals(
            CameraScreenMode.SnapshotFallback(
                FallbackReason.LiveFailed(CameraStreamError.Signaling("webrtc_offer_failed", "No go2rtc")),
            ),
            viewModel.uiState.value.mode,
        )

        viewModel.retry()
        runCurrent()
        assertEquals(CameraScreenMode.Connecting, viewModel.uiState.value.mode)
        assertEquals(2, source.sessions.size)
    }

    @Test
    fun `stop releases the session and start resumes it`() = runTest {
        val (viewModel, manager) = viewModel()
        viewModel.onStart()
        runCurrent()

        viewModel.onStop()
        runCurrent()
        assertEquals(CameraStreamState.Idle, manager.state.value)
        assertTrue(peers.peers.single().isClosed)

        viewModel.onStart()
        runCurrent()
        assertEquals(2, peers.peers.size)
        assertEquals(CameraStreamState.Connecting(CAMERA), manager.state.value)
    }

    @Test
    fun `stream types failure still tries WebRTC`() = runTest {
        source.streamTypesResult = Result.failure(IllegalStateException("not connected"))
        val (viewModel, _) = viewModel()

        viewModel.onStart()
        runCurrent()

        assertEquals(CameraScreenMode.Connecting, viewModel.uiState.value.mode)
        assertEquals(1, source.sessions.size)
    }

    @Test
    fun `leftover state of another camera is not shown`() = runTest {
        val (viewModel, manager) = viewModel()
        manager.start("camera.other")
        runCurrent()
        source.sessions.single().send(HaWebRtcEvent.Error("x", "other failed"))
        runCurrent()

        viewModel.onStart()
        runCurrent()

        assertEquals(CameraScreenMode.Connecting, viewModel.uiState.value.mode)
    }
}
