package com.matiasnl.hakiosk.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.matiasnl.hakiosk.data.device.RemoteCommand
import com.matiasnl.hakiosk.data.device.RemoteControlBridge
import com.matiasnl.hakiosk.data.display.DisplayPreferences
import com.matiasnl.hakiosk.data.display.DisplayPreferencesStore
import com.matiasnl.hakiosk.ui.dashboard.InactivityTimer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val MILLIS_PER_MINUTE = 60_000L

/** Screen on/off, brightness and the persisted values Home Assistant can change remotely. */
data class ScreenControlUiState(
    val screenOn: Boolean = true,
    val brightnessPercent: Int = 100,
    val screenOffTimeoutMinutes: Int = 0,
    val cameraCloseAfterSeconds: Int = 60,
)

/**
 * Owns the "screen off" overlay state and the app's window brightness. There is no Device Owner yet
 * (see README.md), so "off" is a full-screen black overlay at minimum brightness rather than a real
 * display power-off; `FLAG_KEEP_SCREEN_ON` stays set on the activity the whole time the app runs (see
 * [com.matiasnl.hakiosk.MainActivity]) so the system timeout never turns the real display off.
 *
 * Applies [RemoteCommand.SetScreenOn], [RemoteCommand.SetBrightness], [RemoteCommand.SetScreenOffTimeout]
 * and [RemoteCommand.SetCameraCloseAfter] from [remoteControlBridge], and persists the last three
 * through [displayPreferencesStore] so they survive restarts. Navigation commands (ShowView,
 * OpenCamera, ...) are handled separately (see `RemoteCommandNavigator`), which calls [turnScreenOn]
 * itself for the commands that should wake the screen.
 *
 * Scoped to the whole activity (constructed once, outside any back stack entry) so the overlay and
 * brightness survive navigating between screens.
 */
class ScreenControlViewModel(
    private val remoteControlBridge: RemoteControlBridge,
    private val displayPreferencesStore: DisplayPreferencesStore,
    clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val _screenOn = MutableStateFlow(true)

    private val preferences: StateFlow<DisplayPreferences> = displayPreferencesStore.preferences
        .stateIn(viewModelScope, SharingStarted.Eagerly, DisplayPreferences())

    val uiState: StateFlow<ScreenControlUiState> = combine(_screenOn, preferences) { screenOn, prefs ->
        ScreenControlUiState(screenOn, prefs.brightnessPercent, prefs.screenOffTimeoutMinutes, prefs.cameraCloseAfterSeconds)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScreenControlUiState())

    private val inactivityTimer = InactivityTimer(viewModelScope, clock, onTimeout = { _screenOn.value = false })

    init {
        viewModelScope.launch {
            preferences.map { it.screenOffTimeoutMinutes }.distinctUntilChanged().collect { minutes ->
                inactivityTimer.start(minutes * MILLIS_PER_MINUTE)
            }
        }
        viewModelScope.launch {
            remoteControlBridge.commands.collect { command ->
                when (command) {
                    is RemoteCommand.SetScreenOn -> if (command.on) turnScreenOn() else _screenOn.value = false
                    is RemoteCommand.SetBrightness -> setBrightnessPercent(command.percent)
                    is RemoteCommand.SetScreenOffTimeout -> setScreenOffTimeoutMinutes(command.minutes)
                    is RemoteCommand.SetCameraCloseAfter -> setCameraCloseAfterSeconds(command.seconds)
                    else -> Unit
                }
            }
        }
    }

    /** Removes the overlay if shown and restarts the inactivity countdown. Used by a touch on the
     * overlay itself, by [RemoteCommand.SetScreenOn] (true) and by navigation commands that should
     * wake the screen (e.g. opening a camera remotely). Idempotent. */
    fun turnScreenOn() {
        _screenOn.value = true
        inactivityTimer.onActivity()
    }

    /** Any touch anywhere in the app: restarts the inactivity countdown. */
    fun onUserActivity() {
        inactivityTimer.onActivity()
    }

    private fun setBrightnessPercent(percent: Int) {
        viewModelScope.launch { displayPreferencesStore.setBrightnessPercent(percent) }
    }

    private fun setScreenOffTimeoutMinutes(minutes: Int) {
        viewModelScope.launch { displayPreferencesStore.setScreenOffTimeoutMinutes(minutes) }
    }

    private fun setCameraCloseAfterSeconds(seconds: Int) {
        viewModelScope.launch { displayPreferencesStore.setCameraCloseAfterSeconds(seconds) }
    }

    companion object {
        fun factory(remoteControlBridge: RemoteControlBridge, displayPreferencesStore: DisplayPreferencesStore) = viewModelFactory {
            initializer { ScreenControlViewModel(remoteControlBridge, displayPreferencesStore) }
        }
    }
}
