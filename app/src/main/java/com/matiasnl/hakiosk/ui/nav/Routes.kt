package com.matiasnl.hakiosk.ui.nav

import kotlinx.serialization.Serializable

/** Setup screen: base URL + token, connection test, save/disconnect. */
@Serializable
object SetupRoute

/** Main dashboard grid. */
@Serializable
object DashboardRoute

/**
 * Full-screen focus view of one camera. [label] is the dashboard tile's name override, if any; [stream]
 * is the tile's go2rtc stream for full screen, or null for Home Assistant's own stream.
 */
@Serializable
data class CameraRoute(val entityId: String, val label: String? = null, val stream: String? = null)

/** MQTT broker settings for remote control from Home Assistant. */
@Serializable
object RemoteControlRoute

/** Export/import of the whole configuration to a file. */
@Serializable
object ConfigBackupRoute
