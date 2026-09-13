package com.matiasnl.hakiosk.data.dashboard

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** One button of the dashboard grid. Position is the index in [DashboardConfigStore.tiles]. */
@Serializable
data class DashboardTile(
    val entityId: String,
    /** Overrides the entity friendly name when not null. */
    val label: String? = null,
)

interface DashboardConfigStore {
    val tiles: Flow<List<DashboardTile>>

    suspend fun setTiles(tiles: List<DashboardTile>)
}
