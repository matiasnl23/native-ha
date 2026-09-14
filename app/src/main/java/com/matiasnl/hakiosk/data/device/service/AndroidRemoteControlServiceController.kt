package com.matiasnl.hakiosk.data.device.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.matiasnl.hakiosk.data.device.mqtt.RemoteControlServiceController

/**
 * Starts/stops [RemoteControlService]. Starting a foreground service is only allowed while the app is in
 * the foreground (Android 12+ throws `ForegroundServiceStartNotAllowedException`, an
 * [IllegalStateException]); `MqttRemoteControl.start()` is called from the activity, so that's the
 * normal path. From the background the failure is logged and swallowed: the connection keeps running
 * while the process lives, and the next start from the activity brings the service up.
 */
class AndroidRemoteControlServiceController(context: Context) : RemoteControlServiceController {
    private val appContext = context.applicationContext
    private val intent get() = Intent(appContext, RemoteControlService::class.java)

    override fun startService() {
        if (RemoteControlService.running) return
        try {
            ContextCompat.startForegroundService(appContext, intent)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Remote control service not started from the background: ${e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            Log.w(TAG, "Remote control service not allowed: ${e.javaClass.simpleName}")
        }
    }

    override fun stopService() {
        // Unconditional: a start may be pending (requested but onCreate not run yet).
        appContext.stopService(intent)
    }

    private companion object {
        const val TAG = "RemoteControlService"
    }
}
