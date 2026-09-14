package com.matiasnl.hakiosk.data.dashboard

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Choices for the kiosk "return to the first view after inactivity" setting, in minutes. 0 = disabled. */
val INACTIVITY_RETURN_OPTIONS: List<Int> = listOf(0, 1, 5, 15)

/**
 * UI state of the dashboard that must survive restarts but is not part of the layout itself (so it
 * never lives in the [DashboardLayout] JSON, and writing it never re-emits the layout).
 */
data class DashboardViewPreferences(
    /** Id of the view the dashboard last settled on, or null if none was recorded yet. May be stale. */
    val lastViewId: String? = null,
    /** Minutes without touches before returning to the first view; 0 disables it. One of [INACTIVITY_RETURN_OPTIONS]. */
    val inactivityReturnMinutes: Int = 0,
)

interface DashboardViewPreferencesStore {
    val preferences: Flow<DashboardViewPreferences>

    suspend fun setLastViewId(viewId: String)

    /** Values outside [INACTIVITY_RETURN_OPTIONS] are stored as 0 (disabled). */
    suspend fun setInactivityReturnMinutes(minutes: Int)
}

/**
 * The view to open: [lastViewId] if it still exists in [layout], otherwise the first view. Null only
 * for a layout without views (which the layout invariant rules out).
 */
fun resolveViewId(layout: DashboardLayout, lastViewId: String?): String? =
    layout.views.firstOrNull { it.id == lastViewId }?.id ?: layout.views.firstOrNull()?.id

internal fun sanitizeInactivityMinutes(minutes: Int): Int = if (minutes in INACTIVITY_RETURN_OPTIONS) minutes else 0

private val LAST_VIEW_ID_KEY = stringPreferencesKey("last_view_id")
private val INACTIVITY_RETURN_MINUTES_KEY = intPreferencesKey("inactivity_return_minutes")

/**
 * [DashboardViewPreferencesStore] on a DataStore Preferences file of its own (see [DashboardModule]),
 * separate from the layout's `dashboard_config` file. An unreadable file falls back to defaults.
 */
class DataStoreDashboardViewPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : DashboardViewPreferencesStore {

    override val preferences: Flow<DashboardViewPreferences> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs ->
            DashboardViewPreferences(
                lastViewId = prefs[LAST_VIEW_ID_KEY],
                inactivityReturnMinutes = sanitizeInactivityMinutes(prefs[INACTIVITY_RETURN_MINUTES_KEY] ?: 0),
            )
        }

    override suspend fun setLastViewId(viewId: String) {
        dataStore.edit { it[LAST_VIEW_ID_KEY] = viewId }
    }

    override suspend fun setInactivityReturnMinutes(minutes: Int) {
        dataStore.edit { it[INACTIVITY_RETURN_MINUTES_KEY] = sanitizeInactivityMinutes(minutes) }
    }
}

/** In-memory [DashboardViewPreferencesStore] for previews and tests. */
class InMemoryDashboardViewPreferencesStore(
    initial: DashboardViewPreferences = DashboardViewPreferences(),
) : DashboardViewPreferencesStore {
    private val _preferences = MutableStateFlow(initial)
    override val preferences: StateFlow<DashboardViewPreferences> = _preferences.asStateFlow()

    /** Number of writes so far, for tests asserting writes are debounced. */
    var writes = 0
        private set

    override suspend fun setLastViewId(viewId: String) {
        writes++
        _preferences.update { it.copy(lastViewId = viewId) }
    }

    override suspend fun setInactivityReturnMinutes(minutes: Int) {
        writes++
        _preferences.update { it.copy(inactivityReturnMinutes = sanitizeInactivityMinutes(minutes)) }
    }
}
