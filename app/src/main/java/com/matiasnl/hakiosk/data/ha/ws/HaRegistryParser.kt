package com.matiasnl.hakiosk.data.ha.ws

import com.matiasnl.hakiosk.data.ha.HaArea
import com.matiasnl.hakiosk.data.ha.HaFloor
import com.matiasnl.hakiosk.data.ha.ws.HaProtocol.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Reduces Home Assistant registry `result` payloads to the compact models the app keeps.
 * Unknown fields are ignored and malformed entries skipped. Each function returns null when the
 * payload does not have the expected top-level shape, so the caller can keep its last good value.
 */
internal object HaRegistryParser {

    private val floorOrder: Comparator<HaFloor> =
        compareBy<HaFloor, Int?>(nullsLast(naturalOrder())) { it.level }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.floorId }

    private val areaOrder: Comparator<HaArea> =
        compareBy<HaArea, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.areaId }

    /** `config/floor_registry/list`: sorted by level (nulls last), then name. */
    fun parseFloors(result: JsonElement): List<HaFloor>? {
        val array = result as? JsonArray ?: return null
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val floorId = obj["floor_id"].stringOrNull() ?: return@mapNotNull null
            HaFloor(
                floorId = floorId,
                name = obj["name"].stringOrNull() ?: floorId,
                level = (obj["level"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull,
            )
        }.sortedWith(floorOrder)
    }

    /** `config/area_registry/list`: sorted by name. */
    fun parseAreas(result: JsonElement): List<HaArea>? {
        val array = result as? JsonArray ?: return null
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val areaId = obj["area_id"].stringOrNull() ?: return@mapNotNull null
            HaArea(
                areaId = areaId,
                name = obj["name"].stringOrNull() ?: areaId,
                floorId = obj["floor_id"].stringOrNull(),
            )
        }.sortedWith(areaOrder)
    }

    /** `config/device_registry/list` reduced to deviceId -> areaId; devices without an area are dropped. */
    fun parseDeviceAreas(result: JsonElement): Map<String, String>? {
        val array = result as? JsonArray ?: return null
        val deviceAreas = HashMap<String, String>()
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val deviceId = obj["id"].stringOrNull() ?: continue
            val areaId = obj["area_id"].stringOrNull() ?: continue
            deviceAreas[deviceId] = areaId
        }
        return deviceAreas
    }

    /**
     * entityId -> areaId from either `config/entity_registry/list_for_display`
     * (`{"entities":[{"ei","ai","di"}]}`) or the full `config/entity_registry/list` (array of
     * `entity_id`/`area_id`/`device_id`, disabled entities skipped). The entity's own area wins over
     * its device's; entities with neither are absent.
     */
    fun parseEntityAreas(result: JsonElement, deviceAreas: Map<String, String>): Map<String, String>? {
        val entries: JsonArray
        val idKey: String
        val areaKey: String
        val deviceKey: String
        when (result) {
            is JsonObject -> {
                entries = result["entities"] as? JsonArray ?: return null
                idKey = "ei"; areaKey = "ai"; deviceKey = "di"
            }
            is JsonArray -> {
                entries = result
                idKey = "entity_id"; areaKey = "area_id"; deviceKey = "device_id"
            }
            else -> return null
        }
        val entityAreas = HashMap<String, String>()
        for (element in entries) {
            val obj = element as? JsonObject ?: continue
            if (obj["disabled_by"].stringOrNull() != null) continue
            val entityId = obj[idKey].stringOrNull() ?: continue
            val areaId = obj[areaKey].stringOrNull()
                ?: obj[deviceKey].stringOrNull()?.let(deviceAreas::get)
                ?: continue
            entityAreas[entityId] = areaId
        }
        return entityAreas
    }
}
