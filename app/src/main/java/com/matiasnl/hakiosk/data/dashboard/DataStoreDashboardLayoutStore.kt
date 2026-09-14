package com.matiasnl.hakiosk.data.dashboard

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dashboardDataStore by preferencesDataStore(name = "dashboard_config")

private val LAYOUT_KEY = stringPreferencesKey("dashboard_layout_json")

/** Pre-multi-view format: a bare JSON array of `{entityId, label}`, order = tile order in the grid. */
private val LEGACY_TILES_KEY = stringPreferencesKey("dashboard_tiles_json")

/**
 * Persists the dashboard layout as JSON in DataStore Preferences. See [DashboardLayoutJsonMapper] for
 * the format, its version and the migration from [LEGACY_TILES_KEY]; this class stays thin, wiring
 * DataStore to that pure mapper.
 */
class DataStoreDashboardLayoutStore(
    private val context: Context,
    private val idProvider: DashboardIdProvider = UuidDashboardIdProvider,
) : DashboardLayoutStore {

    override val layout: Flow<DashboardLayout> = context.dashboardDataStore.data.map { prefs ->
        val decoded = DashboardLayoutJsonMapper.decode(prefs[LAYOUT_KEY], prefs[LEGACY_TILES_KEY], idProvider)
        if (decoded.shouldPersist) persistMigration(decoded.layout)
        decoded.layout
    }

    override suspend fun update(transform: (DashboardLayout) -> DashboardLayout) {
        context.dashboardDataStore.edit { prefs ->
            val current = DashboardLayoutJsonMapper.decode(prefs[LAYOUT_KEY], prefs[LEGACY_TILES_KEY], idProvider).layout
            prefs[LAYOUT_KEY] = DashboardLayoutJsonMapper.encode(transform(current))
            prefs.remove(LEGACY_TILES_KEY)
        }
    }

    /**
     * Writes the migrated legacy layout once. Re-checks for [LAYOUT_KEY] inside the same atomic edit
     * so two collectors racing on first launch (e.g. the dashboard and the editor both starting up)
     * can't each migrate the legacy data independently and overwrite one another with two different
     * freshly-generated id sets.
     */
    private suspend fun persistMigration(migrated: DashboardLayout) {
        context.dashboardDataStore.edit { prefs ->
            if (prefs[LAYOUT_KEY] != null) return@edit
            prefs[LAYOUT_KEY] = DashboardLayoutJsonMapper.encode(migrated)
            prefs.remove(LEGACY_TILES_KEY)
        }
    }
}
