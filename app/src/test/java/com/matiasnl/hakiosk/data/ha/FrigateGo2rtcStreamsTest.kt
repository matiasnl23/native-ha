package com.matiasnl.hakiosk.data.ha

import com.matiasnl.hakiosk.data.ha.camera.FrigateGo2rtcClient
import com.matiasnl.hakiosk.data.ha.camera.WebSocketHaCameraSource
import com.matiasnl.hakiosk.data.ha.fake.InMemoryHaConfigStore
import com.matiasnl.hakiosk.data.ha.rest.HaHttpException
import com.matiasnl.hakiosk.data.ha.rest.HaRestClient
import com.matiasnl.hakiosk.data.ha.ws.HaRequestException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrigateGo2rtcStreamsTest {
    private val server = MockWebServer().apply { start() }
    private val okHttp = OkHttpClient()
    private val configStore = InMemoryHaConfigStore(HaServerConfig(server.url("/ha/").toString(), "secret-token"))
    private val entities = mutableMapOf(
        "camera.garage" to camera("camera.garage", clientId = "frigate1"),
        "camera.no_mqtt_id" to camera("camera.no_mqtt_id", clientId = "None"),
        "camera.generic" to HaEntity("camera.generic", "idle", JsonObject(emptyMap()), ""),
        "light.kitchen" to HaEntity("light.kitchen", "on", JsonObject(mapOf("client_id" to JsonPrimitive("frigate1"))), ""),
    )
    private val source = WebSocketHaCameraSource(
        activeConnection = { null },
        configStore = configStore,
        restClient = HaRestClient(okHttp),
        go2rtcClient = FrigateGo2rtcClient(okHttp),
        entity = { entities[it] },
    )

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun listsSortedStreamNamesWithBearerAndPathPrefix() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"garage":{"producers":[{"url":"rtsp://x"}],"consumers":null},"Back_Yard":{"producers":[]},""" +
                    """"alpha":{},"front door":{"producers":[],"consumers":[]}}""",
            ),
        )

        val streams = source.go2rtcStreams("camera.garage").getOrThrow()

        assertEquals(listOf("alpha", "Back_Yard", "front door", "garage"), streams)
        val request = server.takeRequest()
        assertEquals("/ha/api/frigate/frigate1/go2rtc/streams", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
    }

    @Test
    fun errorStatusesMapToClearFailuresWithoutToken() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setBody("[]"))

        val missing = source.go2rtcStreams("camera.garage").exceptionOrNull() as HaHttpException
        assertEquals(404, missing.httpCode)
        assertTrue(missing.message!!, missing.message!!.startsWith("Frigate route not found"))
        val unauthorized = source.go2rtcStreams("camera.garage").exceptionOrNull() as HaHttpException
        assertEquals("Invalid access token (HTTP 401)", unauthorized.message)
        val forbidden = source.go2rtcStreams("camera.garage").exceptionOrNull() as HaHttpException
        assertEquals("Access denied (HTTP 403)", forbidden.message)
        val failed = source.go2rtcStreams("camera.garage").exceptionOrNull() as HaHttpException
        assertEquals("Frigate could not list go2rtc streams (HTTP 502)", failed.message)
        val invalid = source.go2rtcStreams("camera.garage").exceptionOrNull() as HaHttpException
        assertEquals("Invalid go2rtc streams response", invalid.message)

        listOf(missing, unauthorized, forbidden, failed, invalid).forEach { assertFalse(it.message!!.contains("secret-token")) }
    }

    @Test
    fun nonFrigateEntitiesFailWithoutRequests() = runTest {
        listOf("camera.unknown", "camera.no_mqtt_id", "camera.generic", "light.kitchen").forEach { id ->
            val failure = source.go2rtcStreams(id).exceptionOrNull()
            assertEquals(id, "not_frigate_camera", (failure as HaRequestException).code)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unconfiguredAndUnreachableServerFail() = runTest {
        server.shutdown()
        val down = source.go2rtcStreams("camera.garage").exceptionOrNull()
        assertTrue(down.toString(), down is HaHttpException && down.message!!.startsWith("go2rtc streams request failed"))

        configStore.clear()
        assertTrue(source.go2rtcStreams("camera.garage").isFailure)
    }

    private fun camera(entityId: String, clientId: String) = HaEntity(
        entityId = entityId,
        state = "streaming",
        attributes = JsonObject(mapOf("client_id" to JsonPrimitive(clientId), "camera_name" to JsonPrimitive("garage"))),
        lastChanged = "",
    )
}
