package com.matiasnl.hakiosk.ui.nav

import kotlinx.serialization.Serializable

/** Setup screen: base URL + token, connection test, save/disconnect. */
@Serializable
object SetupRoute

/** Main dashboard grid. */
@Serializable
object DashboardRoute

/** Dashboard tile editor: pick which entities show up and in what order. */
@Serializable
object EditorRoute
