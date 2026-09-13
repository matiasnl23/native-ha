package com.matiasnl.hakiosk.data.dashboard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Wires dashboard persistence. Owned by the android-ui work stream. */
class DashboardModule(private val context: Context) {
    val configStore: DashboardConfigStore by lazy { DataStoreDashboardConfigStore(context) }
}

class InMemoryDashboardConfigStore(initial: List<DashboardTile> = emptyList()) : DashboardConfigStore {
    private val _tiles = MutableStateFlow(initial)
    override val tiles: StateFlow<List<DashboardTile>> = _tiles.asStateFlow()

    override suspend fun setTiles(tiles: List<DashboardTile>) {
        _tiles.value = tiles
    }
}
