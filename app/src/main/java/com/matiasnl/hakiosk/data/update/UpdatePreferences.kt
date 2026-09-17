package com.matiasnl.hakiosk.data.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** A week: past this the setting is indistinguishable from "off" and just confuses the picker. */
const val MAX_CHECK_INTERVAL_HOURS = 24 * 7

/**
 * How often the tablet looks for a new release, persisted so a restart doesn't turn a kiosk into a
 * polling loop. Kept in a DataStore file of its own, like the other preference groups.
 */
data class UpdatePreferences(
    /** Hours between automatic checks; 0 = automatic checks off (manual and Home Assistant still work). */
    val checkIntervalHours: Int = DEFAULT_CHECK_INTERVAL_HOURS,
    /** When the last check finished, or null if it never ran. Drives when the next one is due. */
    val lastCheckEpochMillis: Long? = null,
)

interface UpdatePreferencesStore {
    val preferences: Flow<UpdatePreferences>

    /** Clamped to 0..[MAX_CHECK_INTERVAL_HOURS]; 0 disables automatic checks. */
    suspend fun setCheckIntervalHours(hours: Int)

    suspend fun setLastCheckEpochMillis(epochMillis: Long)
}

internal fun sanitizeCheckIntervalHours(hours: Int): Int = hours.coerceIn(0, MAX_CHECK_INTERVAL_HOURS)

private val CHECK_INTERVAL_HOURS_KEY = intPreferencesKey("check_interval_hours")
private val LAST_CHECK_EPOCH_MILLIS_KEY = longPreferencesKey("last_check_epoch_millis")

/** [UpdatePreferencesStore] on a DataStore Preferences file of its own. An unreadable file falls back to defaults. */
class DataStoreUpdatePreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : UpdatePreferencesStore {

    override val preferences: Flow<UpdatePreferences> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs ->
            UpdatePreferences(
                checkIntervalHours = sanitizeCheckIntervalHours(
                    prefs[CHECK_INTERVAL_HOURS_KEY] ?: DEFAULT_CHECK_INTERVAL_HOURS,
                ),
                // A clock that went backwards (or a corrupt value) reads as "never checked", which at
                // worst costs one extra check instead of blocking checks until the timestamp passes.
                lastCheckEpochMillis = prefs[LAST_CHECK_EPOCH_MILLIS_KEY]?.takeIf { it > 0 },
            )
        }

    override suspend fun setCheckIntervalHours(hours: Int) {
        dataStore.edit { it[CHECK_INTERVAL_HOURS_KEY] = sanitizeCheckIntervalHours(hours) }
    }

    override suspend fun setLastCheckEpochMillis(epochMillis: Long) {
        dataStore.edit { it[LAST_CHECK_EPOCH_MILLIS_KEY] = epochMillis }
    }

    companion object {
        const val DATASTORE_NAME = "update_prefs"
    }
}

/** In-memory [UpdatePreferencesStore] for previews and tests. */
class InMemoryUpdatePreferencesStore(
    initial: UpdatePreferences = UpdatePreferences(),
) : UpdatePreferencesStore {
    private val _preferences = MutableStateFlow(initial)
    override val preferences: StateFlow<UpdatePreferences> = _preferences.asStateFlow()

    override suspend fun setCheckIntervalHours(hours: Int) {
        _preferences.update { it.copy(checkIntervalHours = sanitizeCheckIntervalHours(hours)) }
    }

    override suspend fun setLastCheckEpochMillis(epochMillis: Long) {
        _preferences.update { it.copy(lastCheckEpochMillis = epochMillis.takeIf { millis -> millis > 0 }) }
    }
}
