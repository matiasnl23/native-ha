package com.matiasnl.hakiosk.data.update.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.matiasnl.hakiosk.HaKioskApp

/**
 * Runs once right after the app updates itself, because the install kills the app's process and nothing
 * it was doing survives: the MQTT connection to Home Assistant would stay down until somebody touched
 * the tablet.
 *
 * **Limitation, on purpose.** This only brings the background work back. It does **not** put the
 * dashboard back on screen: Android 10 blocks activity starts from the background, so a kiosk that
 * updates while nobody is watching comes back with the launcher in front until the activity is started
 * again. Starting the foreground service from here isn't guaranteed either (Android 12+ restricts that
 * too) — the implementation swallows the failure and the next foreground start picks it up. To be
 * verified on the real tablet; nothing here should be promised to the user until then.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? HaKioskApp ?: return
        Log.i(TAG, "Updated: restarting background work")
        runCatching { app.container.postUpdateRestart.restartBackgroundWork() }
            .onFailure { Log.w(TAG, "Could not restart after the update: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = "Update"
    }
}
