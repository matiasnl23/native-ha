package com.matiasnl.hakiosk.ui.dashboard.tiles

import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.domain.ServiceCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Records calls; entities are pushed with [update]. [gate], when set, suspends calls until completed. */
class FakeEntityControlSource(vararg initial: HaEntity) : EntityControlSource {
    private val entities = MutableStateFlow(initial.associateBy { it.entityId })

    val calls = mutableListOf<Pair<String, ServiceCall>>()

    var result: Result<Unit> = Result.success(Unit)

    var gate: CompletableDeferred<Unit>? = null

    /** Collectors currently subscribed to any entity. */
    var activeSubscriptions = 0
        private set

    override fun entity(entityId: String): Flow<HaEntity?> = entities
        .map { it[entityId] }
        .distinctUntilChanged { old, new -> old === new }
        .onStart { activeSubscriptions++ }
        .onCompletion { activeSubscriptions-- }

    override suspend fun call(entityId: String, call: ServiceCall): Result<Unit> {
        calls += entityId to call
        gate?.await()
        return result
    }

    fun update(entity: HaEntity) = entities.update { it + (entity.entityId to entity) }
}

fun testEntity(entityId: String, state: String, attributes: String = "{}") = HaEntity(
    entityId = entityId,
    state = state,
    attributes = Json.parseToJsonElement(attributes) as JsonObject,
    lastChanged = "2026-09-14T00:00:00+00:00",
)
