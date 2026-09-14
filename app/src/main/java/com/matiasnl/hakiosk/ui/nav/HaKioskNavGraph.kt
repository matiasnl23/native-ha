package com.matiasnl.hakiosk.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.matiasnl.hakiosk.camera.CameraModule
import com.matiasnl.hakiosk.data.dashboard.DashboardLayoutStore
import com.matiasnl.hakiosk.data.ha.HaConfigStore
import com.matiasnl.hakiosk.data.ha.HaRepository
import com.matiasnl.hakiosk.ui.camera.CameraScreen
import com.matiasnl.hakiosk.ui.camera.CameraThumbnail
import com.matiasnl.hakiosk.ui.camera.CameraViewModel
import com.matiasnl.hakiosk.ui.dashboard.DashboardScreen
import com.matiasnl.hakiosk.ui.dashboard.DashboardViewModel
import com.matiasnl.hakiosk.ui.editor.EditorScreen
import com.matiasnl.hakiosk.ui.editor.EditorViewModel
import com.matiasnl.hakiosk.ui.setup.SetupScreen
import com.matiasnl.hakiosk.ui.setup.SetupViewModel
import com.matiasnl.hakiosk.ui.viewsettings.ViewSettingsScreen
import com.matiasnl.hakiosk.ui.viewsettings.ViewSettingsViewModel
import kotlinx.coroutines.flow.first

/** Where the app lands on cold start, resolved once the first stored config value is known. */
private enum class StartDestination { Loading, Setup, Dashboard }

/**
 * App-wide navigation graph. Decides the start destination from [haConfigStore] (no config yet ->
 * [SetupRoute], otherwise [DashboardRoute]) without ever flashing the wrong screen: nothing is
 * shown while the first value is still loading.
 */
@Composable
fun HaKioskNavGraph(
    haConfigStore: HaConfigStore,
    haRepository: HaRepository,
    dashboardLayoutStore: DashboardLayoutStore,
    cameraModule: CameraModule,
) {
    val startDestination by produceState(initialValue = StartDestination.Loading, haConfigStore) {
        value = if (haConfigStore.config.first() == null) StartDestination.Setup else StartDestination.Dashboard
    }

    when (val destination = startDestination) {
        StartDestination.Loading -> Surface(modifier = Modifier.fillMaxSize()) {}
        StartDestination.Setup, StartDestination.Dashboard -> {
            val navController = rememberNavController()
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
                    )
                }
                composable<DashboardRoute> { backStackEntry ->
                    val viewModel: DashboardViewModel = viewModel(
                        viewModelStoreOwner = backStackEntry,
                        factory = DashboardViewModel.factory(haRepository, dashboardLayoutStore),
                    )
                    DashboardScreen(
                        viewModel = viewModel,
                        onOpenEditor = { navController.navigate(EditorRoute) },
                        onOpenSettings = { navController.navigate(SetupRoute) },
                        onOpenViewSettings = { viewId ->
                            navController.navigate(ViewSettingsRoute(viewId)) { launchSingleTop = true }
                        },
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
                composable<ViewSettingsRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<ViewSettingsRoute>()
                    val viewModel: ViewSettingsViewModel = viewModel(
                        viewModelStoreOwner = backStackEntry,
                        factory = ViewSettingsViewModel.factory(route.viewId, dashboardLayoutStore),
                    )
                    ViewSettingsScreen(
                        viewModel = viewModel,
                        // Guard against popping twice (Done and back racing): only pop while we're on top.
                        onClose = {
                            if (navController.currentBackStackEntry?.id == backStackEntry.id) navController.popBackStack()
                        },
                    )
                }
                composable<EditorRoute> { backStackEntry ->
                    val viewModel: EditorViewModel = viewModel(
                        viewModelStoreOwner = backStackEntry,
                        factory = EditorViewModel.factory(haRepository, dashboardLayoutStore),
                    )
                    EditorScreen(
                        viewModel = viewModel,
                        onDone = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
