package com.matiasnl.hakiosk.data.device.mqtt

/** Starts and stops the foreground service that keeps the process (and the MQTT connection) alive. */
interface RemoteControlServiceController {
    /**
     * Starts the service if it isn't running. Must not throw: starting a foreground service from the
     * background is restricted on Android 12+, in which case the connection still runs while the
     * process lives and the next call from the foreground activity starts the service.
     */
    fun startService()

    fun stopService()
}
