package com.matiasnl.hakiosk.data.device.fake

import com.matiasnl.hakiosk.data.device.MqttConfig
import com.matiasnl.hakiosk.data.device.MqttConfigStore
import com.matiasnl.hakiosk.data.device.MqttConnectionState
import com.matiasnl.hakiosk.data.device.MqttRemoteControl
import com.matiasnl.hakiosk.data.device.MqttTestResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryMqttConfigStore(initial: MqttConfig? = null) : MqttConfigStore {
    private val _config = MutableStateFlow(initial)
    override val config: StateFlow<MqttConfig?> = _config.asStateFlow()

    override suspend fun save(config: MqttConfig) {
        _config.value = config
    }

    override suspend fun clear() {
        _config.value = null
    }
}

/** MQTT remote control for previews, tests and running the app before the real client exists. */
class FakeMqttRemoteControl(
    private val testResult: MqttTestResult = MqttTestResult.Success,
) : MqttRemoteControl {
    private val _connectionState = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disabled)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    override fun start() {
        _connectionState.value = MqttConnectionState.Connected
    }

    override fun stop() {
        _connectionState.value = MqttConnectionState.Disabled
    }

    override suspend fun testConnection(config: MqttConfig): MqttTestResult = testResult
}
