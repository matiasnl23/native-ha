package com.matiasnl.hakiosk.camera.thumbnail

import com.matiasnl.hakiosk.data.ha.camera.HaCameraSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Refresh interval of dashboard thumbnails. One place to tune network/CPU use of the grid. */
const val THUMBNAIL_REFRESH_INTERVAL_MS = 10_000L

/** Refresh interval of the focus view when live video isn't available. */
const val FOCUS_SNAPSHOT_REFRESH_INTERVAL_MS = 1_500L

/** Latest known image of a camera. [errorMessage] is set when the last refresh failed ([image] is then the previous one, if any). */
data class CameraSnapshot<T : Any>(val image: T?, val errorMessage: String? = null)

private class CachedImage<T : Any>(val image: T, val fetchedAtMillis: Long)

/**
 * Polls camera snapshots (never video) for thumbnails and the no-video fallback.
 *
 * Flows are cold: polling only happens while collected, so the UI stops it by leaving composition
 * (tile scrolled away) or dropping below STARTED (`collectAsStateWithLifecycle`). Tile images go
 * into a byte-bounded LRU keyed by entity id, so scrolling back shows the last image at once and
 * waits for the rest of the interval instead of refetching.
 */
class CameraSnapshotRepository<T : Any>(
    private val source: HaCameraSource,
    private val decoder: SnapshotDecoder<T>,
    private val decodeDispatcher: CoroutineDispatcher,
    maxCacheBytes: Long,
    sizeOf: (T) -> Long,
    private val clock: () -> Long,
) {
    private val cache = ByteLruCache<String, CachedImage<T>>(maxCacheBytes) { sizeOf(it.image) }

    /** Last cached image, for showing something on the very first frame of a tile. */
    fun cachedImage(entityId: String): T? = cache[entityId]?.image

    /**
     * Emits the cached image (if [useCache] and present), then refreshes every [intervalMillis].
     * Fetch, empty body or decode failures keep the previous image and set [CameraSnapshot.errorMessage].
     */
    fun snapshots(
        entityId: String,
        widthPx: Int,
        heightPx: Int,
        intervalMillis: Long,
        useCache: Boolean,
    ): Flow<CameraSnapshot<T>> = flow {
        var current: T? = null
        var lastFetchAt: Long? = null
        if (useCache) {
            cache[entityId]?.let {
                current = it.image
                lastFetchAt = it.fetchedAtMillis
                emit(CameraSnapshot(it.image))
            }
        }
        while (true) {
            lastFetchAt?.let { previous ->
                val wait = previous + intervalMillis - clock()
                if (wait > 0) delay(wait)
            }
            val fetchedAt = clock()
            lastFetchAt = fetchedAt

            val result = try {
                source.fetchSnapshot(entityId, widthPx.takeIf { it > 0 })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            val bytes = result.getOrNull()
            val decoded = if (bytes != null && bytes.isNotEmpty()) decode(bytes, widthPx, heightPx) else null

            if (decoded != null) {
                current = decoded
                if (useCache) cache.put(entityId, CachedImage(decoded, fetchedAt))
                emit(CameraSnapshot(decoded))
            } else {
                val message = result.exceptionOrNull()?.message
                    ?: if (bytes == null || bytes.isEmpty()) "Empty snapshot" else "Could not decode snapshot"
                emit(CameraSnapshot(current, message))
            }
        }
    }.distinctUntilChanged()

    private suspend fun decode(bytes: ByteArray, widthPx: Int, heightPx: Int): T? = try {
        withContext(decodeDispatcher) { decoder.decode(bytes, widthPx, heightPx) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
