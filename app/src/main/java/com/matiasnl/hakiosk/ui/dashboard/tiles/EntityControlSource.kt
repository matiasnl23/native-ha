package com.matiasnl.hakiosk.ui.dashboard.tiles

import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.HaRepository
import com.matiasnl.hakiosk.data.ha.domain.ServiceCall
import com.matiasnl.hakiosk.data.ha.domain.call
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The slice of Home Assistant a details panel needs: one entity's live state and service calls on it.
 * Panels only collect [entity] while they're open.
 */
interface EntityControlSource {
    /** The entity's latest state, or null while it's unknown. Emits only when its instance changes. */
    fun entity(entityId: String): Flow<HaEntity?>

    suspend fun call(entityId: String, call: ServiceCall): Result<Unit>
}

/** [EntityControlSource] backed by the app's [HaRepository]. */
class HaRepositoryControlSource(private val repository: HaRepository) : EntityControlSource {
    // The repository replaces only the entities that changed, so identity is enough to skip the rest.
    override fun entity(entityId: String): Flow<HaEntity?> =
        repository.entities.map { it[entityId] }.distinctUntilChanged { old, new -> old === new }

    override suspend fun call(entityId: String, call: ServiceCall): Result<Unit> = repository.call(entityId, call)
}
