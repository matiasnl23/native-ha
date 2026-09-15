package com.matiasnl.hakiosk.ui.camera

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.camera.thumbnail.CameraSnapshotRepository
import com.matiasnl.hakiosk.camera.thumbnail.THUMBNAIL_REFRESH_INTERVAL_MS
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/**
 * Periodically refreshed snapshot of a camera, sized to where it's laid out. Polls only while
 * [active] (e.g. the tile is inside the viewport), in composition, and while the lifecycle is at
 * least STARTED. When it becomes inactive the last image stays on screen.
 */
@Composable
fun CameraThumbnail(
    entityId: String,
    snapshots: CameraSnapshotRepository<ImageBitmap>,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    refreshIntervalMillis: Long = THUMBNAIL_REFRESH_INTERVAL_MS,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val images = remember(entityId, size, active, refreshIntervalMillis) {
        if (!active || size.width <= 0 || size.height <= 0) {
            emptyFlow()
        } else {
            snapshots.snapshots(entityId, size.width, size.height, refreshIntervalMillis, useCache = true)
                .map { it.image }
        }
    }
    val image by images.collectAsStateWithLifecycle(initialValue = remember(entityId) { snapshots.cachedImage(entityId) })

    CameraThumbnailContent(image = image, modifier = modifier.onSizeChanged { size = it })
}

@Composable
fun CameraThumbnailContent(image: ImageBitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.camera_thumbnail_description),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = stringResource(R.string.camera_thumbnail_placeholder),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(widthDp = 180, heightDp = 120)
@Composable
private fun CameraThumbnailPlaceholderPreview() {
    HAKioskTheme {
        CameraThumbnailContent(image = null, modifier = Modifier.size(180.dp, 120.dp))
    }
}

@Preview(widthDp = 180, heightDp = 120)
@Composable
private fun CameraThumbnailImagePreview() {
    HAKioskTheme {
        CameraThumbnailContent(image = ImageBitmap(160, 90), modifier = Modifier.size(180.dp, 120.dp))
    }
}
