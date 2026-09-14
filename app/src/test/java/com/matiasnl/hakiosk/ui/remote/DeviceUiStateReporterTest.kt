package com.matiasnl.hakiosk.ui.remote

import com.matiasnl.hakiosk.data.dashboard.DashboardLayout
import com.matiasnl.hakiosk.data.dashboard.DashboardView
import com.matiasnl.hakiosk.data.dashboard.InMemoryDashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.TileContent
import com.matiasnl.hakiosk.data.dashboard.newDashboardTile
import com.matiasnl.hakiosk.data.device.CameraOption
import com.matiasnl.hakiosk.data.device.InMemoryRemoteControlBridge
import com.matiasnl.hakiosk.data.device.ViewOption
import com.matiasnl.hakiosk.data.ha.fake.FakeHaRepository
import com.matiasnl.hakiosk.data.ha.fake.sampleEntities
import com.matiasnl.hakiosk.ui.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class DeviceUiStateReporterTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun layoutWithCamera() = DashboardLayout(
        views = listOf(
            DashboardView(
                "v1",
                "Principal",
                tiles = listOf(newDashboardTile(TileContent.Entity("camera.front_door"))),
            ),
        ),
    )

    @Test
    fun `reports views, cameras and screen state from its sources`() = runTest {
        val bridge = InMemoryRemoteControlBridge()
        val layoutStore = InMemoryDashboardLayoutStore(layoutWithCamera())
        val haRepository = FakeHaRepository(sampleEntities())
        val screenState = MutableStateFlow(ScreenControlUiState(screenOn = true, brightnessPercent = 80))
        DeviceUiStateReporter(bridge, layoutStore, haRepository, screenState, clock = { 0L })

        val reported = bridge.uiState.value
        assertEquals(listOf(ViewOption("v1", "Principal")), reported.views)
        assertEquals(listOf(CameraOption("camera.front_door", "Front door")), reported.cameras)
        assertEquals(80, reported.brightnessPercent)
        assertNull(reported.currentViewId)
        assertNull(reported.openCameraEntityId)
    }

    @Test
    fun `reportNavigation updates the current view and open camera`() = runTest {
        val bridge = InMemoryRemoteControlBridge()
        val reporter = DeviceUiStateReporter(
            bridge,
            InMemoryDashboardLayoutStore(layoutWithCamera()),
            FakeHaRepository(sampleEntities()),
            MutableStateFlow(ScreenControlUiState()),
            clock = { 0L },
        )

        reporter.reportNavigation("v1", "camera.front_door")

        assertEquals("v1", bridge.uiState.value.currentViewId)
        assertEquals("camera.front_door", bridge.uiState.value.openCameraEntityId)
    }

    @Test
    fun `onUserActivity and onOverlayTouch report the interaction timestamp`() = runTest {
        val bridge = InMemoryRemoteControlBridge()
        var now = 5_000L
        val reporter = DeviceUiStateReporter(
            bridge,
            InMemoryDashboardLayoutStore(layoutWithCamera()),
            FakeHaRepository(sampleEntities()),
            MutableStateFlow(ScreenControlUiState()),
            clock = { now },
        )

        reporter.onUserActivity()

        assertEquals(5_000L, bridge.uiState.value.lastInteractionEpochMillis)

        // Within the throttle window: ignored.
        now = 6_000L
        reporter.onUserActivity()
        assertEquals(5_000L, bridge.uiState.value.lastInteractionEpochMillis)

        // An overlay touch always reports right away.
        reporter.onOverlayTouch()
        assertEquals(6_000L, bridge.uiState.value.lastInteractionEpochMillis)
    }

    @Test
    fun `screen state changes are reflected in the reported ui state`() = runTest {
        val bridge = InMemoryRemoteControlBridge()
        val screenState = MutableStateFlow(ScreenControlUiState(screenOn = true, screenOffTimeoutMinutes = 0))
        DeviceUiStateReporter(
            bridge,
            InMemoryDashboardLayoutStore(layoutWithCamera()),
            FakeHaRepository(sampleEntities()),
            screenState,
            clock = { 0L },
        )

        screenState.value = ScreenControlUiState(screenOn = false, screenOffTimeoutMinutes = 5)

        assertEquals(false, bridge.uiState.value.screenOn)
        assertEquals(5, bridge.uiState.value.screenOffTimeoutMinutes)
    }
}
