package com.matiasnl.hakiosk.ui.nav

import kotlinx.serialization.Serializable

/** Setup screen: base URL + token, connection test, save/disconnect. */
@Serializable
object SetupRoute

/** Main dashboard grid. */
@Serializable
object DashboardRoute

/** Full-screen focus view of one camera. [label] is the dashboard tile's name override, if any. */
@Serializable
data class CameraRoute(val entityId: String, val label: String? = null)

/** Dashboard tile editor: pick which entities show up and in what order. Only reachable outside edit mode. */
@Serializable
object EditorRoute
