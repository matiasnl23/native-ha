package com.matiasnl.hakiosk.data.update

/**
 * What has to be started again once the app has updated itself. Installing kills the app's process
 * (every install does by default; `setDontKillApp` is API 34 and only covers splits), so nothing the
 * app was running survives the update.
 *
 * Declared here as an interface of its own so the update layer doesn't reach into the MQTT layer: the
 * app container supplies the implementation.
 */
fun interface PostUpdateRestart {
    /**
     * Called from the `ACTION_MY_PACKAGE_REPLACED` receiver, on the main thread and with the app in the
     * background. Must be cheap and must tolerate failing: starting a foreground service from the
     * background is not always allowed.
     */
    fun restartBackgroundWork()
}
