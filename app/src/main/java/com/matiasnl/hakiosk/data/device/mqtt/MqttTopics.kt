package com.matiasnl.hakiosk.data.device.mqtt

/**
 * Topic scheme of one tablet. Everything the app owns lives under `hakiosk/<deviceId>/`:
 *
 * - `hakiosk/<deviceId>/availability`: `online` (retained, published right after every connect) or
 *   `offline` (retained; the Last Will, and also published before a clean disconnect). Home
 *   Assistant entities point their `availability_topic` here (default `payload_available` /
 *   `payload_not_available` of HA's MQTT integration are exactly `online` / `offline`).
 * - Stage 2 adds, under the same base: `hakiosk/<deviceId>/<entity>/state` for states and
 *   `hakiosk/<deviceId>/<entity>/set` for commands, plus the JSON command topic
 *   `hakiosk/<deviceId>/command`. Discovery configs go to Home Assistant's own prefix
 *   (`homeassistant/<component>/<deviceId>/<object_id>/config`), not under [base].
 *
 * [deviceId] is 16 lowercase hex chars (see `MqttDeviceIdProvider`), so it never contains `/`, `+`
 * or `#`.
 */
class MqttTopics(val deviceId: String) {
    val base: String = "$ROOT/$deviceId"
    val availability: String = "$base/availability"

    /** `hakiosk/<deviceId>/<suffix>`. */
    fun topic(suffix: String): String = "$base/$suffix"

    val clientId: String = "$ROOT-$deviceId"

    /**
     * Per-component MQTT Discovery config topic for one entity of this device:
     * `homeassistant/<component>/<deviceId>/<objectId>/config`. Using [deviceId] as the topic's
     * `node_id` (instead of `object_id`) lets several entities share one device while keeping a
     * flat, predictable topic per entity; see `docs/MVP3-MQTT.md`.
     */
    fun discoveryConfigTopic(component: String, objectId: String): String =
        "$DISCOVERY_PREFIX/$component/$deviceId/$objectId/config"

    companion object {
        const val ROOT = "hakiosk"
        const val PAYLOAD_ONLINE = "online"
        const val PAYLOAD_OFFLINE = "offline"

        /** Home Assistant's default MQTT Discovery prefix. */
        const val DISCOVERY_PREFIX = "homeassistant"

        /**
         * MQTT 3.1.1 topic filter matching (section 4.7): `+` matches one level, a trailing `#`
         * matches any number of levels including the parent. Filters starting with a wildcard don't
         * match topics starting with `$`.
         */
        fun matches(filter: String, topic: String): Boolean {
            if (topic.startsWith("$") && (filter.startsWith("+") || filter.startsWith("#"))) return false
            val filterLevels = filter.split('/')
            val topicLevels = topic.split('/')
            for (i in filterLevels.indices) {
                val level = filterLevels[i]
                if (level == "#") return i == filterLevels.lastIndex
                if (i >= topicLevels.size) return false
                if (level != "+" && level != topicLevels[i]) return false
            }
            return filterLevels.size == topicLevels.size
        }
    }
}
