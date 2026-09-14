package com.matiasnl.hakiosk.data.ha

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Home Assistant server the kiosk connects to. [baseUrl] has no trailing slash, e.g. `http://192.168.1.50:8123`. */
data class HaServerConfig(
    val baseUrl: String,
    val token: String,
)

/** Last known state of a single Home Assistant entity. Only the latest state is kept in memory. */
data class HaEntity(
    val entityId: String,
    val state: String,
    val attributes: JsonObject,
    val lastChanged: String,
) {
    /** Domain part of the id, e.g. `light` for `light.kitchen`. */
    val domain: String get() = entityId.substringBefore('.')

    val friendlyName: String
        get() = (attributes["friendly_name"] as? JsonPrimitive)?.contentOrNull ?: entityId

    val icon: String? get() = (attributes["icon"] as? JsonPrimitive)?.contentOrNull

    val unitOfMeasurement: String?
        get() = (attributes["unit_of_measurement"] as? JsonPrimitive)?.contentOrNull

    val isUnavailable: Boolean get() = state == "unavailable" || state == "unknown"
}

data class HaFloor(
    val floorId: String,
    val name: String,
    /** Ordering hint set by the user in HA (0 = ground floor, negative = basement). */
    val level: Int?,
)

data class HaArea(
    val areaId: String,
    val name: String,
    val floorId: String?,
)

/** Floors, areas and the area each entity belongs to, as configured in Home Assistant. */
data class HaRegistry(
    /** Sorted by level (nulls last), then name. */
    val floors: List<HaFloor> = emptyList(),
    /** Sorted by name. */
    val areas: List<HaArea> = emptyList(),
    /** entityId -> areaId. The entity's own area wins; otherwise its device's area. Unassigned entities are absent. */
    val entityAreas: Map<String, String> = emptyMap(),
)

sealed interface HaConnectionState {
    /** No server configured, or [HaRepository.stop] was called. */
    data object Idle : HaConnectionState
    data object Connecting : HaConnectionState
    data object Connected : HaConnectionState

    /** Token rejected by the server. No automatic retry until the config changes. */
    data class AuthFailed(val message: String) : HaConnectionState

    /** Connection lost or unreachable; the repository retries automatically after [retryInMillis]. */
    data class Disconnected(val message: String, val retryInMillis: Long) : HaConnectionState
}

sealed interface HaConnectionTestResult {
    data class Success(val haVersion: String) : HaConnectionTestResult
    data object InvalidToken : HaConnectionTestResult
    data class Unreachable(val message: String) : HaConnectionTestResult
}
