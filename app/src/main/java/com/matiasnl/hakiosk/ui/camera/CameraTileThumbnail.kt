package com.matiasnl.hakiosk.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.matiasnl.hakiosk.camera.CameraModule
import com.matiasnl.hakiosk.camera.thumbnail.THUMBNAIL_REFRESH_INTERVAL_MS
import com.matiasnl.hakiosk.data.dashboard.CameraTileOptions

/**
 * A dashboard camera tile's picture, per its [options]: snapshots at the tile's own interval, or live
 * video while [active] when [CameraTileOptions.thumbnailLive] is set. Contract placeholder: snapshots only.
 */
@Composable
fun CameraTileThumbnail(
    entityId: String,
    options: CameraTileOptions?,
    active: Boolean,
    camera: CameraModule,
    modifier: Modifier = Modifier,
) {
    CameraThumbnail(
        entityId = entityId,
        snapshots = camera.snapshots,
        modifier = modifier,
        active = active,
        refreshIntervalMillis = thumbnailRefreshIntervalMillis(options),
    )
}

/** The tile's snapshot interval in milliseconds: its own setting, never below the minimum, or the app default. */
fun thumbnailRefreshIntervalMillis(options: CameraTileOptions?): Long =
    options?.thumbnailRefreshSeconds
        ?.coerceAtLeast(CameraTileOptions.MIN_THUMBNAIL_REFRESH_SECONDS)
        ?.let { it * 1_000L }
        ?: THUMBNAIL_REFRESH_INTERVAL_MS
