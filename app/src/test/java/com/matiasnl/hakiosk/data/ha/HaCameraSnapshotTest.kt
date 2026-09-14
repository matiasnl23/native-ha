package com.matiasnl.hakiosk.data.ha

import com.matiasnl.hakiosk.data.ha.rest.HaHttpException
import com.matiasnl.hakiosk.data.ha.rest.HaRestClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaCameraSnapshotTest {
    private val server = MockWebServer().apply { start() }
    private val client = HaRestClient(OkHttpClient())
    private val config = HaServerConfig(server.url("/").toString(), "secret-token")
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun HaHttpException?.orFail(): HaHttpException = this ?: error("expected HaHttpException")

    @Test
    fun sendsBearerAndWidthAndReturnsBytes() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg)))

        val result = client.fetchCameraSnapshot(config, "camera.front_door", width = 320)

        assertArrayEquals(jpeg, result.getOrThrow())
        val request = server.takeRequest()
        assertEquals("/api/camera_proxy/camera.front_door?width=320", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
    }

    @Test
    fun widthIsOmittedWhenNullOrNotPositive() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(jpeg)))
        server.enqueue(MockResponse().setBody(Buffer().write(jpeg)))

        assertTrue(client.fetchCameraSnapshot(config, "camera.a").isSuccess)
        assertTrue(client.fetchCameraSnapshot(config, "camera.a", width = 0).isSuccess)

        assertEquals("/api/camera_proxy/camera.a", server.takeRequest().path)
        assertEquals("/api/camera_proxy/camera.a", server.takeRequest().path)
    }

    @Test
    fun errorStatusesMapToClearFailuresWithoutToken() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(500))

        val unauthorized = client.fetchCameraSnapshot(config, "camera.a").exceptionOrNull() as? HaHttpException
        assertEquals(401, unauthorized.orFail().httpCode)
        assertEquals("Invalid access token (HTTP 401)", unauthorized.orFail().message)

        val missing = client.fetchCameraSnapshot(config, "camera.gone").exceptionOrNull() as? HaHttpException
        assertEquals("Camera not found: camera.gone (HTTP 404)", missing.orFail().message)

        val off = client.fetchCameraSnapshot(config, "camera.a").exceptionOrNull() as? HaHttpException
        assertEquals(503, off.orFail().httpCode)

        val failed = client.fetchCameraSnapshot(config, "camera.a").exceptionOrNull() as? HaHttpException
        assertEquals("Home Assistant could not get the camera image (HTTP 500)", failed.orFail().message)
        listOf(unauthorized, missing, off, failed).forEach { assertTrue(it!!.message!!.contains("secret-token").not()) }
    }

    @Test
    fun oversizedBodiesFailWithAndWithoutContentLength() = runTest {
        val big = ByteArray(11) { 1 }
        server.enqueue(MockResponse().setBody(Buffer().write(big)))
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(big), 4))
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(10) { 2 })))

        val declared = client.fetchCameraSnapshot(config, "camera.a", maxBytes = 10).exceptionOrNull() as? HaHttpException
        assertTrue(declared.orFail().message!!.startsWith("Snapshot too large"))
        assertNull(declared.orFail().httpCode)

        val chunked = client.fetchCameraSnapshot(config, "camera.a", maxBytes = 10).exceptionOrNull() as? HaHttpException
        assertTrue(chunked.orFail().message!!.startsWith("Snapshot too large"))

        assertEquals(10, client.fetchCameraSnapshot(config, "camera.a", maxBytes = 10).getOrThrow().size)
    }

    @Test
    fun emptyBodyAndUnreachableServerFail() = runTest {
        server.enqueue(MockResponse().setBody(""))
        assertEquals("Empty snapshot", client.fetchCameraSnapshot(config, "camera.a").exceptionOrNull()?.message)

        server.shutdown()
        val down = client.fetchCameraSnapshot(config, "camera.a").exceptionOrNull()
        assertTrue(down.toString(), down is HaHttpException && down.message!!.startsWith("Snapshot failed"))
    }
}
