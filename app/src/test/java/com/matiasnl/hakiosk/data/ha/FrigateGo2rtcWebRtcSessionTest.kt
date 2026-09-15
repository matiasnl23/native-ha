package com.matiasnl.hakiosk.data.ha

import app.cash.turbine.test
import com.matiasnl.hakiosk.data.ha.camera.FrigateGo2rtcClient
import com.matiasnl.hakiosk.data.ha.camera.FrigateGo2rtcProtocol
import com.matiasnl.hakiosk.data.ha.camera.HaIceCandidate
import com.matiasnl.hakiosk.data.ha.camera.HaWebRtcEvent
import com.matiasnl.hakiosk.data.ha.camera.WebSocketHaCameraSource
import com.matiasnl.hakiosk.data.ha.fake.InMemoryHaConfigStore
import com.matiasnl.hakiosk.data.ha.rest.HaRestClient
import com.matiasnl.hakiosk.data.ha.ws.HaClientSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class FrigateGo2rtcWebRtcSessionTest {
    private val server = MockWebServer().apply { start() }
    private val go2rtc = FakeGo2rtc()
    private val okHttp = OkHttpClient()
    private val configStore = InMemoryHaConfigStore(HaServerConfig(server.url("/ha").toString(), "secret-token"))
    private val entities = mapOf(
        "camera.garage" to HaEntity(
            "camera.garage",
            "streaming",
            JsonObject(mapOf("client_id" to JsonPrimitive("frigate1"), "camera_name" to JsonPrimitive("garage"))),
            "",
        ),
        "camera.generic" to HaEntity("camera.generic", "idle", JsonObject(emptyMap()), ""),
    )

    private fun source(settings: HaClientSettings = HaClientSettings()) = WebSocketHaCameraSource(
        activeConnection = { null },
        configStore = configStore,
        restClient = HaRestClient(okHttp),
        go2rtcClient = FrigateGo2rtcClient(okHttp, settings),
        entity = { entities[it] },
    )

    @After
    fun tearDown() {
        go2rtc.sockets.forEach { runCatching { it.close(1001, "shutdown") } }
        server.shutdown()
    }

    @Test
    fun offerAnswerAndCandidatesFlowThroughTheProxy() = runTest {
        server.enqueue(MockResponse().withWebSocketUpgrade(go2rtc))
        val local = Channel<HaIceCandidate>(Channel.UNLIMITED)
        // Emitted before the answer (and before the socket opens): still sent, after the offer.
        local.trySend(HaIceCandidate("candidate:local-1 1 udp 2122260223 192.168.1.20 50000 typ host", "0", 0))
        local.trySend(HaIceCandidate(" ", null, null)) // blank: skipped

        source().go2rtcWebRtcSession("camera.garage", "garage main", "v=0 offer", local.receiveAsFlow())
            .test(timeout = 10.seconds) {
                val request = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }!!
                assertEquals("/ha/api/frigate/frigate1/webrtc/api/ws?src=garage%20main", request.path)
                assertEquals("Bearer secret-token", request.getHeader("Authorization"))
                assertFalse(request.requestUrl.toString().contains("secret-token"))

                assertEquals(frame("webrtc/offer", "v=0 offer"), go2rtc.awaitMessage())
                assertEquals(
                    frame("webrtc/candidate", "candidate:local-1 1 udp 2122260223 192.168.1.20 50000 typ host"),
                    go2rtc.awaitMessage(),
                )

                val socket = go2rtc.awaitSocket()
                val answer = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:video0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:1\r\n"
                socket.send(frame("webrtc/answer", answer).toString())
                assertEquals(HaWebRtcEvent.Answer(answer), awaitItem())

                socket.send(frame("webrtc/candidate", "candidate:remote-1 1 udp 2130706431 10.0.0.2 8555 typ host").toString())
                socket.send("""{"type":"from_the_future","value":"x"}""") // unknown: skipped
                assertEquals(
                    HaWebRtcEvent.RemoteCandidate(
                        HaIceCandidate("candidate:remote-1 1 udp 2130706431 10.0.0.2 8555 typ host", "video0", 0),
                    ),
                    awaitItem(),
                )

                local.trySend(HaIceCandidate("candidate:local-2", "0", 0))
                assertEquals(frame("webrtc/candidate", "candidate:local-2"), go2rtc.awaitMessage())

                cancelAndIgnoreRemainingEvents()
            }
        assertEquals(1000, go2rtc.awaitCloseCode())
    }

    @Test
    fun go2rtcErrorIsTerminal() = runTest {
        server.enqueue(MockResponse().withWebSocketUpgrade(go2rtc))
        source().go2rtcWebRtcSession("camera.garage", "missing", "v=0", emptyFlow()).test(timeout = 10.seconds) {
            assertEquals(frame("webrtc/offer", "v=0"), go2rtc.awaitMessage())
            go2rtc.awaitSocket().send(frame("error", "webrtc/offer: stream not found").toString())
            assertEquals(HaWebRtcEvent.Error("stream_not_found", "webrtc/offer: stream not found"), awaitItem())
            awaitComplete()
        }
        assertEquals(1000, go2rtc.awaitCloseCode())
    }

    @Test
    fun rejectedHandshakeEmitsErrorWithoutToken() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(404))

        source().go2rtcWebRtcSession("camera.garage", "garage", "v=0", emptyFlow()).test(timeout = 10.seconds) {
            val error = awaitItem() as HaWebRtcEvent.Error
            assertEquals(FrigateGo2rtcClient.CODE_UNAUTHORIZED, error.code)
            assertEquals("Invalid access token (HTTP 401)", error.message)
            awaitComplete()
        }
        source().go2rtcWebRtcSession("camera.garage", "garage", "v=0", emptyFlow()).test(timeout = 10.seconds) {
            val error = awaitItem() as HaWebRtcEvent.Error
            assertEquals(FrigateGo2rtcClient.CODE_PROXY_NOT_FOUND, error.code)
            assertFalse(error.message.contains("secret-token"))
            awaitComplete()
        }
    }

    @Test
    fun socketClosedAfterAnswerEmitsConnectionLost() = runTest {
        server.enqueue(MockResponse().withWebSocketUpgrade(go2rtc))
        source().go2rtcWebRtcSession("camera.garage", "garage", "v=0", emptyFlow()).test(timeout = 10.seconds) {
            go2rtc.awaitMessage()
            val socket = go2rtc.awaitSocket()
            socket.send(frame("webrtc/answer", "v=0\r\n").toString())
            assertEquals(HaWebRtcEvent.Answer("v=0\r\n"), awaitItem())
            socket.close(1001, "restart")
            assertEquals("connection_lost", (awaitItem() as HaWebRtcEvent.Error).code)
            awaitComplete()
        }
    }

    @Test
    fun missingAnswerTimesOutAndClosesTheSocket() = runTest {
        server.enqueue(MockResponse().withWebSocketUpgrade(go2rtc))
        val source = source(HaClientSettings(requestTimeoutMillis = 300))
        source.go2rtcWebRtcSession("camera.garage", "garage", "v=0", emptyFlow()).test(timeout = 10.seconds) {
            assertEquals("timeout", (awaitItem() as HaWebRtcEvent.Error).code)
            awaitComplete()
        }
        assertEquals(1000, go2rtc.awaitCloseCode())
    }

    @Test
    fun nonFrigateCameraUnconfiguredAndUnreachableServerEmitErrors() = runTest {
        source().go2rtcWebRtcSession("camera.generic", "garage", "v=0", emptyFlow()).test(timeout = 10.seconds) {
            assertEquals("not_frigate_camera", (awaitItem() as HaWebRtcEvent.Error).code)
            awaitComplete()
        }
        assertEquals(0, server.requestCount)

        server.shutdown()
        source().go2rtcWebRtcSession("camera.garage", "garage", "v=0", emptyFlow()).test(timeout = 10.seconds) {
            assertEquals(FrigateGo2rtcClient.CODE_CONNECTION_FAILED, (awaitItem() as HaWebRtcEvent.Error).code)
            awaitComplete()
        }

        configStore.clear()
        source().go2rtcWebRtcSession("camera.garage", "garage", "v=0", emptyFlow()).test(timeout = 10.seconds) {
            assertTrue(awaitItem() is HaWebRtcEvent.Error)
            awaitComplete()
        }
    }

    @Test
    fun protocolMapsFramesAndMid() {
        assertEquals("0", FrigateGo2rtcProtocol.firstMid("v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"))
        assertEquals("v", FrigateGo2rtcProtocol.firstMid("v=0\na=mid:v\na=mid:a\n"))
        assertEquals(
            HaWebRtcEvent.Error("go2rtc_error", "webrtc/offer: codecs not matched"),
            FrigateGo2rtcProtocol.parseEvent("""{"type":"error","value":"webrtc/offer: codecs not matched"}""", "0"),
        )
        assertEquals("invalid_answer", (FrigateGo2rtcProtocol.parseEvent("""{"type":"webrtc/answer","value":""}""", "0") as HaWebRtcEvent.Error).code)
        assertNull(FrigateGo2rtcProtocol.parseEvent("""{"type":"webrtc/candidate","value":""}""", "0"))
        assertNull(FrigateGo2rtcProtocol.parseEvent("not json", "0"))
        assertEquals(
            "http://ha.local:8123/p/api/frigate/frigate%201/webrtc/api/ws?src=a%2Bb",
            FrigateGo2rtcProtocol.signalingUrl("ws://ha.local:8123/p/", "frigate 1", "a+b").toString(),
        )
    }

    private fun frame(type: String, value: String): JsonObject = buildJsonObject {
        put("type", type)
        put("value", value)
    }

    /** Server side of go2rtc's `/api/ws`: records client frames and close codes. */
    private class FakeGo2rtc : WebSocketListener() {
        val sockets = CopyOnWriteArrayList<WebSocket>()
        private val received = LinkedBlockingQueue<JsonObject>()
        private val closeCodes = LinkedBlockingQueue<Int>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets.add(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(Json.parseToJsonElement(text).jsonObject)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeCodes.add(code)
            webSocket.close(1000, null)
        }

        suspend fun awaitMessage(): JsonObject = withContext(Dispatchers.IO) {
            received.poll(5, TimeUnit.SECONDS) ?: error("No message from the client")
        }

        suspend fun awaitCloseCode(): Int = withContext(Dispatchers.IO) {
            closeCodes.poll(5, TimeUnit.SECONDS) ?: error("Client did not close the socket")
        }

        suspend fun awaitSocket(): WebSocket = withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 5_000
            while (sockets.isEmpty()) {
                check(System.currentTimeMillis() < deadline) { "No socket opened" }
                Thread.sleep(10)
            }
            sockets.first()
        }
    }
}
