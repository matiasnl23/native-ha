package com.matiasnl.hakiosk

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import com.matiasnl.hakiosk.camera.CameraModule
import com.matiasnl.hakiosk.data.config.AndroidConfigBackupFiles
import com.matiasnl.hakiosk.data.config.ConfigBackupAppVersion
import com.matiasnl.hakiosk.data.config.ConfigBackupFiles
import com.matiasnl.hakiosk.data.config.ConfigBackupRepository
import com.matiasnl.hakiosk.data.dashboard.DashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.DashboardModule
import com.matiasnl.hakiosk.data.dashboard.DashboardViewPreferencesStore
import com.matiasnl.hakiosk.data.display.DisplayModule
import com.matiasnl.hakiosk.data.display.DisplayPreferencesStore
import com.matiasnl.hakiosk.data.device.DeviceModule
import com.matiasnl.hakiosk.data.device.MqttConfigStore
import com.matiasnl.hakiosk.data.device.MqttRemoteControl
import com.matiasnl.hakiosk.data.device.RemoteControlBridge
import com.matiasnl.hakiosk.data.ha.HaConfigStore
import com.matiasnl.hakiosk.data.ha.HaModule
import com.matiasnl.hakiosk.data.ha.HaRepository
import com.matiasnl.hakiosk.data.ha.camera.HaCameraSource

/** Manual dependency container (no DI framework, keeps startup and RAM low). One instance per process. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val ha = HaModule(context.applicationContext)
    private val dashboard = DashboardModule(context.applicationContext)
    private val device = DeviceModule(context.applicationContext)
    private val display = DisplayModule(context.applicationContext)

    val haConfigStore: HaConfigStore get() = ha.configStore
    val haRepository: HaRepository get() = ha.repository
    val haCameraSource: HaCameraSource get() = ha.cameraSource
    val dashboardLayoutStore: DashboardLayoutStore get() = dashboard.layoutStore
    val dashboardViewPreferencesStore: DashboardViewPreferencesStore get() = dashboard.viewPreferencesStore
    val camera: CameraModule by lazy { CameraModule(context.applicationContext, ha.cameraSource) }
    val remoteControlBridge: RemoteControlBridge get() = device.remoteControlBridge
    val mqttConfigStore: MqttConfigStore get() = device.mqttConfigStore
    val mqttRemoteControl: MqttRemoteControl get() = device.mqttRemoteControl
    val displayPreferencesStore: DisplayPreferencesStore get() = display.preferencesStore

    val configBackupFiles: ConfigBackupFiles by lazy { AndroidConfigBackupFiles(appContext) }

    /** Reads from and writes to every store above: the config export/import spans all of them. */
    val configBackupRepository: ConfigBackupRepository by lazy {
        ConfigBackupRepository(
            haConfigStore = ha.configStore,
            dashboardLayoutStore = dashboard.layoutStore,
            viewPreferencesStore = dashboard.viewPreferencesStore,
            displayPreferencesStore = display.preferencesStore,
            mqttConfigStore = device.mqttConfigStore,
            appVersion = appVersion(),
        )
    }

    /** Stamped into every exported file so a future version can tell where it came from. */
    private fun appVersion(): ConfigBackupAppVersion = try {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        ConfigBackupAppVersion(
            versionName = info.versionName ?: UNKNOWN_VERSION,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
        )
    } catch (_: PackageManager.NameNotFoundException) {
        ConfigBackupAppVersion(UNKNOWN_VERSION, 0)
    }

    private companion object {
        const val UNKNOWN_VERSION = "unknown"
    }
}
