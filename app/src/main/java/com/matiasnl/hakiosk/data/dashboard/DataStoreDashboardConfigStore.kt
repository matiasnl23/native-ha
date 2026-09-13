package com.matiasnl.hakiosk.data.dashboard

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dashboardDataStore by preferencesDataStore(name = "dashboard_config")

private val TILES_KEY = stringPreferencesKey("dashboard_tiles_json")

/**
 * Persists dashboard tiles as a JSON array in DataStore Preferences. The JSON array order is the
 * tile order shown in the grid.
 */
class DataStoreDashboardConfigStore(private val context: Context) : DashboardConfigStore {
    private val json = Json { ignoreUnknownKeys = true }

    override val tiles: Flow<List<DashboardTile>> =
        context.dashboardDataStore.data.map { prefs ->
            val raw = prefs[TILES_KEY] ?: return@map emptyList()
            runCatching { json.decodeFromString<List<DashboardTile>>(raw) }.getOrDefault(emptyList())
        }

    override suspend fun setTiles(tiles: List<DashboardTile>) {
        val encoded = json.encodeToString(tiles)
        context.dashboardDataStore.edit { prefs ->
            prefs[TILES_KEY] = encoded
        }
    }
}
