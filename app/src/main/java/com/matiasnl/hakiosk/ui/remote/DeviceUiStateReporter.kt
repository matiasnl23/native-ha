package com.matiasnl.hakiosk.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.matiasnl.hakiosk.data.dashboard.DashboardLayoutStore
import com.matiasnl.hakiosk.data.device.DeviceUiState
import com.matiasnl.hakiosk.data.device.RemoteControlBridge
import com.matiasnl.hakiosk.data.display.DEFAULT_BRIGHTNESS_PERCENT
import com.matiasnl.hakiosk.data.ha.HaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** What the nav graph currently shows, as far as remote control cares. */
private data class NavigationReport(val currentViewId: String? = null, val openCameraEntityId: String? = null)

/**
 * Reports [DeviceUiState] to Home Assistant through [remoteControlBridge]: the dashboard's views and
 * camera tiles (from [dashboardLayoutStore] and [haRepository]'s entities, for friendly names), the
 * screen state ([screenControlUiState], owned by [ScreenControlViewModel]), the current view/open
 * camera (reported by the nav graph through [reportNavigation]) and the throttled last-interaction
 * timestamp (through [onUserActivity] and [onOverlayTouch]).
 *
 * Scoped to the whole activity, like [ScreenControlViewModel], so it keeps reporting regardless of
 * which screen is current.
 */
class DeviceUiStateReporter(
    private val remoteControlBridge: RemoteControlBridge,
    dashboardLayoutStore: DashboardLayoutStore,
    haRepository: HaRepository,
    screenControlUiState: StateFlow<ScreenControlUiState>,
    clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val _navigation = MutableStateFlow(NavigationReport())
    private val interactionThrottle = LastInteractionThrottle(clock)

    init {
        viewModelScope.launch {
            combine(
                dashboardLayoutStore.layout,
                haRepository.entities,
                screenControlUiState,
                _navigation,
                interactionThrottle.lastInteractionEpochMillis,
            ) { layout, entities, screen, navigation, lastInteraction ->
                DeviceUiState(
                    views = viewOptionsFrom(layout),
                    currentViewId = navigation.currentViewId,
                    cameras = cameraOptionsFrom(layout, entities),
                    openCameraEntityId = navigation.openCameraEntityId,
                    screenOn = screen.screenOn,
                    // The contract has no "follow the system" value: report full brightness until HA sets one.
                    brightnessPercent = screen.brightnessPercent ?: DEFAULT_BRIGHTNESS_PERCENT,
                    screenOffTimeoutMinutes = screen.screenOffTimeoutMinutes,
                    cameraCloseAfterSeconds = screen.cameraCloseAfterSeconds,
                    lastInteractionEpochMillis = lastInteraction,
                )
            }.collect { state -> remoteControlBridge.updateUiState { state } }
        }
    }

    /** The nav graph calls this whenever the current view or open camera changes. */
    fun reportNavigation(currentViewId: String?, openCameraEntityId: String?) {
        _navigation.value = NavigationReport(currentViewId, openCameraEntityId)
    }

    /** Any touch anywhere in the app; throttled (see [LastInteractionThrottle]). */
    fun onUserActivity() {
        interactionThrottle.onActivity()
    }

    /** A touch that turned the screen back on: always reported right away. */
    fun onOverlayTouch() {
        interactionThrottle.onActivity(forceImmediate = true)
    }

    companion object {
        fun factory(
            remoteControlBridge: RemoteControlBridge,
            dashboardLayoutStore: DashboardLayoutStore,
            haRepository: HaRepository,
            screenControlUiState: StateFlow<ScreenControlUiState>,
        ) = viewModelFactory {
            initializer { DeviceUiStateReporter(remoteControlBridge, dashboardLayoutStore, haRepository, screenControlUiState) }
        }
    }
}
