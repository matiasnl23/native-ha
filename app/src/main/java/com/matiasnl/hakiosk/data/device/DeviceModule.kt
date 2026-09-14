package com.matiasnl.hakiosk.data.device

import android.content.Context
import com.matiasnl.hakiosk.data.device.fake.FakeMqttRemoteControl
import com.matiasnl.hakiosk.data.device.fake.InMemoryMqttConfigStore

/** Wires remote control. The MQTT parts are owned by the device-control work stream. One instance per process. */
class DeviceModule(@Suppress("unused") private val context: Context) {
    val remoteControlBridge: RemoteControlBridge by lazy { InMemoryRemoteControlBridge() }

    // Fakes until the MQTT implementation lands.
    val mqttConfigStore: MqttConfigStore by lazy { InMemoryMqttConfigStore() }
    val mqttRemoteControl: MqttRemoteControl by lazy { FakeMqttRemoteControl() }
}
