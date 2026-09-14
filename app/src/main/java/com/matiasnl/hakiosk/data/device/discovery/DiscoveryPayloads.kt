package com.matiasnl.hakiosk.data.device.discovery

import com.matiasnl.hakiosk.data.device.mqtt.MqttTopics
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Device metadata shared by every Discovery config this app publishes. */
data class DiscoveryDevice(val deviceId: String, val deviceName: String, val model: String, val appVersion: String)

/**
 * Builds MQTT Discovery `config` payloads, one topic per entity (per-component discovery), verified
 * against the `homeassistant/components/mqtt/` schemas in home-assistant/core (`switch.py`,
 * `number.py`, `select.py`, `button.py`, `sensor.py`, `binary_sensor.py`, `schemas.py`): full JSON
 * keys (no abbreviations), a shared `device` and `origin` block, and one `availability_topic` per
 * entity pointing at the LWT topic from stage 1. See `docs/MVP3-MQTT.md` for the version/rationale.
 */
object DiscoveryPayloads {
    const val PAYLOAD_ON = "ON"
    const val PAYLOAD_OFF = "OFF"

    /** Home Assistant's own sentinel for "no state yet" on a read-only sensor/select (`sensor.py`, `select.py`). */
    const val PAYLOAD_NONE = "None"

    private const val MANUFACTURER = "HA Kiosk"

    fun switchConfig(topics: MqttTopics, device: DiscoveryDevice): String =
        entityConfig(DeviceEntityKey.SCREEN, topics, device) {
            put("state_topic", topics.stateTopic(DeviceEntityKey.SCREEN))
            put("command_topic", topics.setTopic(DeviceEntityKey.SCREEN))
            put("payload_on", PAYLOAD_ON)
            put("payload_off", PAYLOAD_OFF)
        }

    fun numberConfig(
        entity: DeviceEntityKey,
        topics: MqttTopics,
        device: DiscoveryDevice,
        min: Int,
        max: Int,
        step: Int,
        unitOfMeasurement: String,
        mode: String,
    ): String = entityConfig(entity, topics, device) {
        put("state_topic", topics.stateTopic(entity))
        put("command_topic", topics.setTopic(entity))
        put("min", min)
        put("max", max)
        put("step", step)
        put("unit_of_measurement", unitOfMeasurement)
        put("mode", mode)
    }

    fun selectConfig(entity: DeviceEntityKey, topics: MqttTopics, device: DiscoveryDevice, options: List<String>): String =
        entityConfig(entity, topics, device) {
            put("state_topic", topics.stateTopic(entity))
            put("command_topic", topics.setTopic(entity))
            putJsonArray("options") { options.forEach { add(it) } }
        }

    fun buttonConfig(entity: DeviceEntityKey, topics: MqttTopics, device: DiscoveryDevice): String =
        entityConfig(entity, topics, device) {
            put("command_topic", topics.setTopic(entity))
        }

    fun sensorConfig(
        entity: DeviceEntityKey,
        topics: MqttTopics,
        device: DiscoveryDevice,
        deviceClass: String? = null,
        unitOfMeasurement: String? = null,
        stateClass: String? = null,
    ): String = entityConfig(entity, topics, device) {
        put("state_topic", topics.stateTopic(entity))
        deviceClass?.let { put("device_class", it) }
        unitOfMeasurement?.let { put("unit_of_measurement", it) }
        stateClass?.let { put("state_class", it) }
    }

    fun binarySensorConfig(entity: DeviceEntityKey, topics: MqttTopics, device: DiscoveryDevice, deviceClass: String): String =
        entityConfig(entity, topics, device) {
            put("state_topic", topics.stateTopic(entity))
            put("device_class", deviceClass)
            put("payload_on", PAYLOAD_ON)
            put("payload_off", PAYLOAD_OFF)
        }

    /** `hakiosk_<deviceId>` — shared by every entity's `device.identifiers`. */
    fun deviceIdentifier(deviceId: String): String = "hakiosk_$deviceId"

    private fun entityConfig(
        entity: DeviceEntityKey,
        topics: MqttTopics,
        device: DiscoveryDevice,
        extra: JsonObjectBuilder.() -> Unit,
    ): String = buildJsonObject {
        put("name", entity.displayName)
        put("unique_id", entity.uniqueId(device.deviceId))
        put("availability_topic", topics.availability)
        extra()
        putJsonObject("device") {
            putJsonArray("identifiers") { add(deviceIdentifier(device.deviceId)) }
            put("name", device.deviceName)
            put("manufacturer", MANUFACTURER)
            put("model", device.model)
            put("sw_version", device.appVersion)
        }
        putJsonObject("origin") {
            put("name", MANUFACTURER)
            put("sw_version", device.appVersion)
        }
    }.toString()
}
