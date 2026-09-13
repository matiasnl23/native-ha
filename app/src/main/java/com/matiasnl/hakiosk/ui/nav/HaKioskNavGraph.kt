package com.matiasnl.hakiosk.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.matiasnl.hakiosk.data.dashboard.DashboardConfigStore
import com.matiasnl.hakiosk.data.ha.HaConfigStore
import com.matiasnl.hakiosk.data.ha.HaRepository
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
    dashboardConfigStore: DashboardConfigStore,
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
                composable<SetupRoute> {
                    Text("Setup")
                }
                composable<DashboardRoute> {
                    Text("Dashboard")
                }
                composable<EditorRoute> {
                    Text("Editor")
                }
            }
        }
    }
}
