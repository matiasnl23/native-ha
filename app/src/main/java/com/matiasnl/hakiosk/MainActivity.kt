package com.matiasnl.hakiosk

import android.os.Bundle
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
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The repository observes config changes itself; we only drive its lifecycle here.
        container.haRepository.start()
    }

    override fun onStop() {
        // The camera screen stops its own session; this is a safety net so no WebRTC survives in background.
        container.camera.webRtcSessionManager.stop()
        container.haRepository.stop()
        super.onStop()
    }
}
