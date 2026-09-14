package com.matiasnl.hakiosk.data.device

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.matiasnl.hakiosk.data.device.discovery.AndroidBatterySource
import com.matiasnl.hakiosk.data.device.discovery.RemoteControlPublisher
import com.matiasnl.hakiosk.data.device.mqtt.ConnectivityNetworkMonitor
import com.matiasnl.hakiosk.data.device.mqtt.HiveMqClientFactory
import com.matiasnl.hakiosk.data.device.mqtt.MqttConnectionManager
import com.matiasnl.hakiosk.data.device.mqtt.MqttMessaging
import com.matiasnl.hakiosk.data.device.service.AndroidRemoteControlServiceController
import com.matiasnl.hakiosk.data.device.store.DataStoreMqttConfigStore
import com.matiasnl.hakiosk.data.device.store.MqttDeviceIdProvider
import com.matiasnl.hakiosk.data.ha.store.KeystoreTokenCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Wires remote control. The MQTT parts are owned by the device-control work stream. One instance per process. */
class DeviceModule(context: Context) {
    private val appContext = context.applicationContext

    val remoteControlBridge: RemoteControlBridge by lazy { InMemoryRemoteControlBridge() }

    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(
            produceFile = { appContext.preferencesDataStoreFile(DataStoreMqttConfigStore.DATASTORE_NAME) },
        )
    }

    private val configStore by lazy {
        DataStoreMqttConfigStore(dataStore, KeystoreTokenCipher(DataStoreMqttConfigStore.PASSWORD_KEY_ALIAS))
    }

    // Process-wide: the connection outlives activities (kept alive by RemoteControlService).
    private val connectionManager by lazy {
        MqttConnectionManager(
            configStore = configStore,
            deviceIdProvider = configStore,
            clientFactory = HiveMqClientFactory(),
            networkMonitor = ConnectivityNetworkMonitor(appContext.getSystemService(ConnectivityManager::class.java)),
            serviceController = AndroidRemoteControlServiceController(appContext),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            log = { Log.i(TAG, it) },
        )
    }

    val mqttConfigStore: MqttConfigStore get() = configStore
    val mqttRemoteControl: MqttRemoteControl get() = connectionManager

    /** Stable id of this install (MQTT topics, client id, Home Assistant unique ids). For stage 2. */
    val mqttDeviceIdProvider: MqttDeviceIdProvider get() = configStore

    /** Publish/subscribe over the managed connection. For stage 2 (Discovery, commands, state). */
    val mqttMessaging: MqttMessaging get() = connectionManager

    /**
     * Discovery/state publishing and command handling. Runs for the life of the process, independent
     * of any activity: referencing it here (rather than leaving it unused-and-never-built) is what
     * forces this whole lazy chain — config store, connection manager, publisher — to start as soon as
     * [DeviceModule] is constructed, not on first UI access.
     */
    private val remoteControlPublisher: RemoteControlPublisher by lazy {
        RemoteControlPublisher(
            messaging = connectionManager,
            bridge = remoteControlBridge,
            configStore = configStore,
            batterySource = AndroidBatterySource(appContext),
            deviceModel = Build.MODEL,
            appVersion = appVersionName(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            log = { Log.i(TAG, it) },
        )
    }

    init {
        remoteControlPublisher.start()
    }

    private fun appVersionName(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: UNKNOWN_VERSION
    } catch (e: PackageManager.NameNotFoundException) {
        UNKNOWN_VERSION
    }

    private companion object {
        const val TAG = "RemoteControl"
        const val UNKNOWN_VERSION = "unknown"
    }
}
