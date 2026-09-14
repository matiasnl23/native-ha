package com.matiasnl.hakiosk.data.ha

import com.matiasnl.hakiosk.data.ha.rest.HaRestClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HaRestClientTest {
    private val server = MockWebServer().apply { start() }
    private val client = HaRestClient(OkHttpClient())
    private val baseUrl = server.url("/").toString() // trailing slash on purpose

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun successReturnsVersionAndSendsBearer() = runTest {
        server.enqueue(MockResponse().setBody("""{"version":"2026.9.1","location_name":"Home"}"""))

        val result = client.testConnection(HaServerConfig(baseUrl, "abc"))

        assertEquals(HaConnectionTestResult.Success("2026.9.1"), result)
        val request = server.takeRequest()
        assertEquals("/api/config", request.path)
        assertEquals("Bearer abc", request.getHeader("Authorization"))
    }

    @Test
    fun unauthorizedIsInvalidToken() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(HaConnectionTestResult.InvalidToken, client.testConnection(HaServerConfig(baseUrl, "bad")))
    }

    @Test
    fun nonHomeAssistantResponseIsUnreachable() = runTest {
        server.enqueue(MockResponse().setBody("<html></html>"))
        assertTrue(client.testConnection(HaServerConfig(baseUrl, "abc")) is HaConnectionTestResult.Unreachable)
    }

    @Test
    fun tlsFailureIsUnreachableWithTlsMessage() = runTest {
        // https against a server that answers in plaintext fails the TLS handshake deterministically.
        val plain = java.net.ServerSocket(0)
        val acceptor = Thread {
            runCatching {
                plain.accept().use { socket ->
                    socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                    socket.getOutputStream().flush()
                }
            }
        }.apply { start() }
        val result = try {
            client.testConnection(HaServerConfig("https://127.0.0.1:${plain.localPort}", "abc"))
        } finally {
            plain.close()
            acceptor.join(2_000)
        }
        assertTrue(result.toString(), result is HaConnectionTestResult.Unreachable)
        val message = (result as HaConnectionTestResult.Unreachable).message
        assertTrue(message, message.startsWith("TLS error") || message.startsWith("Certificate not trusted"))
    }

    @Test
    fun invalidUrlAndDownServerAreUnreachable() = runTest {
        assertEquals(
            HaConnectionTestResult.Unreachable("Invalid URL"),
            client.testConnection(HaServerConfig("192.168.1.50:8123", "abc")),
        )
        server.shutdown()
        assertTrue(client.testConnection(HaServerConfig(baseUrl, "abc")) is HaConnectionTestResult.Unreachable)
    }
}
