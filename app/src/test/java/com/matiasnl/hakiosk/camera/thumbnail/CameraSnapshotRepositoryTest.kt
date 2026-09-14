package com.matiasnl.hakiosk.camera.thumbnail

import com.matiasnl.hakiosk.camera.ScriptedHaCameraSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CAMERA = "camera.driveway"

/** Decodes byte arrays to strings; a first byte of 0 simulates a corrupt JPEG. */
private val fakeDecoder = SnapshotDecoder<String> { bytes, width, height ->
    if (bytes.first() == 0.toByte()) null else "img${bytes.joinToString("")}@${width}x$height"
}

@OptIn(ExperimentalCoroutinesApi::class)
class CameraSnapshotRepositoryTest {

    private val source = ScriptedHaCameraSource()

    private fun TestScope.repository(maxCacheBytes: Long = 1_000) = CameraSnapshotRepository(
        source = source,
        decoder = fakeDecoder,
        decodeDispatcher = UnconfinedTestDispatcher(testScheduler),
        maxCacheBytes = maxCacheBytes,
        sizeOf = { it.length.toLong() },
        clock = { testScheduler.currentTime },
    )

    private fun TestScope.collect(
        repository: CameraSnapshotRepository<String>,
        emissions: MutableList<CameraSnapshot<String>>,
        intervalMillis: Long = THUMBNAIL_REFRESH_INTERVAL_MS,
        useCache: Boolean = true,
    ) = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        repository.snapshots(CAMERA, 320, 180, intervalMillis, useCache).collect { emissions += it }
    }

    @Test
    fun `polls only while collected, at the configured interval, with the tile width`() = runTest {
        val repository = repository()
        advanceTimeBy(60_000)
        assertTrue(source.snapshotRequests.isEmpty())

        val emissions = mutableListOf<CameraSnapshot<String>>()
        val job = collect(repository, emissions)
        runCurrent()
        assertEquals(listOf(CAMERA to 320), source.snapshotRequests)
        assertEquals(CameraSnapshot("img123@320x180"), emissions.single())

        advanceTimeBy(THUMBNAIL_REFRESH_INTERVAL_MS - 1)
        runCurrent()
        assertEquals(1, source.snapshotRequests.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, source.snapshotRequests.size)

        job.cancel()
        advanceTimeBy(10 * THUMBNAIL_REFRESH_INTERVAL_MS)
        runCurrent()
        assertEquals(2, source.snapshotRequests.size)
    }

    @Test
    fun `failures keep the last image and report the error`() = runTest {
        source.snapshotResults += Result.success(byteArrayOf(7))
        source.snapshotResults += Result.failure(IllegalStateException("Cámara apagada"))
        source.snapshotResults += Result.success(ByteArray(0))
        source.snapshotResults += Result.success(byteArrayOf(0, 1))
        val emissions = mutableListOf<CameraSnapshot<String>>()
        collect(repository = repository(), emissions = emissions, intervalMillis = 1_000)

        runCurrent()
        repeat(3) {
            advanceTimeBy(1_000)
            runCurrent()
        }

        assertEquals(
            listOf(
                CameraSnapshot("img7@320x180"),
                CameraSnapshot("img7@320x180", "Cámara apagada"),
                CameraSnapshot("img7@320x180", "Empty snapshot"),
                CameraSnapshot("img7@320x180", "Could not decode snapshot"),
            ),
            emissions,
        )
    }

    @Test
    fun `empty or undecodable snapshot without a previous image yields a placeholder`() = runTest {
        source.snapshotResults += Result.success(ByteArray(0))
        val emissions = mutableListOf<CameraSnapshot<String>>()
        collect(repository(), emissions)
        runCurrent()

        assertEquals(CameraSnapshot<String>(null, "Empty snapshot"), emissions.single())
    }

    @Test
    fun `a new collector shows the cached image at once and waits for the rest of the interval`() = runTest {
        val repository = repository()
        val first = collect(repository, mutableListOf())
        runCurrent()
        first.cancel()
        advanceTimeBy(4_000)

        val emissions = mutableListOf<CameraSnapshot<String>>()
        collect(repository, emissions)
        runCurrent()
        assertEquals(listOf(CameraSnapshot("img123@320x180")), emissions)
        assertEquals(1, source.snapshotRequests.size)
        assertEquals("img123@320x180", repository.cachedImage(CAMERA))

        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(2, source.snapshotRequests.size)
    }

    @Test
    fun `uncached flows do not fill the thumbnail cache`() = runTest {
        val repository = repository()
        val emissions = mutableListOf<CameraSnapshot<String>>()
        collect(repository, emissions, intervalMillis = FOCUS_SNAPSHOT_REFRESH_INTERVAL_MS, useCache = false)
        runCurrent()

        assertEquals(1, emissions.size)
        assertEquals(null, repository.cachedImage(CAMERA))
    }

    @Test
    fun `thrown source exceptions are treated as failures`() = runTest {
        val throwing = object : com.matiasnl.hakiosk.data.ha.camera.HaCameraSource by source {
            override suspend fun fetchSnapshot(entityId: String, width: Int?): Result<ByteArray> =
                throw java.io.IOException("socket closed")
        }
        val repository = CameraSnapshotRepository(
            source = throwing,
            decoder = fakeDecoder,
            decodeDispatcher = UnconfinedTestDispatcher(testScheduler),
            maxCacheBytes = 1_000,
            sizeOf = { it.length.toLong() },
            clock = { testScheduler.currentTime },
        )
        val emissions = mutableListOf<CameraSnapshot<String>>()
        collect(repository, emissions)
        runCurrent()

        assertEquals(CameraSnapshot<String>(null, "socket closed"), emissions.single())
    }
}
