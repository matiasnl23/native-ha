package com.matiasnl.hakiosk.data.device.discovery

import com.matiasnl.hakiosk.data.device.CameraOption
import com.matiasnl.hakiosk.data.device.DeviceUiState
import com.matiasnl.hakiosk.data.device.MqttConfig
import com.matiasnl.hakiosk.data.device.MqttConfigStore
import com.matiasnl.hakiosk.data.device.MqttConnectionState
import com.matiasnl.hakiosk.data.device.RemoteCommand
import com.matiasnl.hakiosk.data.device.RemoteControlBridge
import com.matiasnl.hakiosk.data.device.ViewOption
import com.matiasnl.hakiosk.data.device.mqtt.MqttMessaging
import com.matiasnl.hakiosk.data.device.mqtt.MqttTopics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Publishes MQTT Discovery configs and entity states for the tablet, and turns incoming commands
 * (per-entity `.../set` topics and the JSON `.../command` topic) into [RemoteCommand]s dispatched
 * through [bridge]. Runs for the whole life of the process — independent of any activity — as long as
 * [scope] is alive; there is nothing to start it from the UI, unlike [messaging] itself which only
 * opens a connection once [com.matiasnl.hakiosk.data.device.MqttRemoteControl.start] is called.
 *
 * Discovery configs and full state are (re)published every time [MqttMessaging.connectionState]
 * becomes [MqttConnectionState.Connected] (fresh broker, or reconnect after a possible restart that
 * dropped retained messages). Between reconnects, only entity states that actually changed are
 * published, debounced by [DEBOUNCE_MILLIS] so a burst of UI updates doesn't turn into a publish
 * storm. View/camera `select` options are republished (as a config update) whenever the dashboard's
 * views or cameras change.
 *
 * When remote control is disabled (the user clears the broker config), this publisher does nothing
 * special: the device already appears "unavailable" in Home Assistant through the LWT (stage 1), and
 * removing the entities themselves is left to the user (deleting the device removes them all).
 */
class RemoteControlPublisher(
    private val messaging: MqttMessaging,
    private val bridge: RemoteControlBridge,
    private val configStore: MqttConfigStore,
    private val batterySource: BatterySource,
    private val deviceModel: String,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var viewCatalog: OptionCatalog = OptionCatalog.EMPTY

    @Volatile private var cameraCatalog: OptionCatalog = OptionCatalog.EMPTY

    private var lastPublishedStates: Map<DeviceEntityKey, String> = emptyMap()
    private var lastPublishedViewOptions: List<String>? = null
    private var lastPublishedCameraOptions: List<String>? = null

    /** Launches the publishing and command coroutines on [scope]. Call once. */
    fun start() {
        scope.launch { bridge.uiState.collect(::updateCatalogs) }
        scope.launch { publishLoop() }
        scope.launch { handleEntityCommands() }
        scope.launch { handleJsonCommands() }
    }

    private sealed interface Signal {
        data class Connection(val state: MqttConnectionState) : Signal
        data class Data(val ui: DeviceUiState, val battery: BatteryState, val config: MqttConfig?) : Signal
    }

    @OptIn(FlowPreview::class)
    private suspend fun publishLoop() {
        val topics = messaging.topics()
        // Read the current battery/config once so the very first Connected transition (which can
        // race the debounced combine below) always resyncs with real values, not placeholders.
        var latestUi = bridge.uiState.value
        var latestBattery = batterySource.state.first()
        var latestConfig = configStore.config.first()
        var wasConnected = false

        val signals = merge(
            messaging.connectionState.map { Signal.Connection(it) },
            combine(bridge.uiState, batterySource.state, configStore.config, Signal::Data).debounce(DEBOUNCE_MILLIS),
        )
        signals.collect { signal ->
            when (signal) {
                is Signal.Connection -> {
                    val isConnected = signal.state is MqttConnectionState.Connected
                    // viewCatalog/cameraCatalog are kept current by their own, non-debounced collector
                    // (started in start()), so they're read here rather than derived from latestUi,
                    // which can still be lagging behind the debounced Data signal below.
                    if (isConnected && !wasConnected) fullResync(topics, latestConfig, latestUi, latestBattery)
                    wasConnected = isConnected
                }
                is Signal.Data -> {
                    latestUi = signal.ui
                    latestBattery = signal.battery
                    latestConfig = signal.config
                    if (wasConnected) publishChanges(topics, signal.config, signal.ui, signal.battery)
                }
            }
        }
    }

    private fun updateCatalogs(ui: DeviceUiState) {
        viewCatalog = OptionCatalog.of(ui.views, ViewOption::id, ViewOption::name)
        cameraCatalog = OptionCatalog.of(ui.cameras, CameraOption::entityId, CameraOption::name)
    }

    private suspend fun fullResync(topics: MqttTopics, config: MqttConfig?, ui: DeviceUiState, battery: BatteryState) {
        val device = deviceOf(topics, config) ?: return
        val viewOptions = viewCatalog.options
        val cameraOptions = cameraOptionsFor(cameraCatalog)
        discoveryConfigs(topics, device, viewOptions, cameraOptions).forEach { (entity, payload) ->
            messaging.publish(topics.discoveryConfigTopic(entity), payload, retain = true)
        }
        lastPublishedViewOptions = viewOptions
        lastPublishedCameraOptions = cameraOptions

        val states = statePayloads(ui, battery)
        states.forEach { (entity, payload) -> messaging.publish(topics.stateTopic(entity), payload, retain = true) }
        lastPublishedStates = states
    }

    private suspend fun publishChanges(topics: MqttTopics, config: MqttConfig?, ui: DeviceUiState, battery: BatteryState) {
        val device = deviceOf(topics, config) ?: return
        republishChangedOptions(topics, device)

        val states = statePayloads(ui, battery)
        states.forEach { (entity, payload) ->
            if (lastPublishedStates[entity] != payload && messaging.publish(topics.stateTopic(entity), payload, retain = true)) {
                lastPublishedStates = lastPublishedStates + (entity to payload)
            }
        }
    }

    private suspend fun republishChangedOptions(topics: MqttTopics, device: DiscoveryDevice) {
        val viewOptions = viewCatalog.options
        if (viewOptions != lastPublishedViewOptions) {
            val payload = DiscoveryPayloads.selectConfig(DeviceEntityKey.VIEW, topics, device, viewOptions)
            if (messaging.publish(topics.discoveryConfigTopic(DeviceEntityKey.VIEW), payload, retain = true)) {
                lastPublishedViewOptions = viewOptions
            }
        }
        val cameraOptions = cameraOptionsFor(cameraCatalog)
        if (cameraOptions != lastPublishedCameraOptions) {
            val payload = DiscoveryPayloads.selectConfig(DeviceEntityKey.CAMERA, topics, device, cameraOptions)
            if (messaging.publish(topics.discoveryConfigTopic(DeviceEntityKey.CAMERA), payload, retain = true)) {
                lastPublishedCameraOptions = cameraOptions
            }
        }
    }

    private fun deviceOf(topics: MqttTopics, config: MqttConfig?): DiscoveryDevice? {
        val cfg = config ?: return null
        val deviceName = cfg.deviceName.trim().ifBlank { DEFAULT_DEVICE_NAME }
        return DiscoveryDevice(topics.deviceId, deviceName, deviceModel, appVersion)
    }

    private fun cameraOptionsFor(catalog: OptionCatalog): List<String> = listOf(NO_CAMERA_OPTION) + catalog.options

    private fun statePayloads(ui: DeviceUiState, battery: BatteryState): Map<DeviceEntityKey, String> {
        val viewName = viewCatalog.nameFor(ui.currentViewId) ?: DiscoveryPayloads.PAYLOAD_NONE
        val cameraName = ui.openCameraEntityId?.let(cameraCatalog::nameFor) ?: NO_CAMERA_OPTION
        return mapOf(
            DeviceEntityKey.SCREEN to onOffPayload(ui.screenOn),
            DeviceEntityKey.BRIGHTNESS to ui.brightnessPercent.toString(),
            DeviceEntityKey.SCREEN_OFF_TIMEOUT to ui.screenOffTimeoutMinutes.toString(),
            DeviceEntityKey.VIEW to viewName,
            DeviceEntityKey.CAMERA to cameraName,
            DeviceEntityKey.CAMERA_CLOSE_AFTER to ui.cameraCloseAfterSeconds.toString(),
            DeviceEntityKey.BATTERY to battery.percent.toString(),
            DeviceEntityKey.CHARGING to onOffPayload(battery.charging),
            DeviceEntityKey.CURRENT_VIEW to viewName,
            DeviceEntityKey.LAST_INTERACTION to (ui.lastInteractionEpochMillis?.let(::isoTimestamp) ?: DiscoveryPayloads.PAYLOAD_NONE),
        )
    }

    private fun discoveryConfigs(
        topics: MqttTopics,
        device: DiscoveryDevice,
        viewOptions: List<String>,
        cameraOptions: List<String>,
    ): Map<DeviceEntityKey, String> = mapOf(
        DeviceEntityKey.SCREEN to DiscoveryPayloads.switchConfig(topics, device),
        DeviceEntityKey.BRIGHTNESS to DiscoveryPayloads.numberConfig(
            DeviceEntityKey.BRIGHTNESS, topics, device, min = 0, max = 100, step = 1, unitOfMeasurement = "%", mode = "slider",
        ),
        DeviceEntityKey.SCREEN_OFF_TIMEOUT to DiscoveryPayloads.numberConfig(
            DeviceEntityKey.SCREEN_OFF_TIMEOUT, topics, device, min = 0, max = 240, step = 1, unitOfMeasurement = "min", mode = "box",
        ),
        DeviceEntityKey.VIEW to DiscoveryPayloads.selectConfig(DeviceEntityKey.VIEW, topics, device, viewOptions),
        DeviceEntityKey.MAIN_VIEW to DiscoveryPayloads.buttonConfig(DeviceEntityKey.MAIN_VIEW, topics, device),
        DeviceEntityKey.CAMERA to DiscoveryPayloads.selectConfig(DeviceEntityKey.CAMERA, topics, device, cameraOptions),
        DeviceEntityKey.CAMERA_CLOSE_AFTER to DiscoveryPayloads.numberConfig(
            DeviceEntityKey.CAMERA_CLOSE_AFTER, topics, device, min = 0, max = 600, step = 1, unitOfMeasurement = "s", mode = "box",
        ),
        DeviceEntityKey.RELOAD to DiscoveryPayloads.buttonConfig(DeviceEntityKey.RELOAD, topics, device),
        DeviceEntityKey.BATTERY to DiscoveryPayloads.sensorConfig(
            DeviceEntityKey.BATTERY, topics, device, deviceClass = "battery", unitOfMeasurement = "%", stateClass = "measurement",
        ),
        DeviceEntityKey.CHARGING to DiscoveryPayloads.binarySensorConfig(DeviceEntityKey.CHARGING, topics, device, deviceClass = "battery_charging"),
        DeviceEntityKey.CURRENT_VIEW to DiscoveryPayloads.sensorConfig(DeviceEntityKey.CURRENT_VIEW, topics, device),
        DeviceEntityKey.LAST_INTERACTION to DiscoveryPayloads.sensorConfig(DeviceEntityKey.LAST_INTERACTION, topics, device, deviceClass = "timestamp"),
    )

    // --- Commands -----------------------------------------------------------------------------

    private suspend fun handleEntityCommands() {
        val topics = messaging.topics()
        messaging.subscribe(topics.topic("+/set")).collect { message ->
            val objectId = entityObjectId(topics, message.topic) ?: return@collect
            val entity = DeviceEntityKey.entries.firstOrNull { it.objectId == objectId } ?: return@collect
            toCommand(entity, message.payloadText.trim())?.let(bridge::dispatch)
                ?: log("Ignored command for ${entity.objectId}")
        }
    }

    private fun entityObjectId(topics: MqttTopics, topic: String): String? {
        val prefix = "${topics.base}/"
        if (!topic.startsWith(prefix)) return null
        val parts = topic.removePrefix(prefix).split('/')
        return parts.takeIf { it.size == 2 && it[1] == "set" }?.get(0)
    }

    private fun toCommand(entity: DeviceEntityKey, payload: String): RemoteCommand? = when (entity) {
        DeviceEntityKey.SCREEN -> onOffOrNull(payload)?.let(RemoteCommand::SetScreenOn)
        DeviceEntityKey.BRIGHTNESS -> clampedIntOrNull(payload, 0, 100)?.let(RemoteCommand::SetBrightness)
        DeviceEntityKey.SCREEN_OFF_TIMEOUT -> clampedIntOrNull(payload, 0, 240)?.let(RemoteCommand::SetScreenOffTimeout)
        DeviceEntityKey.VIEW -> foundId(viewCatalog, payload)?.let(RemoteCommand::ShowView)
        DeviceEntityKey.MAIN_VIEW -> RemoteCommand.ShowMainView
        DeviceEntityKey.CAMERA -> if (payload == NO_CAMERA_OPTION) {
            RemoteCommand.CloseCamera
        } else {
            foundId(cameraCatalog, payload)?.let { RemoteCommand.OpenCamera(it, closeAfterSeconds = null) }
        }
        DeviceEntityKey.CAMERA_CLOSE_AFTER -> clampedIntOrNull(payload, 0, 600)?.let(RemoteCommand::SetCameraCloseAfter)
        DeviceEntityKey.RELOAD -> RemoteCommand.Reload
        DeviceEntityKey.BATTERY, DeviceEntityKey.CHARGING, DeviceEntityKey.CURRENT_VIEW, DeviceEntityKey.LAST_INTERACTION -> null
    }

    private suspend fun handleJsonCommands() {
        val topics = messaging.topics()
        messaging.subscribe(topics.topic("command")).collect { message ->
            val command = JsonCommandParser.parse(message.payloadText)
            if (command == null) {
                log("Ignored malformed or unknown JSON command")
                return@collect
            }
            toCommand(command)?.let(bridge::dispatch) ?: log("Ignored JSON command: unresolved reference")
        }
    }

    private fun toCommand(command: JsonCommand): RemoteCommand? = when (command) {
        is JsonCommand.OpenCamera -> RemoteCommand.OpenCamera(command.entityId, command.closeAfterSeconds?.coerceIn(0, 600))
        JsonCommand.CloseCamera -> RemoteCommand.CloseCamera
        is JsonCommand.ShowView -> resolveViewIdOrName(command.viewIdOrName)?.let(RemoteCommand::ShowView)
        JsonCommand.MainView -> RemoteCommand.ShowMainView
        is JsonCommand.Screen -> RemoteCommand.SetScreenOn(command.on)
        is JsonCommand.Brightness -> RemoteCommand.SetBrightness(command.percent.coerceIn(0, 100))
        JsonCommand.Reload -> RemoteCommand.Reload
    }

    private fun resolveViewIdOrName(idOrName: String): String? =
        idOrName.takeIf(viewCatalog::hasId) ?: foundId(viewCatalog, idOrName)

    private fun foundId(catalog: OptionCatalog, name: String): String? =
        (catalog.resolve(name) as? OptionCatalog.Resolution.Found)?.id

    private fun onOffPayload(value: Boolean) = if (value) DiscoveryPayloads.PAYLOAD_ON else DiscoveryPayloads.PAYLOAD_OFF

    private fun onOffOrNull(payload: String): Boolean? = when (payload) {
        DiscoveryPayloads.PAYLOAD_ON -> true
        DiscoveryPayloads.PAYLOAD_OFF -> false
        else -> null
    }

    private fun clampedIntOrNull(payload: String, min: Int, max: Int): Int? =
        payload.toDoubleOrNull()?.roundToInt()?.coerceIn(min, max)

    private fun isoTimestamp(epochMillis: Long): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    companion object {
        const val DEBOUNCE_MILLIS = 500L
        private const val DEFAULT_DEVICE_NAME = "HA Kiosk"
    }
}
