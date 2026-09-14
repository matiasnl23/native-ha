package com.matiasnl.hakiosk.data.display

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Brightness reported to Home Assistant while the app follows the system brightness (none set remotely). */
const val DEFAULT_BRIGHTNESS_PERCENT = 100

/** Default "screen off after inactivity" timeout in minutes; 0 = never. */
const val DEFAULT_SCREEN_OFF_TIMEOUT_MINUTES = 0

/** Default auto-close delay for a camera opened by a remote command, in seconds; 0 = don't close. */
const val DEFAULT_CAMERA_CLOSE_AFTER_SECONDS = 60

/**
 * Screen/camera preferences Home Assistant can change remotely, persisted so they survive restarts.
 * Kept in a DataStore file of its own (see [DisplayModule]), independent of the dashboard layout and
 * the MQTT broker config.
 */
data class DisplayPreferences(
    /**
     * App window brightness 0..100, or null (the default) to follow the system brightness, including
     * adaptive brightness. Only set once Home Assistant sends a brightness.
     */
    val brightnessPercent: Int? = null,
    val screenOffTimeoutMinutes: Int = DEFAULT_SCREEN_OFF_TIMEOUT_MINUTES,
    val cameraCloseAfterSeconds: Int = DEFAULT_CAMERA_CLOSE_AFTER_SECONDS,
)

interface DisplayPreferencesStore {
    val preferences: Flow<DisplayPreferences>

    /** Clamped to 0..100. */
    suspend fun setBrightnessPercent(percent: Int)

    /** Clamped to a minimum of 0 (never). */
    suspend fun setScreenOffTimeoutMinutes(minutes: Int)

    /** Clamped to a minimum of 0 (don't auto-close). */
    suspend fun setCameraCloseAfterSeconds(seconds: Int)
}

internal fun sanitizeBrightnessPercent(percent: Int): Int = percent.coerceIn(0, 100)

internal fun sanitizeNonNegative(value: Int): Int = value.coerceAtLeast(0)

private val BRIGHTNESS_PERCENT_KEY = intPreferencesKey("brightness_percent")
private val SCREEN_OFF_TIMEOUT_MINUTES_KEY = intPreferencesKey("screen_off_timeout_minutes")
private val CAMERA_CLOSE_AFTER_SECONDS_KEY = intPreferencesKey("camera_close_after_seconds")

/** [DisplayPreferencesStore] on a DataStore Preferences file of its own. An unreadable file falls back to defaults. */
class DataStoreDisplayPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : DisplayPreferencesStore {

    override val preferences: Flow<DisplayPreferences> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs ->
            DisplayPreferences(
                brightnessPercent = prefs[BRIGHTNESS_PERCENT_KEY]?.let(::sanitizeBrightnessPercent),
                screenOffTimeoutMinutes = sanitizeNonNegative(
                    prefs[SCREEN_OFF_TIMEOUT_MINUTES_KEY] ?: DEFAULT_SCREEN_OFF_TIMEOUT_MINUTES,
                ),
                cameraCloseAfterSeconds = sanitizeNonNegative(
                    prefs[CAMERA_CLOSE_AFTER_SECONDS_KEY] ?: DEFAULT_CAMERA_CLOSE_AFTER_SECONDS,
                ),
            )
        }

    override suspend fun setBrightnessPercent(percent: Int) {
        dataStore.edit { it[BRIGHTNESS_PERCENT_KEY] = sanitizeBrightnessPercent(percent) }
    }

    override suspend fun setScreenOffTimeoutMinutes(minutes: Int) {
        dataStore.edit { it[SCREEN_OFF_TIMEOUT_MINUTES_KEY] = sanitizeNonNegative(minutes) }
    }

    override suspend fun setCameraCloseAfterSeconds(seconds: Int) {
        dataStore.edit { it[CAMERA_CLOSE_AFTER_SECONDS_KEY] = sanitizeNonNegative(seconds) }
    }
}

/** In-memory [DisplayPreferencesStore] for previews and tests. */
class InMemoryDisplayPreferencesStore(
    initial: DisplayPreferences = DisplayPreferences(),
) : DisplayPreferencesStore {
    private val _preferences = MutableStateFlow(initial)
    override val preferences: StateFlow<DisplayPreferences> = _preferences.asStateFlow()

    override suspend fun setBrightnessPercent(percent: Int) {
        _preferences.update { it.copy(brightnessPercent = sanitizeBrightnessPercent(percent)) }
    }

    override suspend fun setScreenOffTimeoutMinutes(minutes: Int) {
        _preferences.update { it.copy(screenOffTimeoutMinutes = sanitizeNonNegative(minutes)) }
    }

    override suspend fun setCameraCloseAfterSeconds(seconds: Int) {
        _preferences.update { it.copy(cameraCloseAfterSeconds = sanitizeNonNegative(seconds)) }
    }
}
