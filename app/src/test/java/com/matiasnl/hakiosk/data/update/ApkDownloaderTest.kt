package com.matiasnl.hakiosk.data.update

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val server = MockWebServer().apply { start() }
    private val downloadDir: File by lazy { File(tempFolder.root, "updates") }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun downloader(usableSpace: Long = 1_000_000_000) =
        ApkDownloader(OkHttpClient(), downloadDir, usableSpaceBytes = { usableSpace })

    private fun asset(
        body: String = APK_BODY,
        sha256: String = sha256Of(APK_BODY),
        sizeBytes: Long = body.toByteArray().size.toLong(),
    ) = ApkAsset("arm64-v8a", server.url("/hakiosk.apk").toString(), sha256, sizeBytes)

    @Test
    fun `downloads the APK and reports progress`() = runTest {
        server.enqueue(MockResponse().setBody(APK_BODY))
        val progress = mutableListOf<Pair<Long, Long>>()

        val file = downloader().download(asset()) { done, total -> progress += done to total }.getOrThrow()

        assertEquals(APK_BODY, file.readText())
        assertEquals(ApkDownloader.APK_FILE_NAME, file.name)
        assertEquals(APK_BODY.length.toLong() to APK_BODY.length.toLong(), progress.last())
    }

    @Test
    fun `follows the redirect to object storage`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/objects/hakiosk.apk")))
        server.enqueue(MockResponse().setBody(APK_BODY))

        assertEquals(APK_BODY, downloader().download(asset()).getOrThrow().readText())
    }

    @Test
    fun `a sha256 that doesn't match fails and leaves nothing on disk`() = runTest {
        server.enqueue(MockResponse().setBody(APK_BODY))

        val result = downloader().download(asset(sha256 = "b".repeat(64)))

        assertEquals(UpdateError.ChecksumMismatch, result.errorOrNull())
        assertNothingLeftOnDisk()
    }

    @Test
    fun `a body longer than the published size is cut off and rejected`() = runTest {
        server.enqueue(MockResponse().setBody(APK_BODY + " and a payload appended by someone else"))

        val result = downloader().download(asset())

        assertEquals(UpdateError.ChecksumMismatch, result.errorOrNull())
        assertNothingLeftOnDisk()
    }

    @Test
    fun `a truncated download is reported as a network error`() = runTest {
        server.enqueue(MockResponse().setBody(APK_BODY))

        val result = downloader().download(asset(sizeBytes = APK_BODY.length + 100L))

        assertTrue(result.errorOrNull().toString(), result.errorOrNull() is UpdateError.Network)
        assertNothingLeftOnDisk()
    }

    @Test
    fun `an HTTP error fails without leaving a file`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = downloader().download(asset())

        assertTrue(result.errorOrNull() is UpdateError.Network)
        assertNothingLeftOnDisk()
    }

    @Test
    fun `not enough free space fails before a single byte is downloaded`() = runTest {
        val result = downloader(usableSpace = 1_000).download(asset())

        val error = result.errorOrNull()
        assertTrue(error.toString(), error is UpdateError.NotEnoughSpace)
        // Twice the APK plus the margin: the file itself and the copy the installer session makes.
        assertEquals(
            APK_BODY.length * 2 + ApkDownloader.SPACE_MARGIN_BYTES,
            (error as UpdateError.NotEnoughSpace).requiredBytes,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an implausibly large published size is treated as bad metadata`() = runTest {
        val result = downloader().download(asset(sizeBytes = ApkDownloader.MAX_APK_BYTES + 1))

        assertTrue(result.errorOrNull() is UpdateError.InvalidMetadata)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a stale APK from a killed process is wiped before downloading`() = runTest {
        downloadDir.mkdirs()
        val stale = File(downloadDir, ApkDownloader.APK_FILE_NAME).apply { writeText("an older, unverified APK") }
        server.enqueue(MockResponse().setBody(APK_BODY))

        downloader().download(asset()).getOrThrow()

        assertEquals(APK_BODY, stale.readText())
        assertEquals(1, downloadDir.listFiles()?.size)
    }

    @Test
    fun `clear deletes the downloaded APK`() = runTest {
        server.enqueue(MockResponse().setBody(APK_BODY))
        val downloader = downloader()
        downloader.download(asset()).getOrThrow()

        downloader.clear()

        assertNothingLeftOnDisk()
    }

    private fun assertNothingLeftOnDisk() {
        assertFalse(File(downloadDir, ApkDownloader.APK_FILE_NAME).exists())
        assertEquals(0, downloadDir.listFiles()?.size ?: 0)
    }

    private fun Result<*>.errorOrNull(): UpdateError? = exceptionOrNull()?.toUpdateError()

    private fun sha256Of(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val APK_BODY = "PK pretend this is an APK"
    }
}
