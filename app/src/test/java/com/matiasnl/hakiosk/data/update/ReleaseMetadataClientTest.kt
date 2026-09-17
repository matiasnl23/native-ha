package com.matiasnl.hakiosk.data.update

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseMetadataClientTest {
    private val server = MockWebServer().apply { start() }
    private val url = server.url("/releases/latest/download/release-metadata.json").toString()
    private val client = ReleaseMetadataClient(OkHttpClient(), url)

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `reads the published metadata`() = runTest {
        server.enqueue(MockResponse().setBody(METADATA))

        val metadata = client.fetch().getOrThrow()

        assertEquals(10002L, metadata.versionCode)
        assertEquals("1.0.2", metadata.versionName)
        assertEquals("/releases/latest/download/release-metadata.json", server.takeRequest().path)
    }

    @Test
    fun `follows the redirect to object storage`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/objects/metadata.json")))
        server.enqueue(MockResponse().setBody(METADATA))

        assertEquals(10002L, client.fetch().getOrThrow().versionCode)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a repository without releases is a network error, not a crash`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val error = client.fetch().errorOrNull()

        assertTrue(error.toString(), error is UpdateError.Network)
        assertTrue(error.toString(), (error as UpdateError.Network).message.contains("404"))
    }

    @Test
    fun `a server error is a network error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        assertTrue(client.fetch().errorOrNull() is UpdateError.Network)
    }

    @Test
    fun `a corrupt body is invalid metadata, not a network error`() = runTest {
        server.enqueue(MockResponse().setBody("<html>not found</html>"))

        assertTrue(client.fetch().errorOrNull() is UpdateError.InvalidMetadata)
    }

    @Test
    fun `an oversized body is rejected without reading it all`() = runTest {
        server.enqueue(MockResponse().setBody("x".repeat((ReleaseMetadataClient.MAX_METADATA_BYTES + 1024).toInt())))

        assertTrue(client.fetch().errorOrNull() is UpdateError.InvalidMetadata)
    }

    @Test
    fun `an unreachable server is a network error`() = runTest {
        server.shutdown()

        assertTrue(client.fetch().errorOrNull() is UpdateError.Network)
    }

    private fun Result<*>.errorOrNull(): UpdateError? = exceptionOrNull()?.toUpdateError()

    private companion object {
        val METADATA = """
            {"versionCode":10002,"versionName":"1.0.2","minSdk":26,
             "apks":{"universal":{"url":"https://e.invalid/u.apk","sha256":"${"a".repeat(64)}","sizeBytes":100}}}
        """.trimIndent()
    }
}
