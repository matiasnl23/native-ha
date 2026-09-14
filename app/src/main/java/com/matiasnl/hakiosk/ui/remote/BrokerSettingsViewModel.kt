package com.matiasnl.hakiosk.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.matiasnl.hakiosk.data.device.MqttConfig
import com.matiasnl.hakiosk.data.device.MqttConfigStore
import com.matiasnl.hakiosk.data.device.MqttConnectionState
import com.matiasnl.hakiosk.data.device.MqttRemoteControl
import com.matiasnl.hakiosk.data.device.MqttTestResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Shown in the device name field until a stored config (or the user) provides one. */
const val DEFAULT_MQTT_DEVICE_NAME = "HA Kiosk"

sealed interface BrokerTestStatus {
    data object Idle : BrokerTestStatus
    data object Testing : BrokerTestStatus
    data class Done(val result: MqttTestResult) : BrokerTestStatus
}

data class BrokerSettingsUiState(
    val host: String = "",
    val port: String = MqttConfig.DEFAULT_PORT.toString(),
    val username: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val useTls: Boolean = false,
    val deviceName: String = DEFAULT_MQTT_DEVICE_NAME,
    val hostError: Boolean = false,
    val portError: Boolean = false,
    val deviceNameError: Boolean = false,
    val testStatus: BrokerTestStatus = BrokerTestStatus.Idle,
    val connectionState: MqttConnectionState = MqttConnectionState.Disabled,
    /** True once a stored config was loaded, i.e. this is an edit rather than a first-time setup. */
    val isConfigured: Boolean = false,
    val notificationPermissionDenied: Boolean = false,
) {
    val canSave: Boolean
        get() = host.isNotBlank() && deviceName.isNotBlank() && (port.toIntOrNull()?.let { it in 1..65535 } == true)
}

/**
 * Drives the broker settings screen: editing the MQTT connection fields, testing the connection, and
 * saving (or clearing) the stored [MqttConfig]. Mirrors [com.matiasnl.hakiosk.ui.setup.SetupViewModel]'s
 * load-race handling: the stored config loads asynchronously and must not clobber fields the user
 * already started typing.
 */
class BrokerSettingsViewModel(
    private val mqttConfigStore: MqttConfigStore,
    private val mqttRemoteControl: MqttRemoteControl,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrokerSettingsUiState())
    val uiState: StateFlow<BrokerSettingsUiState> = _uiState.asStateFlow()

    private var fieldsTouched = false

    init {
        viewModelScope.launch {
            mqttConfigStore.config.first()?.let { config ->
                _uiState.update {
                    if (fieldsTouched) {
                        it.copy(isConfigured = true)
                    } else {
                        it.copy(
                            host = config.host,
                            port = config.port.toString(),
                            username = config.username.orEmpty(),
                            password = config.password.orEmpty(),
                            useTls = config.useTls,
                            deviceName = config.deviceName,
                            isConfigured = true,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            mqttRemoteControl.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
    }

    fun onHostChange(value: String) {
        fieldsTouched = true
        _uiState.update { it.copy(host = value, hostError = false, testStatus = BrokerTestStatus.Idle) }
    }

    fun onPortChange(value: String) {
        fieldsTouched = true
        val digitsOnly = value.filter { it.isDigit() }
        _uiState.update { it.copy(port = digitsOnly, portError = false, testStatus = BrokerTestStatus.Idle) }
    }

    fun onUsernameChange(value: String) {
        fieldsTouched = true
        _uiState.update { it.copy(username = value, testStatus = BrokerTestStatus.Idle) }
    }

    fun onPasswordChange(value: String) {
        fieldsTouched = true
        _uiState.update { it.copy(password = value, testStatus = BrokerTestStatus.Idle) }
    }

    fun togglePasswordVisibility() {
        _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    fun onDeviceNameChange(value: String) {
        fieldsTouched = true
        _uiState.update { it.copy(deviceName = value, deviceNameError = false, testStatus = BrokerTestStatus.Idle) }
    }

    /** Turning TLS on/off swaps the port to the other mode's default, but only while it's still that default. */
    fun onUseTlsChange(useTls: Boolean) {
        fieldsTouched = true
        _uiState.update { state ->
            val previousDefault = if (state.useTls) MqttConfig.DEFAULT_TLS_PORT else MqttConfig.DEFAULT_PORT
            val newDefault = if (useTls) MqttConfig.DEFAULT_TLS_PORT else MqttConfig.DEFAULT_PORT
            val port = if (state.port == previousDefault.toString()) newDefault.toString() else state.port
            state.copy(useTls = useTls, port = port, testStatus = BrokerTestStatus.Idle)
        }
    }

    fun testConnection() {
        val validated = validate(_uiState.value)
        _uiState.value = validated
        val config = buildConfig(validated) ?: return
        _uiState.update { it.copy(testStatus = BrokerTestStatus.Testing) }
        viewModelScope.launch {
            val result = mqttRemoteControl.testConnection(config)
            _uiState.update { it.copy(testStatus = BrokerTestStatus.Done(result)) }
        }
    }

    /** Persists the current fields and invokes [onSaved] once the store confirms the write. */
    fun save(onSaved: () -> Unit) {
        val validated = validate(_uiState.value)
        _uiState.value = validated
        val config = buildConfig(validated) ?: return
        viewModelScope.launch {
            mqttConfigStore.save(config)
            // The real implementation reconnects by itself on config changes; calling start() is harmless.
            mqttRemoteControl.start()
            onSaved()
        }
    }

    /** Clears the stored config and invokes [onCleared]. */
    fun clear(onCleared: () -> Unit) {
        viewModelScope.launch {
            mqttConfigStore.clear()
            mqttRemoteControl.start()
            onCleared()
        }
    }

    /** Called by the screen when the POST_NOTIFICATIONS request (API 33+) was denied. */
    fun onNotificationPermissionDenied() {
        _uiState.update { it.copy(notificationPermissionDenied = true) }
    }

    fun dismissNotificationPermissionNote() {
        _uiState.update { it.copy(notificationPermissionDenied = false) }
    }

    private fun validate(state: BrokerSettingsUiState): BrokerSettingsUiState {
        val port = state.port.toIntOrNull()
        return state.copy(
            hostError = state.host.isBlank(),
            portError = port == null || port !in 1..65535,
            deviceNameError = state.deviceName.isBlank(),
        )
    }

    private fun buildConfig(state: BrokerSettingsUiState): MqttConfig? {
        val port = state.port.toIntOrNull() ?: return null
        if (state.host.isBlank() || state.deviceName.isBlank() || port !in 1..65535) return null
        return MqttConfig(
            host = state.host.trim(),
            port = port,
            username = state.username.trim().ifBlank { null },
            password = state.password.ifEmpty { null },
            useTls = state.useTls,
            deviceName = state.deviceName.trim(),
        )
    }

    companion object {
        fun factory(mqttConfigStore: MqttConfigStore, mqttRemoteControl: MqttRemoteControl) = viewModelFactory {
            initializer { BrokerSettingsViewModel(mqttConfigStore, mqttRemoteControl) }
        }
    }
}
