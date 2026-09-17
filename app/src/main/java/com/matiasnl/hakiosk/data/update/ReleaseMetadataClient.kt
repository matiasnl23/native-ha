package com.matiasnl.hakiosk.data.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where the app learns about published releases. Swapped for a fake in tests. */
fun interface ReleaseMetadataSource {
    /** Fails with an [UpdateFailureException] carrying a typed [UpdateError]. */
    suspend fun fetch(): Result<ReleaseMetadata>
}

/**
 * Reads `release-metadata.json` from the release asset URL of the GitHub repository.
 *
 * Uses the stable `releases/latest/download/<asset>` URL, **not** the GitHub API: without a token the
 * API allows 60 requests per hour per source IP (shared by the whole house behind one NAT) and a
 * conditional 304 still consumes quota. The asset URL is a plain redirect to object storage, which
 * OkHttp follows by default.
 *
 * No certificate pinning on purpose: an expired pin would lock every tablet out of the very channel
 * that fixes it. System HTTPS plus the end-to-end signature check of the downloaded APK is the stronger
 * combination (see `docs/RELEASE-OTA.md`).
 */
class ReleaseMetadataClient(
    client: OkHttpClient,
    private val metadataUrl: String = LATEST_METADATA_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReleaseMetadataSource {

    // Derived client: shares the connection pool and dispatcher with the app's shared one.
    private val client = client.newBuilder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(): Result<ReleaseMetadata> = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url(metadataUrl)
            // The tablet checks every few hours: a cached copy would hide a release that just shipped.
            .cacheControl(CacheControl.FORCE_NETWORK)
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = if (response.code == 404) {
                        "No release published yet (HTTP 404)"
                    } else {
                        "HTTP ${response.code}"
                    }
                    updateFailure(UpdateError.Network(detail))
                }
                val source = response.body.source()
                // Buffers at most MAX_METADATA_BYTES + 1: a wrong URL answering with a huge body
                // (an HTML error page, say) is rejected instead of read into memory.
                if (source.request(MAX_METADATA_BYTES + 1)) {
                    updateFailure(UpdateError.InvalidMetadata("Release metadata is too large"))
                }
                ReleaseMetadataParser.parse(source.readUtf8())
            }
        } catch (e: UpdateFailureException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(UpdateFailureException(UpdateError.Network(UpdateNetworkErrors.describe(e))))
        }
    }

    companion object {
        /** Stable "latest release" asset URL; see the class KDoc for why it isn't the GitHub API. */
        const val LATEST_METADATA_URL =
            "https://github.com/matiasnl23/native-ha/releases/latest/download/release-metadata.json"

        /** The real file is well under 2 KB. */
        const val MAX_METADATA_BYTES: Long = 64L * 1024

        private const val CALL_TIMEOUT_SECONDS = 30L
    }
}
