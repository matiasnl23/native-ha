package com.matiasnl.hakiosk.ui.remote

import com.matiasnl.hakiosk.data.device.InMemoryRemoteControlBridge
import com.matiasnl.hakiosk.data.device.RemoteCommand
import com.matiasnl.hakiosk.data.display.InMemoryDisplayPreferencesStore
import com.matiasnl.hakiosk.ui.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScreenControlViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun TestScope.viewModel(
        bridge: InMemoryRemoteControlBridge = InMemoryRemoteControlBridge(),
        preferences: InMemoryDisplayPreferencesStore = InMemoryDisplayPreferencesStore(),
    ): ScreenControlViewModel {
        val viewModel = ScreenControlViewModel(bridge, preferences, clock = { testScheduler.currentTime })
        backgroundScope.launch(Dispatchers.Main) { viewModel.uiState.collect {} }
        return viewModel
    }

    @Test
    fun `starts with the screen on and the persisted defaults`() = runTest {
        val viewModel = viewModel()

        val state = viewModel.uiState.value
        assertTrue(state.screenOn)
        assertEquals(100, state.brightnessPercent)
        assertEquals(0, state.screenOffTimeoutMinutes)
        assertEquals(60, state.cameraCloseAfterSeconds)
    }

    @Test
    fun `SetScreenOn false shows the overlay, true removes it`() = runTest {
        val bridge = InMemoryRemoteControlBridge()
        val viewModel = viewModel(bridge)

        bridge.dispatch(RemoteCommand.SetScreenOn(false))
        assertFalse(viewModel.uiState.value.screenOn)

        bridge.dispatch(RemoteCommand.SetScreenOn(true))
        assertTrue(viewModel.uiState.value.screenOn)
    }

    @Test
    fun `SetBrightness is applied and persisted`() = runTest {
        val bridge = InMemoryRemoteControlBridge()
        val preferences = InMemoryDisplayPreferencesStore()
        val viewModel = viewModel(bridge, preferences)

        bridge.dispatch(RemoteCommand.SetBrightness(40))

        assertEquals(40, viewModel.uiState.value.brightnessPercent)
        assertEquals(40, preferences.preferences.value.brightnessPercent)
    }

    @Test
    fun `SetCameraCloseAfter is applied and persisted`() = runTest {
        val bridge = InMemoryRemoteControlBridge()
        val preferences = InMemoryDisplayPreferencesStore()
        val viewModel = viewModel(bridge, preferences)

        bridge.dispatch(RemoteCommand.SetCameraCloseAfter(15))

        assertEquals(15, viewModel.uiState.value.cameraCloseAfterSeconds)
        assertEquals(15, preferences.preferences.value.cameraCloseAfterSeconds)
    }

    @Test
    fun `turns the screen off after the configured timeout without touches`() = runTest {
        val bridge = InMemoryRemoteControlBridge()
        val viewModel = viewModel(bridge)

        bridge.dispatch(RemoteCommand.SetScreenOffTimeout(1))
        advanceTimeBy(59_000)
        assertTrue(viewModel.uiState.value.screenOn)

        advanceTimeBy(1_001)
        assertFalse(viewModel.uiState.value.screenOn)
    }

    @Test
    fun `touches reset the screen-off countdown`() = runTest {
        val bridge = InMemoryRemoteControlBridge()
        val viewModel = viewModel(bridge)
        bridge.dispatch(RemoteCommand.SetScreenOffTimeout(1))

        advanceTimeBy(59_000)
        viewModel.onUserActivity()
        advanceTimeBy(59_000)
        assertTrue(viewModel.uiState.value.screenOn)

        advanceTimeBy(1_001)
        assertFalse(viewModel.uiState.value.screenOn)
    }

    @Test
    fun `a timeout of zero never turns the screen off`() = runTest {
        val viewModel = viewModel()

        advanceTimeBy(60 * 60_000L)

        assertTrue(viewModel.uiState.value.screenOn)
    }

    @Test
    fun `turnScreenOn restarts the countdown`() = runTest {
        val bridge = InMemoryRemoteControlBridge()
        val viewModel = viewModel(bridge)
        bridge.dispatch(RemoteCommand.SetScreenOffTimeout(1))

        advanceTimeBy(60_001)
        assertFalse(viewModel.uiState.value.screenOn)

        viewModel.turnScreenOn()
        assertTrue(viewModel.uiState.value.screenOn)
        advanceTimeBy(59_000)
        assertTrue(viewModel.uiState.value.screenOn)
        advanceTimeBy(1_001)
        assertFalse(viewModel.uiState.value.screenOn)
    }
}
