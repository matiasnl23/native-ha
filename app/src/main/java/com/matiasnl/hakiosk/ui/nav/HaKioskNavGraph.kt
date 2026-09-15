package com.matiasnl.hakiosk.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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
import com.matiasnl.hakiosk.ui.camera.CameraTileThumbnail
import com.matiasnl.hakiosk.ui.dashboard.tiles.LocalCameraStreamCatalog
import androidx.compose.runtime.CompositionLocalProvider
import com.matiasnl.hakiosk.ui.camera.CameraViewModel
import com.matiasnl.hakiosk.ui.dashboard.DashboardScreen
import com.matiasnl.hakiosk.ui.dashboard.DashboardViewModel
import com.matiasnl.hakiosk.ui.remote.BrokerSettingsScreen
import com.matiasnl.hakiosk.ui.remote.BrokerSettingsViewModel
import com.matiasnl.hakiosk.ui.remote.DeviceUiStateReporter
import com.matiasnl.hakiosk.ui.remote.RemoteCommandNavigator
import com.matiasnl.hakiosk.ui.remote.RemoteNavigationActions
import com.matiasnl.hakiosk.ui.remote.ScreenControlViewModel
import com.matiasnl.hakiosk.ui.remote.ScreenOffOverlay
import com.matiasnl.hakiosk.ui.remote.WindowBrightnessEffect
import com.matiasnl.hakiosk.ui.setup.SetupScreen
import com.matiasnl.hakiosk.ui.setup.SetupViewModel
import kotlinx.coroutines.flow.first

/** Where the app lands on cold start, resolved once the first stored config value is known. */
private enum class StartDestination { Loading, Setup, Dashboard }

/** Leaves the camera screen (if open) and navigates to the dashboard unless it's already current. */
private fun ensureOnDashboard(navController: NavHostController) {
    if (navController.currentDestination?.hasRoute<CameraRoute>() == true) {
        navController.popBackStack()
    }
    if (navController.currentDestination?.hasRoute<DashboardRoute>() != true) {
        navController.navigate(DashboardRoute) { launchSingleTop = true }
    }
}

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

    val deviceUiStateReporter: DeviceUiStateReporter = viewModel(
        factory = DeviceUiStateReporter.factory(
            remoteControlBridge,
            dashboardLayoutStore,
            haRepository,
            screenControlViewModel.uiState,
        ),
    )

    when (val destination = startDestination) {
        StartDestination.Loading -> Surface(modifier = Modifier.fillMaxSize()) {}
        StartDestination.Setup, StartDestination.Dashboard -> {
            val navController = rememberNavController()
            val latestOnUserActivity by rememberUpdatedState {
                screenControlViewModel.onUserActivity()
                deviceUiStateReporter.onUserActivity()
            }

            // Tracks the current DashboardViewModel instance (only set while its back stack entry
            // exists), so remote navigation commands can drive it regardless of which screen shows.
            var dashboardViewModel by remember { mutableStateOf<DashboardViewModel?>(null) }
            val coroutineScope = rememberCoroutineScope()
            val navigator = remember {
                RemoteCommandNavigator(
                    scope = coroutineScope,
                    commands = remoteControlBridge.commands,
                    actions = object : RemoteNavigationActions {
                        override val isDashboardEditing: Boolean
                            get() = dashboardViewModel?.uiState?.value?.isEditing == true

                        override fun goToView(viewId: String) {
                            ensureOnDashboard(navController)
                            dashboardViewModel?.selectView(viewId)
                        }

                        override fun goToFirstView() {
                            ensureOnDashboard(navController)
                            dashboardViewModel?.selectFirstView()
                        }

                        override fun openCamera(entityId: String) {
                            // launchSingleTop on the same destination keeps the top back stack entry, and with it
                            // the CameraViewModel bound to the previous camera: leave another camera first.
                            val current = navController.currentBackStackEntry
                            if (current?.destination?.hasRoute<CameraRoute>() == true) {
                                if (current.toRoute<CameraRoute>().entityId == entityId) return
                                navController.popBackStack()
                            }
                            navController.navigate(CameraRoute(entityId)) { launchSingleTop = true }
                        }

                        override fun closeCamera() {
                            if (navController.currentDestination?.hasRoute<CameraRoute>() == true) {
                                navController.popBackStack()
                            }
                        }
                    },
                    turnScreenOn = screenControlViewModel::turnScreenOn,
                    cameraCloseAfterSecondsProvider = { screenState.cameraCloseAfterSeconds },
                    reload = {
                        haRepository.stop()
                        haRepository.start()
                    },
                )
            }
            LaunchedEffect(navigator) { navigator.start() }

            // Reports the settled view (survives the dashboard not being the current screen) and the
            // open camera entity, if any, to Home Assistant through deviceUiStateReporter.
            var reportedViewId by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(dashboardViewModel) {
                val currentDashboardViewModel = dashboardViewModel
                if (currentDashboardViewModel == null) {
                    reportedViewId = null
                } else {
                    currentDashboardViewModel.uiState.collect { state -> reportedViewId = state.viewId }
                }
            }
            val currentBackStackEntry by navController.currentBackStackEntryAsState()
            val openCameraEntityId = currentBackStackEntry
                ?.takeIf { it.destination.hasRoute<CameraRoute>() }
                ?.let { it.toRoute<CameraRoute>().entityId }
            LaunchedEffect(reportedViewId, openCameraEntityId) {
                deviceUiStateReporter.reportNavigation(reportedViewId, openCameraEntityId)
            }

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
                        // Exposes this instance to the remote navigation actions above for as long as
                        // this back stack entry is alive (it may still exist while another screen shows).
                        DisposableEffect(viewModel) {
                            dashboardViewModel = viewModel
                            onDispose { if (dashboardViewModel === viewModel) dashboardViewModel = null }
                        }
                        CompositionLocalProvider(
                            LocalCameraStreamCatalog provides { entityId -> cameraModule.cameraSource.go2rtcStreams(entityId) },
                        ) {
                            DashboardScreen(
                                viewModel = viewModel,
                                onOpenSettings = { navController.navigate(SetupRoute) },
                                onOpenCamera = { entityId, label, stream ->
                                    // launchSingleTop: a double tap must not stack two camera screens.
                                    navController.navigate(CameraRoute(entityId, label, stream)) { launchSingleTop = true }
                                },
                                cameraThumbnail = { entityId, options, active, modifier ->
                                    CameraTileThumbnail(
                                        entityId = entityId,
                                        options = options,
                                        active = active,
                                        camera = cameraModule,
                                        modifier = modifier,
                                    )
                                },
                            )
                        }
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
                                stream = route.stream,
                            ),
                        )
                        CameraScreen(
                            entityId = route.entityId,
                            viewModel = viewModel,
                            snapshots = cameraModule.snapshots,
                            onClose = { navController.popBackStack() },
                            onUserActivity = navigator::onCameraScreenTouch,
                        )
                    }
                }
                if (!screenState.screenOn) {
                    ScreenOffOverlay(
                        onTouch = {
                            screenControlViewModel.turnScreenOn()
                            deviceUiStateReporter.onOverlayTouch()
                        },
                    )
                }
            }
        }
    }
}
