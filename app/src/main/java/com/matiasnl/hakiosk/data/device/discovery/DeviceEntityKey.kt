package com.matiasnl.hakiosk.data.device.discovery

import com.matiasnl.hakiosk.data.device.mqtt.MqttTopics

/**
 * One entity the tablet publishes to Home Assistant via MQTT Discovery (see `docs/MVP3-MQTT.md`).
 *
 * [objectId] is the entity's slug, used as the topic segment (`hakiosk/<deviceId>/<objectId>/state`
 * and `.../set`) and as the Discovery topic's `object_id`. [component] is the Home Assistant MQTT
 * platform (`switch`, `number`, `select`, `button`, `sensor`, `binary_sensor`). [displayName] is the
 * entity name shown in Home Assistant, in Spanish per the app's UI language convention.
 */
enum class DeviceEntityKey(val objectId: String, val component: String, val displayName: String) {
    SCREEN("screen", "switch", "Pantalla"),
    BRIGHTNESS("brightness", "number", "Brillo"),
    SCREEN_OFF_TIMEOUT("screen_off_timeout", "number", "Apagar pantalla tras"),
    VIEW("view", "select", "Vista"),
    MAIN_VIEW("main_view", "button", "Volver a la vista principal"),
    CAMERA("camera", "select", "Cámara en pantalla"),
    CAMERA_CLOSE_AFTER("camera_close_after", "number", "Cerrar cámara tras"),
    RELOAD("reload", "button", "Recargar"),
    BATTERY("battery", "sensor", "Batería"),
    CHARGING("charging", "binary_sensor", "Cargando"),
    CURRENT_VIEW("current_view", "sensor", "Vista actual"),
    LAST_INTERACTION("last_interaction", "sensor", "Última interacción"),
    ;

    /** `hakiosk_<deviceId>_<objectId>`. */
    fun uniqueId(deviceId: String): String = "hakiosk_${deviceId}_$objectId"
}

/** `hakiosk/<deviceId>/<objectId>/state`. Not published for [DeviceEntityKey.MAIN_VIEW]/[DeviceEntityKey.RELOAD] (stateless buttons). */
fun MqttTopics.stateTopic(entity: DeviceEntityKey): String = topic("${entity.objectId}/state")

/** `hakiosk/<deviceId>/<objectId>/set`. */
fun MqttTopics.setTopic(entity: DeviceEntityKey): String = topic("${entity.objectId}/set")

/** `homeassistant/<component>/<deviceId>/<objectId>/config`. */
fun MqttTopics.discoveryConfigTopic(entity: DeviceEntityKey): String = discoveryConfigTopic(entity.component, entity.objectId)
