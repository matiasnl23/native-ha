package com.matiasnl.hakiosk

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.matiasnl.hakiosk.ui.nav.HaKioskNavGraph
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as HaKioskApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The kiosk runs 24/7; there is no Device Owner yet (see README.md) so the "screen off" remote
        // command is only an in-app overlay (see ScreenControlViewModel) rather than a real display
        // power-off. Without this flag the system's own timeout would turn the real display off under
        // that overlay, and a remote "on" command wouldn't be able to wake it back up.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            HAKioskTheme {
                HaKioskNavGraph(
                    haConfigStore = container.haConfigStore,
                    haRepository = container.haRepository,
                    dashboardLayoutStore = container.dashboardLayoutStore,
                    dashboardViewPreferencesStore = container.dashboardViewPreferencesStore,
                    cameraModule = container.camera,
                    mqttConfigStore = container.mqttConfigStore,
                    mqttRemoteControl = container.mqttRemoteControl,
                    remoteControlBridge = container.remoteControlBridge,
                    displayPreferencesStore = container.displayPreferencesStore,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The repository observes config changes itself; we only drive its lifecycle here.
        container.haRepository.start()
        // Idempotent and a no-op without a stored broker config. Never stopped in onStop: remote
        // control must keep working while the activity is stopped (a foreground service keeps it
        // alive); saving or clearing the broker config also calls start() again (harmless).
        container.mqttRemoteControl.start()
    }

    override fun onStop() {
        // The camera screen stops its own session; this is a safety net so no WebRTC survives in background.
        container.camera.webRtcSessionManager.stop()
        container.haRepository.stop()
        super.onStop()
    }
}
