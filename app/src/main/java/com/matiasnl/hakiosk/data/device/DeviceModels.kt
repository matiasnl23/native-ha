package com.matiasnl.hakiosk.data.device

/** MQTT broker the tablet connects to so Home Assistant can control it. */
data class MqttConfig(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val username: String? = null,
    /** Stored encrypted and never logged. */
    val password: String? = null,
    val useTls: Boolean = false,
    /** Name of the device shown in Home Assistant, e.g. "Tablet cocina". */
    val deviceName: String,
) {
    /** Redacts [password] so logging a config never leaks it. */
    override fun toString(): String =
        "MqttConfig(host=$host, port=$port, username=$username, password=${if (password == null) "null" else "***"}, " +
            "useTls=$useTls, deviceName=$deviceName)"

    companion object {
        const val DEFAULT_PORT = 1883
        const val DEFAULT_TLS_PORT = 8883
    }
}

sealed interface MqttConnectionState {
    /** No broker configured, or the remote control service isn't running. */
    data object Disabled : MqttConnectionState
    data object Connecting : MqttConnectionState
    data object Connected : MqttConnectionState

    /** Credentials rejected; no automatic retry until the config changes. */
    data class AuthFailed(val message: String) : MqttConnectionState

    /** Connection lost or unreachable; retried automatically after [retryInMillis]. */
    data class Disconnected(val message: String, val retryInMillis: Long) : MqttConnectionState
}

sealed interface MqttTestResult {
    data object Success : MqttTestResult
    data class AuthFailed(val message: String) : MqttTestResult
    data class Unreachable(val message: String) : MqttTestResult
}

/** A command Home Assistant sent to the tablet. Applied by the UI layer. */
sealed interface RemoteCommand {
    /** Turns the screen on (removes the "screen off" overlay) or off (shows it). */
    data class SetScreenOn(val on: Boolean) : RemoteCommand

    /** App window brightness, 0..100. */
    data class SetBrightness(val percent: Int) : RemoteCommand

    /** Minutes without touches before the screen turns off; 0 = never. */
    data class SetScreenOffTimeout(val minutes: Int) : RemoteCommand

    data class ShowView(val viewId: String) : RemoteCommand

    data object ShowMainView : RemoteCommand

    /** Opens [entityId] full screen; closes it by itself after [closeAfterSeconds] unless null or 0. */
    data class OpenCamera(val entityId: String, val closeAfterSeconds: Int?) : RemoteCommand

    data object CloseCamera : RemoteCommand

    /** Default auto-close for [OpenCamera] commands that don't specify one; 0 = don't close. */
    data class SetCameraCloseAfter(val seconds: Int) : RemoteCommand

    /** Reconnects to Home Assistant and reloads the dashboard. */
    data object Reload : RemoteCommand
}

data class ViewOption(val id: String, val name: String)

data class CameraOption(val entityId: String, val name: String)

/**
 * What the UI layer reports so the MQTT layer can publish it to Home Assistant (entity states and
 * select options). Battery and charging are read by the MQTT layer itself.
 */
data class DeviceUiState(
    val views: List<ViewOption> = emptyList(),
    val currentViewId: String? = null,
    /** Cameras present in the dashboard, offered by the "camera on screen" select. */
    val cameras: List<CameraOption> = emptyList(),
    /** Camera currently open full screen, or null. */
    val openCameraEntityId: String? = null,
    val screenOn: Boolean = true,
    val brightnessPercent: Int = 100,
    val screenOffTimeoutMinutes: Int = 0,
    val cameraCloseAfterSeconds: Int = DEFAULT_CAMERA_CLOSE_AFTER_SECONDS,
    /** Epoch millis of the last touch on the app, or null if none since start. */
    val lastInteractionEpochMillis: Long? = null,
) {
    companion object {
        const val DEFAULT_CAMERA_CLOSE_AFTER_SECONDS = 60
    }
}
