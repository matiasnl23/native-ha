package com.matiasnl.hakiosk.data.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

/** Downloads the APK of a release and proves it is byte-for-byte the published one. */
interface ApkDownloadSource {
    /**
     * Downloads [asset] and checks its SHA-256 before returning it. A failure leaves nothing behind:
     * the partial file is deleted. Fails with an [UpdateFailureException] carrying a typed [UpdateError].
     *
     * [onProgress] is called with (downloaded, total) bytes, throttled; the caller can render it
     * directly as a percentage.
     */
    suspend fun download(asset: ApkAsset, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<File>

    /** Deletes anything left in the download directory (stale partials from a killed process). */
    fun clear()
}

/**
 * Downloads to **internal** storage ([downloadDir] under `cacheDir`) on purpose: on external storage
 * another app could swap the file between the hash check and the install, which is exactly the check
 * this class exists to make meaningful.
 *
 * The hash is computed while streaming, so the file is never read twice, and the download stops as soon
 * as it exceeds the size the metadata declared.
 */
class ApkDownloader(
    client: OkHttpClient,
    private val downloadDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Injectable so the "not enough space" path is testable without filling a disk. */
    private val usableSpaceBytes: (File) -> Long = { it.usableSpace },
) : ApkDownloadSource {

    // No call timeout: a 60 MB universal APK on a slow link would trip any fixed budget. The read
    // timeout still fails a stalled connection, and the coroutine can be cancelled mid-download.
    private val client = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override fun clear() {
        downloadDir.listFiles()?.forEach { it.delete() }
    }

    override suspend fun download(asset: ApkAsset, onProgress: (Long, Long) -> Unit): Result<File> =
        withContext(ioDispatcher) {
            if (asset.sizeBytes > MAX_APK_BYTES) {
                return@withContext Result.failure(
                    UpdateFailureException(UpdateError.InvalidMetadata("Published APK is implausibly large")),
                )
            }
            downloadDir.mkdirs()
            // Whatever a previous attempt (or a killed process) left behind goes first: the space check
            // below must see the real free space, and a stale APK must never be installed by accident.
            clear()

            // Twice the APK plus a margin: the file itself, and the copy PackageInstaller writes into
            // its own session when the install starts.
            val required = asset.sizeBytes * 2 + SPACE_MARGIN_BYTES
            val available = usableSpaceBytes(downloadDir)
            if (available < required) {
                return@withContext Result.failure(
                    UpdateFailureException(UpdateError.NotEnoughSpace(required, available)),
                )
            }

            val target = File(downloadDir, APK_FILE_NAME)
            try {
                streamToFile(asset, target, onProgress)
                Result.success(target)
            } catch (e: UpdateFailureException) {
                target.delete()
                Result.failure(e)
            } catch (e: IOException) {
                target.delete()
                Result.failure(UpdateFailureException(UpdateError.Network(UpdateNetworkErrors.describe(e))))
            } catch (e: Throwable) {
                // Cancellation included: never leave a half-written APK on disk.
                target.delete()
                throw e
            }
        }

    private suspend fun streamToFile(asset: ApkAsset, target: File, onProgress: (Long, Long) -> Unit) {
        val request = Request.Builder().url(asset.url).get().build()
        // Redirects are followed by default: the release asset URL redirects to object storage.
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) updateFailure(UpdateError.Network("Download failed (HTTP ${response.code})"))
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var reported = 0L
            onProgress(0, asset.sizeBytes)
            response.body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        downloaded += read
                        if (downloaded > asset.sizeBytes) {
                            updateFailure(UpdateError.ChecksumMismatch)
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        if (downloaded - reported >= PROGRESS_STEP_BYTES) {
                            reported = downloaded
                            onProgress(downloaded, asset.sizeBytes)
                        }
                    }
                    output.flush()
                    // The bytes must survive a power cut between here and the install.
                    output.fd.sync()
                }
            }
            if (downloaded != asset.sizeBytes) {
                updateFailure(UpdateError.Network("Download truncated at $downloaded of ${asset.sizeBytes} bytes"))
            }
            if (digest.digest().toHexString() != asset.sha256) {
                updateFailure(UpdateError.ChecksumMismatch)
            }
            onProgress(downloaded, asset.sizeBytes)
        }
    }

    companion object {
        /** Single fixed name: the directory is wiped before each download, so nothing accumulates. */
        const val APK_FILE_NAME = "update.apk"

        /** Sanity bound on what the metadata may ask the tablet to download. */
        const val MAX_APK_BYTES: Long = 512L * 1024 * 1024

        /** Headroom beyond the two copies, so the install doesn't fill the last free byte. */
        const val SPACE_MARGIN_BYTES: Long = 32L * 1024 * 1024

        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 512L * 1024
        private const val READ_TIMEOUT_SECONDS = 60L
    }
}
