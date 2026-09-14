package com.matiasnl.hakiosk.data.device.discovery

import com.matiasnl.hakiosk.data.device.mqtt.MqttTopics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryPayloadsTest {
    private val topics = MqttTopics(DEVICE_ID)
    private val device = DiscoveryDevice(deviceId = DEVICE_ID, deviceName = "Tablet cocina", model = "SM-T500", appVersion = "1.0")

    private fun parse(payload: String): JsonObject = Json.parseToJsonElement(payload).jsonObject

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun switchConfigHasStateAndCommandTopicsAndOnOffPayloads() {
        val config = parse(DiscoveryPayloads.switchConfig(topics, device))

        assertEquals("Pantalla", config.str("name"))
        assertEquals("hakiosk_${DEVICE_ID}_screen", config.str("unique_id"))
        assertEquals("hakiosk/$DEVICE_ID/availability", config.str("availability_topic"))
        assertEquals("hakiosk/$DEVICE_ID/screen/state", config.str("state_topic"))
        assertEquals("hakiosk/$DEVICE_ID/screen/set", config.str("command_topic"))
        assertEquals("ON", config.str("payload_on"))
        assertEquals("OFF", config.str("payload_off"))
        assertDeviceAndOrigin(config)
    }

    @Test
    fun brightnessNumberConfigIsAZeroToHundredPercentSlider() {
        val config = parse(
            DiscoveryPayloads.numberConfig(
                DeviceEntityKey.BRIGHTNESS, topics, device, min = 0, max = 100, step = 1, unitOfMeasurement = "%", mode = "slider",
            ),
        )

        assertEquals("Brillo", config.str("name"))
        assertEquals("hakiosk_${DEVICE_ID}_brightness", config.str("unique_id"))
        assertEquals("hakiosk/$DEVICE_ID/availability", config.str("availability_topic"))
        assertEquals("hakiosk/$DEVICE_ID/brightness/state", config.str("state_topic"))
        assertEquals("hakiosk/$DEVICE_ID/brightness/set", config.str("command_topic"))
        assertEquals(0, config.getValue("min").jsonPrimitive.content.toInt())
        assertEquals(100, config.getValue("max").jsonPrimitive.content.toInt())
        assertEquals(1, config.getValue("step").jsonPrimitive.content.toInt())
        assertEquals("%", config.str("unit_of_measurement"))
        assertEquals("slider", config.str("mode"))
    }

    @Test
    fun screenOffTimeoutNumberConfigIsMinutesInBoxMode() {
        val config = parse(
            DiscoveryPayloads.numberConfig(
                DeviceEntityKey.SCREEN_OFF_TIMEOUT, topics, device, min = 0, max = 240, step = 1, unitOfMeasurement = "min", mode = "box",
            ),
        )

        assertEquals("Apagar pantalla tras", config.str("name"))
        assertEquals(240, config.getValue("max").jsonPrimitive.content.toInt())
        assertEquals("min", config.str("unit_of_measurement"))
        assertEquals("box", config.str("mode"))
    }

    @Test
    fun cameraCloseAfterNumberConfigIsSecondsInBoxMode() {
        val config = parse(
            DiscoveryPayloads.numberConfig(
                DeviceEntityKey.CAMERA_CLOSE_AFTER, topics, device, min = 0, max = 600, step = 1, unitOfMeasurement = "s", mode = "box",
            ),
        )

        assertEquals("Cerrar cámara tras", config.str("name"))
        assertEquals(600, config.getValue("max").jsonPrimitive.content.toInt())
        assertEquals("s", config.str("unit_of_measurement"))
    }

    @Test
    fun selectConfigCarriesTheOptionsInOrder() {
        val config = parse(DiscoveryPayloads.selectConfig(DeviceEntityKey.VIEW, topics, device, listOf("Cocina", "Living")))

        assertEquals("Vista", config.str("name"))
        assertEquals("hakiosk/$DEVICE_ID/view/state", config.str("state_topic"))
        assertEquals("hakiosk/$DEVICE_ID/view/set", config.str("command_topic"))
        assertEquals(listOf("Cocina", "Living"), config.getValue("options").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun cameraSelectConfigIncludesNinguna() {
        val options = listOf(NO_CAMERA_OPTION, "Frente")
        val config = parse(DiscoveryPayloads.selectConfig(DeviceEntityKey.CAMERA, topics, device, options))

        assertEquals("Cámara en pantalla", config.str("name"))
        assertEquals(options, config.getValue("options").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun buttonConfigHasOnlyACommandTopicAndNoState() {
        val config = parse(DiscoveryPayloads.buttonConfig(DeviceEntityKey.RELOAD, topics, device))

        assertEquals("Recargar", config.str("name"))
        assertEquals("hakiosk/$DEVICE_ID/reload/set", config.str("command_topic"))
        assertFalse(config.containsKey("state_topic"))
    }

    @Test
    fun mainViewButtonConfig() {
        val config = parse(DiscoveryPayloads.buttonConfig(DeviceEntityKey.MAIN_VIEW, topics, device))

        assertEquals("Volver a la vista principal", config.str("name"))
        assertEquals("hakiosk/$DEVICE_ID/main_view/set", config.str("command_topic"))
    }

    @Test
    fun batterySensorConfigHasDeviceClassUnitAndStateClass() {
        val config = parse(
            DiscoveryPayloads.sensorConfig(
                DeviceEntityKey.BATTERY, topics, device, deviceClass = "battery", unitOfMeasurement = "%", stateClass = "measurement",
            ),
        )

        assertEquals("Batería", config.str("name"))
        assertEquals("hakiosk_${DEVICE_ID}_battery", config.str("unique_id"))
        assertEquals("hakiosk/$DEVICE_ID/availability", config.str("availability_topic"))
        assertEquals("hakiosk/$DEVICE_ID/battery/state", config.str("state_topic"))
        assertEquals("battery", config.str("device_class"))
        assertEquals("%", config.str("unit_of_measurement"))
        assertEquals("measurement", config.str("state_class"))
        assertFalse(config.containsKey("command_topic"))
    }

    @Test
    fun currentViewSensorConfigHasNoDeviceClass() {
        val config = parse(DiscoveryPayloads.sensorConfig(DeviceEntityKey.CURRENT_VIEW, topics, device))

        assertEquals("Vista actual", config.str("name"))
        assertFalse(config.containsKey("device_class"))
        assertFalse(config.containsKey("unit_of_measurement"))
        assertFalse(config.containsKey("state_class"))
    }

    @Test
    fun lastInteractionSensorConfigHasTimestampDeviceClass() {
        val config = parse(DiscoveryPayloads.sensorConfig(DeviceEntityKey.LAST_INTERACTION, topics, device, deviceClass = "timestamp"))

        assertEquals("Última interacción", config.str("name"))
        assertEquals("timestamp", config.str("device_class"))
    }

    @Test
    fun chargingBinarySensorConfigHasDeviceClassAndOnOffPayloads() {
        val config = parse(DiscoveryPayloads.binarySensorConfig(DeviceEntityKey.CHARGING, topics, device, deviceClass = "battery_charging"))

        assertEquals("Cargando", config.str("name"))
        assertEquals("hakiosk/$DEVICE_ID/charging/state", config.str("state_topic"))
        assertEquals("battery_charging", config.str("device_class"))
        assertEquals("ON", config.str("payload_on"))
        assertEquals("OFF", config.str("payload_off"))
    }

    @Test
    fun everyEntityUsesTheDistinctUniqueId() {
        val ids = DeviceEntityKey.entries.map { it.uniqueId(DEVICE_ID) }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("hakiosk_${DEVICE_ID}_") })
    }

    private fun assertDeviceAndOrigin(config: JsonObject) {
        val deviceBlock = config.getValue("device").jsonObject
        assertEquals(listOf("hakiosk_$DEVICE_ID"), deviceBlock.getValue("identifiers").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Tablet cocina", deviceBlock.str("name"))
        assertEquals("HA Kiosk", deviceBlock.str("manufacturer"))
        assertEquals("SM-T500", deviceBlock.str("model"))
        assertEquals("1.0", deviceBlock.str("sw_version"))

        val origin = config.getValue("origin").jsonObject
        assertEquals("HA Kiosk", origin.str("name"))
        assertEquals("1.0", origin.str("sw_version"))
        assertNull(config["platform"])
    }

    private companion object {
        const val DEVICE_ID = "0123456789abcdef"
    }
}
