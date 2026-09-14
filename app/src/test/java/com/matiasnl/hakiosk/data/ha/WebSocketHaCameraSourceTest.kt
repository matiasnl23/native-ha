package com.matiasnl.hakiosk.data.ha

import app.cash.turbine.test
import com.matiasnl.hakiosk.data.ha.FakeHaWebSocketServer.Companion.state
import com.matiasnl.hakiosk.data.ha.camera.HaCameraStreamType
import com.matiasnl.hakiosk.data.ha.camera.HaIceCandidate
import com.matiasnl.hakiosk.data.ha.camera.HaIceServer
import com.matiasnl.hakiosk.data.ha.camera.HaWebRtcClientConfig
import com.matiasnl.hakiosk.data.ha.camera.HaWebRtcEvent
import com.matiasnl.hakiosk.data.ha.camera.WebSocketHaCameraSource
import com.matiasnl.hakiosk.data.ha.fake.InMemoryHaConfigStore
import com.matiasnl.hakiosk.data.ha.ws.Backoff
import com.matiasnl.hakiosk.data.ha.ws.HaClientSettings
import com.matiasnl.hakiosk.data.ha.ws.HaRequestException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class WebSocketHaCameraSourceTest {
    private val ha = FakeHaWebSocketServer()
    private val configStore = InMemoryHaConfigStore(HaServerConfig(ha.baseUrl, "good-token"))
    private val okHttp = OkHttpClient()
    private val repo = WebSocketHaRepository(
        configStore = configStore,
        okHttpClient = okHttp,
        settings = HaClientSettings(heartbeatIntervalMillis = 0),
        backoff = Backoff { 50 },
    )
    private val source = WebSocketHaCameraSource(repo, configStore, okHttp)

    @After
    fun tearDown() {
        repo.stop()
        ha.shutdown()
    }

    private suspend fun <T> awaitValue(flow: Flow<T>, predicate: (T) -> Boolean): T {
        var found: T? = null
        flow.test(timeout = 5.seconds) {
            while (found == null) {
                val item = awaitItem()
                if (predicate(item)) found = item
            }
            cancelAndIgnoreRemainingEvents()
        }
        @Suppress("UNCHECKED_CAST")
        return found as T
    }

    private suspend fun connect() {
        repo.start()
        awaitValue(repo.connectionState) { it == HaConnectionState.Connected }
    }

    @Test
    fun capabilitiesAndClientConfigAreFetchedOverWebSocket() = runTest {
        connect()
        ha.cameraCapabilities = Json.parseToJsonElement("""{"frontend_stream_types":["hls","web_rtc"]}""")

        assertEquals(setOf(HaCameraStreamType.HLS, HaCameraStreamType.WEB_RTC), source.streamTypes("camera.door").getOrThrow())
        assertEquals("camera.door", ha.awaitMessage("camera/capabilities")["entity_id"]!!.jsonPrimitive.content)

        assertEquals(
            HaWebRtcClientConfig(listOf(HaIceServer(listOf("stun:stun.home-assistant.io:3478")))),
            source.webRtcClientConfig("camera.door").getOrThrow(),
        )
        assertEquals("camera.door", ha.awaitMessage("camera/webrtc/get_client_config")["entity_id"]!!.jsonPrimitive.content)

        ha.cameraCapabilities = Json.parseToJsonElement("""{"unexpected":true}""")
        assertTrue(source.streamTypes("camera.door").isFailure)
    }

    @Test
    fun notConnectedFailsCommandsAndSessionEmitsError() = runTest {
        val failure = source.streamTypes("camera.door").exceptionOrNull()
        assertEquals(HaRequestException.CODE_NOT_CONNECTED, (failure as HaRequestException).code)
        assertTrue(source.sendWebRtcCandidate("camera.door", "s", HaIceCandidate("candidate:x", null, null)).isFailure)

        source.webRtcSession("camera.door", "v=0").test(timeout = 5.seconds) {
            val error = awaitItem() as HaWebRtcEvent.Error
            assertEquals("not_connected", error.code)
            awaitComplete()
        }
    }

    @Test
    fun offerStreamsSessionAnswerAndCandidatesUntilTerminalError() = runTest {
        connect()
        source.webRtcSession("camera.door", "v=0 offer").test(timeout = 5.seconds) {
            assertEquals(HaWebRtcEvent.Session("session-1"), awaitItem())
            val offer = ha.awaitMessage("camera/webrtc/offer")
            assertEquals("camera.door", offer["entity_id"]!!.jsonPrimitive.content)
            assertEquals("v=0 offer", offer["offer"]!!.jsonPrimitive.content)
            val id = offer["id"]!!.jsonPrimitive.int

            ha.sendSubscriptionEvent(id, """{"type":"answer","answer":"v=0 answer"}""")
            assertEquals(HaWebRtcEvent.Answer("v=0 answer"), awaitItem())

            ha.sendSubscriptionEvent(id, """{"type":"candidate","candidate":{"candidate":"candidate:1","sdpMid":"0","sdpMLineIndex":0}}""")
            ha.sendSubscriptionEvent(id, """{"type":"candidate","candidate":{"candidate":""}}""") // end marker: skipped
            ha.sendSubscriptionEvent(id, """{"type":"from_the_future"}""") // unknown: skipped
            ha.sendSubscriptionEvent(id, """{"type":"candidate","candidate":"candidate:2"}""")
            assertEquals(HaWebRtcEvent.RemoteCandidate(HaIceCandidate("candidate:1", "0", 0)), awaitItem())
            assertEquals(HaWebRtcEvent.RemoteCandidate(HaIceCandidate("candidate:2", null, null)), awaitItem())

            ha.sendSubscriptionEvent(id, """{"type":"error","code":"webrtc_offer_failed","message":"go2rtc down"}""")
            ha.sendSubscriptionEvent(id, """{"type":"candidate","candidate":"candidate:3"}""")
            assertEquals(HaWebRtcEvent.Error("webrtc_offer_failed", "go2rtc down"), awaitItem())
            awaitComplete()

            assertEquals(id, ha.awaitMessage("unsubscribe_events")["subscription"]!!.jsonPrimitive.int)
        }
        assertEquals(HaConnectionState.Connected, repo.connectionState.value)
    }

    @Test
    fun rejectedOfferEmitsErrorWithHaCode() = runTest {
        connect()
        ha.offerError = "webrtc_offer_failed" to "Camera does not support WebRTC"
        source.webRtcSession("camera.door", "v=0").test(timeout = 5.seconds) {
            assertEquals(HaWebRtcEvent.Error("webrtc_offer_failed", "Camera does not support WebRTC"), awaitItem())
            awaitComplete()
        }
        assertEquals(0, repo.activeConnection()!!.activeSubscriptionCount)
    }

    @Test
    fun cancellingCollectionUnsubscribesAndStopsRouting() = runTest {
        connect()
        source.webRtcSession("camera.door", "v=0").test(timeout = 5.seconds) {
            assertEquals(HaWebRtcEvent.Session("session-1"), awaitItem())
            assertEquals(1, repo.activeConnection()!!.activeSubscriptionCount)
            cancelAndIgnoreRemainingEvents()
        }
        val offerId = ha.awaitMessage("camera/webrtc/offer")["id"]!!.jsonPrimitive.int
        assertEquals(offerId, ha.awaitMessage("unsubscribe_events")["subscription"]!!.jsonPrimitive.int)
        assertEquals(0, repo.activeConnection()!!.activeSubscriptionCount)

        // Late events for the old session are dropped; state updates keep flowing on the same connection.
        ha.sendSubscriptionEvent(offerId, """{"type":"answer","answer":"late"}""")
        ha.sendStateChanged("light.kitchen", state("light.kitchen", "on"))
        awaitValue(repo.entities) { it["light.kitchen"]?.state == "on" }
        assertEquals(HaConnectionState.Connected, repo.connectionState.value)
        assertEquals(1, ha.connections.get())
    }

    @Test
    fun connectionDropMidSessionEmitsTerminalError() = runTest {
        connect()
        source.webRtcSession("camera.door", "v=0").test(timeout = 5.seconds) {
            assertEquals(HaWebRtcEvent.Session("session-1"), awaitItem())
            ha.dropConnection()
            val error = awaitItem() as HaWebRtcEvent.Error
            assertEquals(HaRequestException.CODE_CONNECTION_LOST, error.code)
            awaitComplete()
        }
        // The repository still reconnects on its own. Poll: right after the drop the state can still
        // read the old Connected, and a StateFlow collector may skip the intermediate states.
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!(ha.connections.get() == 2 && repo.connectionState.value == HaConnectionState.Connected)) {
                check(System.currentTimeMillis() < deadline) { "Did not reconnect: ${repo.connectionState.value}" }
                Thread.sleep(20)
            }
        }
    }

    @Test
    fun candidateIsSentAsIceCandidateInit() = runTest {
        connect()
        val result = source.sendWebRtcCandidate("camera.door", "session-1", HaIceCandidate("candidate:x", "0", 0))
        assertTrue(result.isSuccess)

        val message = ha.awaitMessage("camera/webrtc/candidate")
        assertEquals("camera.door", message["entity_id"]!!.jsonPrimitive.content)
        assertEquals("session-1", message["session_id"]!!.jsonPrimitive.content)
        assertEquals(
            Json.parseToJsonElement("""{"candidate":"candidate:x","sdpMid":"0","sdpMLineIndex":0}"""),
            message["candidate"]!!.jsonObject,
        )
    }

    @Test
    fun snapshotUsesStoredConfigEvenWithoutWebSocket() = runTest {
        val bytes = source.fetchSnapshot("camera.door", width = 480).getOrThrow()

        assertArrayEquals(ha.snapshotBody, bytes)
        val request = ha.lastSnapshotRequest!!
        assertEquals("/api/camera_proxy/camera.door?width=480", request.path)
        assertEquals("Bearer good-token", request.getHeader("Authorization"))

        configStore.clear()
        assertTrue(source.fetchSnapshot("camera.door").isFailure)
    }
}
