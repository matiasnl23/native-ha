package com.matiasnl.hakiosk.ui.dashboard.tiles

import androidx.compose.runtime.staticCompositionLocalOf

/** Lists the go2rtc stream names a camera entity offers through Frigate, for the camera tile's stream pickers. */
typealias CameraStreamCatalog = suspend (entityId: String) -> Result<List<String>>

/** Provided by the navigation graph from the camera source; previews and tests get an empty list. */
val LocalCameraStreamCatalog = staticCompositionLocalOf<CameraStreamCatalog> { { Result.success(emptyList()) } }
