package com.matiasnl.hakiosk.data.device

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Persists the broker config. The password must be stored encrypted and never logged. */
interface MqttConfigStore {
    val config: Flow<MqttConfig?>

    suspend fun save(config: MqttConfig)

    suspend fun clear()
}

/** The MQTT side of remote control: connection, Discovery and state publishing. */
interface MqttRemoteControl {
    val connectionState: StateFlow<MqttConnectionState>

    /** Starts (or keeps) the connection using the stored config; reconnects when it changes. Idempotent. */
    fun start()

    fun stop()

    /** Validates a config without storing it (used by the settings screen). */
    suspend fun testConnection(config: MqttConfig): MqttTestResult
}

/**
 * Process-wide bridge between the MQTT layer and the UI layer: Home Assistant commands flow to the UI
 * through [commands]; the UI reports what it shows through [updateUiState] for the MQTT layer to
 * publish.
 */
interface RemoteControlBridge {
    /** Commands for the UI. Not replayed: a command sent while nothing collects is dropped. */
    val commands: SharedFlow<RemoteCommand>

    val uiState: StateFlow<DeviceUiState>

    /** Called by the MQTT layer when Home Assistant sends a command. */
    fun dispatch(command: RemoteCommand)

    /** Called by the UI layer whenever what it shows changes. */
    fun updateUiState(transform: (DeviceUiState) -> DeviceUiState)
}

/** The real [RemoteControlBridge]: in-memory, one instance per process. */
class InMemoryRemoteControlBridge : RemoteControlBridge {
    private val _commands = MutableSharedFlow<RemoteCommand>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val commands: SharedFlow<RemoteCommand> = _commands.asSharedFlow()

    private val _uiState = MutableStateFlow(DeviceUiState())
    override val uiState: StateFlow<DeviceUiState> = _uiState.asStateFlow()

    override fun dispatch(command: RemoteCommand) {
        _commands.tryEmit(command)
    }

    override fun updateUiState(transform: (DeviceUiState) -> DeviceUiState) {
        _uiState.update(transform)
    }
}
