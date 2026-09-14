package com.matiasnl.hakiosk.camera.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Decodes a JPEG snapshot for display at roughly [targetWidthPx] x [targetHeightPx]. Null means "no image". */
fun interface SnapshotDecoder<T : Any> {
    fun decode(bytes: ByteArray, targetWidthPx: Int, targetHeightPx: Int): T?
}

/**
 * Largest power-of-two sample size that keeps both dimensions at or above the target (the image
 * still covers the target when cropped, without decoding more pixels than needed). HA often ignores
 * the requested width, so a 4K frame for a 300 px tile decodes at 1/8 instead of full size.
 */
fun calculateInSampleSize(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
    var sampleSize = 1
    while (sourceWidth / (sampleSize * 2) >= targetWidth && sourceHeight / (sampleSize * 2) >= targetHeight) {
        sampleSize *= 2
    }
    return sampleSize
}

/** BitmapFactory decoder: reads the bounds first, then decodes subsampled as RGB_565 (half the RAM of ARGB_8888; JPEGs have no alpha). */
class BitmapSnapshotDecoder : SnapshotDecoder<ImageBitmap> {
    override fun decode(bytes: ByteArray, targetWidthPx: Int, targetHeightPx: Int): ImageBitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetWidthPx, targetHeightPx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
}

/** Bytes an [ImageBitmap] really holds, for sizing the memory cache. */
fun ImageBitmap.byteCount(): Long = asAndroidBitmap().allocationByteCount.toLong()
