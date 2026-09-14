package com.matiasnl.hakiosk.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.matiasnl.hakiosk.camera.CameraModule
import com.matiasnl.hakiosk.data.dashboard.DashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.DashboardViewPreferencesStore
import com.matiasnl.hakiosk.data.device.MqttConfigStore
import com.matiasnl.hakiosk.data.device.MqttRemoteControl
import com.matiasnl.hakiosk.data.device.RemoteControlBridge
import com.matiasnl.hakiosk.data.display.DisplayPreferencesStore
import com.matiasnl.hakiosk.data.ha.HaConfigStore
import com.matiasnl.hakiosk.data.ha.HaRepository
import com.matiasnl.hakiosk.ui.camera.CameraScreen
import com.matiasnl.hakiosk.ui.camera.CameraThumbnail
import com.matiasnl.hakiosk.ui.camera.CameraViewModel
import com.matiasnl.hakiosk.ui.dashboard.DashboardScreen
import com.matiasnl.hakiosk.ui.dashboard.DashboardViewModel
import com.matiasnl.hakiosk.ui.remote.BrokerSettingsScreen
import com.matiasnl.hakiosk.ui.remote.BrokerSettingsViewModel
import com.matiasnl.hakiosk.ui.remote.ScreenControlViewModel
import com.matiasnl.hakiosk.ui.remote.ScreenOffOverlay
import com.matiasnl.hakiosk.ui.remote.WindowBrightnessEffect
import com.matiasnl.hakiosk.ui.setup.SetupScreen
import com.matiasnl.hakiosk.ui.setup.SetupViewModel
import kotlinx.coroutines.flow.first

/** Where the app lands on cold start, resolved once the first stored config value is known. */
private enum class StartDestination { Loading, Setup, Dashboard }

/**
 * App-wide navigation graph. Decides the start destination from [haConfigStore] (no config yet ->
 * [SetupRoute], otherwise [DashboardRoute]) without ever flashing the wrong screen: nothing is
 * shown while the first value is still loading.
 *
 * Also owns remote control's screen-level state ([ScreenControlViewModel], scoped to the whole
 * activity rather than any one back stack entry): the screen-off overlay is drawn above the whole
 * [NavHost] so it covers any screen, window brightness is applied here, and every touch anywhere in
 * the app restarts the screen-off inactivity countdown.
 */
@Composable
fun HaKioskNavGraph(
    haConfigStore: HaConfigStore,
    haRepository: HaRepository,
    dashboardLayoutStore: DashboardLayoutStore,
    dashboardViewPreferencesStore: DashboardViewPreferencesStore,
    cameraModule: CameraModule,
    mqttConfigStore: MqttConfigStore,
    mqttRemoteControl: MqttRemoteControl,
    remoteControlBridge: RemoteControlBridge,
    displayPreferencesStore: DisplayPreferencesStore,
) {
    val startDestination by produceState(initialValue = StartDestination.Loading, haConfigStore) {
        value = if (haConfigStore.config.first() == null) StartDestination.Setup else StartDestination.Dashboard
    }

    // Scoped to the activity (no back stack entry owner) so the screen-off overlay and brightness
    // survive navigating between screens.
    val screenControlViewModel: ScreenControlViewModel = viewModel(
        factory = ScreenControlViewModel.factory(remoteControlBridge, displayPreferencesStore),
    )
    val screenState by screenControlViewModel.uiState.collectAsStateWithLifecycle()
    WindowBrightnessEffect(screenState.screenOn, screenState.brightnessPercent)

    when (val destination = startDestination) {
        StartDestination.Loading -> Surface(modifier = Modifier.fillMaxSize()) {}
        StartDestination.Setup, StartDestination.Dashboard -> {
            val navController = rememberNavController()
            val latestOnUserActivity by rememberUpdatedState(screenControlViewModel::onUserActivity)
            Box(
                // Any touch anywhere in the app restarts the screen-off countdown. Observed in the
                // Initial pass and never consumed, so every screen's own gestures behave as before.
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                latestOnUserActivity()
                            }
                        }
                    },
            ) {
                NavHost(
                    navController = navController,
                    startDestination = if (destination == StartDestination.Setup) SetupRoute else DashboardRoute,
                ) {
                    composable<SetupRoute> { backStackEntry ->
                        val viewModel: SetupViewModel = viewModel(
                            viewModelStoreOwner = backStackEntry,
                            factory = SetupViewModel.factory(haRepository, haConfigStore),
                        )
                        SetupScreen(
                            viewModel = viewModel,
                            canGoBack = navController.previousBackStackEntry != null,
                            onSaved = {
                                if (navController.previousBackStackEntry != null) {
                                    navController.popBackStack()
                                } else {
                                    navController.navigate(DashboardRoute) {
                                        popUpTo(SetupRoute) { inclusive = true }
                                    }
                                }
                            },
                            onDisconnected = {
                                navController.navigate(SetupRoute) {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                            onBack = { navController.popBackStack() },
                            onOpenRemoteControl = { navController.navigate(RemoteControlRoute) },
                        )
                    }
                    composable<RemoteControlRoute> { backStackEntry ->
                        val viewModel: BrokerSettingsViewModel = viewModel(
                            viewModelStoreOwner = backStackEntry,
                            factory = BrokerSettingsViewModel.factory(mqttConfigStore, mqttRemoteControl),
                        )
                        BrokerSettingsScreen(
                            viewModel = viewModel,
                            onSaved = { navController.popBackStack() },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable<DashboardRoute> { backStackEntry ->
                        val viewModel: DashboardViewModel = viewModel(
                            viewModelStoreOwner = backStackEntry,
                            factory = DashboardViewModel.factory(haRepository, dashboardLayoutStore, dashboardViewPreferencesStore),
                        )
                        DashboardScreen(
                            viewModel = viewModel,
                            onOpenSettings = { navController.navigate(SetupRoute) },
                            onOpenCamera = { entityId, label ->
                                // launchSingleTop: a double tap must not stack two camera screens.
                                navController.navigate(CameraRoute(entityId, label)) { launchSingleTop = true }
                            },
                            cameraThumbnail = { entityId, active, modifier ->
                                CameraThumbnail(
                                    entityId = entityId,
                                    snapshots = cameraModule.snapshots,
                                    modifier = modifier,
                                    active = active,
                                )
                            },
                        )
                    }
                    composable<CameraRoute> { backStackEntry ->
                        val route = backStackEntry.toRoute<CameraRoute>()
                        val viewModel: CameraViewModel = viewModel(
                            viewModelStoreOwner = backStackEntry,
                            factory = CameraViewModel.factory(
                                entityId = route.entityId,
                                label = route.label,
                                haRepository = haRepository,
                                cameraSource = cameraModule.cameraSource,
                                sessionManager = cameraModule.webRtcSessionManager,
                            ),
                        )
                        CameraScreen(
                            entityId = route.entityId,
                            viewModel = viewModel,
                            snapshots = cameraModule.snapshots,
                            onClose = { navController.popBackStack() },
                        )
                    }
                }
                if (!screenState.screenOn) {
                    ScreenOffOverlay(onTouch = screenControlViewModel::turnScreenOn)
                }
            }
        }
    }
}
