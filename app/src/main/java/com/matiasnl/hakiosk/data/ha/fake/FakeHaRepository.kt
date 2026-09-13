package com.matiasnl.hakiosk.data.ha.fake

import com.matiasnl.hakiosk.data.ha.HaConfigStore
import com.matiasnl.hakiosk.data.ha.HaConnectionState
import com.matiasnl.hakiosk.data.ha.HaConnectionTestResult
import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.HaRepository
import com.matiasnl.hakiosk.data.ha.HaServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** In-memory repository for previews, UI tests and running the app before the real client exists. */
class FakeHaRepository(
    initialEntities: List<HaEntity> = sampleEntities(),
) : HaRepository {
    private val _connectionState = MutableStateFlow<HaConnectionState>(HaConnectionState.Idle)
    override val connectionState: StateFlow<HaConnectionState> = _connectionState.asStateFlow()

    private val _entities = MutableStateFlow(initialEntities.associateBy { it.entityId })
    override val entities: StateFlow<Map<String, HaEntity>> = _entities.asStateFlow()

    override fun start() {
        _connectionState.value = HaConnectionState.Connected
    }

    override fun stop() {
        _connectionState.value = HaConnectionState.Idle
    }

    override suspend fun callService(
        domain: String,
        service: String,
        entityId: String,
        data: JsonObject,
    ): Result<Unit> {
        val newState = when (service) {
            "turn_on" -> "on"
            "turn_off" -> "off"
            "toggle" -> if (_entities.value[entityId]?.state == "on") "off" else "on"
            else -> return Result.success(Unit)
        }
        _entities.update { current ->
            val entity = current[entityId] ?: return@update current
            current + (entityId to entity.copy(state = newState))
        }
        return Result.success(Unit)
    }

    override suspend fun testConnection(config: HaServerConfig): HaConnectionTestResult =
        HaConnectionTestResult.Success(haVersion = "fake")
}

class InMemoryHaConfigStore(initial: HaServerConfig? = null) : HaConfigStore {
    private val _config = MutableStateFlow(initial)
    override val config: StateFlow<HaServerConfig?> = _config.asStateFlow()

    override suspend fun save(config: HaServerConfig) {
        _config.value = config
    }

    override suspend fun clear() {
        _config.value = null
    }
}

fun sampleEntities(): List<HaEntity> = listOf(
    fakeEntity("light.living_room", "on", "Living room"),
    fakeEntity("light.kitchen", "off", "Kitchen"),
    fakeEntity("switch.coffee_maker", "off", "Coffee maker"),
    fakeEntity("sensor.outdoor_temperature", "18.5", "Outdoor temperature", unit = "°C"),
    fakeEntity("scene.movie_night", "scening", "Movie night"),
    fakeEntity("camera.front_door", "idle", "Front door"),
)

private fun fakeEntity(id: String, state: String, name: String, unit: String? = null) = HaEntity(
    entityId = id,
    state = state,
    attributes = JsonObject(
        buildMap {
            put("friendly_name", JsonPrimitive(name))
            unit?.let { put("unit_of_measurement", JsonPrimitive(it)) }
        }
    ),
    lastChanged = "2026-01-01T00:00:00+00:00",
)
