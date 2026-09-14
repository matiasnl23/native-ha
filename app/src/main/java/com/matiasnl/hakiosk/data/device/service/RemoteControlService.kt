package com.matiasnl.hakiosk.data.device.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.matiasnl.hakiosk.HaKioskApp
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.device.MqttConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process, and with it the MQTT connection owned by
 * `MqttConnectionManager`, alive 24/7 while the activity is stopped. It doesn't own the connection: it
 * only shows its state in a low-importance notification and, when the system restarts it after killing
 * the process (START_STICKY), starts the connection again.
 *
 * Type `specialUse`: no other type fits a persistent connection to a self-hosted broker without a time
 * limit (`dataSync` is capped at 6 h/day on Android 15+). The app is sideloaded, so Play's review of
 * the subtype doesn't apply.
 */
class RemoteControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inForeground = false

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val remoteControl = (application as HaKioskApp).container.mqttRemoteControl
        if (!inForeground) {
            ensureChannel()
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(remoteControl.connectionState.value),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        0
                    },
                )
            } catch (e: RuntimeException) {
                // E.g. ForegroundServiceStartNotAllowedException when restarted from the background.
                Log.w(TAG, "Could not enter the foreground: ${e.javaClass.simpleName}")
                stopSelf()
                return START_NOT_STICKY
            }
            inForeground = true
            scope.launch {
                remoteControl.connectionState
                    .map { textFor(it) }
                    .distinctUntilChanged()
                    .collect { notificationManager().notify(NOTIFICATION_ID, buildNotification(it)) }
            }
        }
        // No-op when already running; after a system restart of the process this reconnects.
        remoteControl.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.remote_control_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.remote_control_channel_description)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun textFor(state: MqttConnectionState): Int = when (state) {
        MqttConnectionState.Connected -> R.string.remote_control_state_connected
        MqttConnectionState.Connecting -> R.string.remote_control_state_connecting
        is MqttConnectionState.Disconnected -> R.string.remote_control_state_disconnected
        is MqttConnectionState.AuthFailed -> R.string.remote_control_state_auth_failed
        MqttConnectionState.Disabled -> R.string.remote_control_state_disabled
    }

    private fun buildNotification(state: MqttConnectionState): Notification = buildNotification(textFor(state))

    private fun buildNotification(textRes: Int): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_remote_control)
            .setContentTitle(getString(R.string.remote_control_notification_title))
            .setContentText(getString(textRes))
            .setContentIntent(launch)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "RemoteControlService"
        private const val CHANNEL_ID = "remote_control"
        private const val NOTIFICATION_ID = 1001

        /** True between onCreate and onDestroy. Read by [AndroidRemoteControlServiceController]. */
        @Volatile
        var running: Boolean = false
            private set
    }
}
