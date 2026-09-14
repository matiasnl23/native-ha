package com.matiasnl.hakiosk.ui.remote

import com.matiasnl.hakiosk.data.device.RemoteCommand
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeRemoteNavigationActions : RemoteNavigationActions {
    override var isDashboardEditing: Boolean = false
    var viewShown: String? = null
        private set
    var wentToFirstView = false
        private set
    var openedCameraEntityId: String? = null
        private set
    var cameraClosed = false
        private set

    override fun goToView(viewId: String) {
        viewShown = viewId
    }

    override fun goToFirstView() {
        wentToFirstView = true
    }

    override fun openCamera(entityId: String) {
        openedCameraEntityId = entityId
        cameraClosed = false
    }

    override fun closeCamera() {
        cameraClosed = true
    }
}

class RemoteCommandNavigatorTest {

    private fun TestScope.harness(
        actions: FakeRemoteNavigationActions = FakeRemoteNavigationActions(),
        defaultCloseAfterSeconds: Int = 60,
    ): Triple<RemoteCommandNavigator, FakeRemoteNavigationActions, MutableSharedFlow<RemoteCommand>> {
        val commands = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 16)
        var turnedScreenOn = 0
        var reloaded = 0
        val navigator = RemoteCommandNavigator(
            scope = backgroundScope,
            commands = commands,
            actions = actions,
            turnScreenOn = { turnedScreenOn++ },
            cameraCloseAfterSecondsProvider = { defaultCloseAfterSeconds },
            reload = { reloaded++ },
        )
        navigator.start()
        runCurrent() // Lets the collector coroutine reach its first suspension point before any emit.
        return Triple(navigator, actions, commands)
    }

    @Test
    fun `ShowView switches the dashboard to that view`() = runTest {
        val (_, actions, commands) = harness()

        commands.emit(RemoteCommand.ShowView("v2"))
        runCurrent()

        assertEquals("v2", actions.viewShown)
    }

    @Test
    fun `ShowView and ShowMainView are ignored while editing`() = runTest {
        val actions = FakeRemoteNavigationActions().apply { isDashboardEditing = true }
        val (_, _, commands) = harness(actions)

        commands.emit(RemoteCommand.ShowView("v2"))
        runCurrent()
        commands.emit(RemoteCommand.ShowMainView)
        runCurrent()

        assertNull(actions.viewShown)
        assertTrue(!actions.wentToFirstView)
    }

    @Test
    fun `ShowMainView goes to the first view`() = runTest {
        val (_, actions, commands) = harness()

        commands.emit(RemoteCommand.ShowMainView)
        runCurrent()

        assertTrue(actions.wentToFirstView)
    }

    @Test
    fun `OpenCamera is ignored while editing`() = runTest {
        val actions = FakeRemoteNavigationActions().apply { isDashboardEditing = true }
        val (_, _, commands) = harness(actions)

        commands.emit(RemoteCommand.OpenCamera("camera.front_door", null))
        runCurrent()

        assertNull(actions.openedCameraEntityId)
    }

    @Test
    fun `OpenCamera without an explicit delay auto-closes after the default`() = runTest {
        val (_, actions, commands) = harness(defaultCloseAfterSeconds = 30)

        commands.emit(RemoteCommand.OpenCamera("camera.front_door", null))
        runCurrent()
        assertEquals("camera.front_door", actions.openedCameraEntityId)

        advanceTimeBy(29_000)
        assertTrue(!actions.cameraClosed)

        advanceTimeBy(1_001)
        assertTrue(actions.cameraClosed)
    }

    @Test
    fun `OpenCamera with an explicit delay overrides the default`() = runTest {
        val (_, actions, commands) = harness(defaultCloseAfterSeconds = 60)

        commands.emit(RemoteCommand.OpenCamera("camera.front_door", 5))
        runCurrent()

        advanceTimeBy(4_000)
        assertTrue(!actions.cameraClosed)
        advanceTimeBy(1_001)
        assertTrue(actions.cameraClosed)
    }

    @Test
    fun `OpenCamera with a zero delay never auto-closes`() = runTest {
        val (_, actions, commands) = harness()

        commands.emit(RemoteCommand.OpenCamera("camera.front_door", 0))
        runCurrent()

        advanceTimeBy(60 * 60_000L)
        assertTrue(!actions.cameraClosed)
    }

    @Test
    fun `a touch on the camera screen cancels the pending auto-close`() = runTest {
        val (navigator, actions, commands) = harness(defaultCloseAfterSeconds = 30)

        commands.emit(RemoteCommand.OpenCamera("camera.front_door", null))
        runCurrent()
        navigator.onCameraScreenTouch()

        advanceTimeBy(60 * 60_000L)
        assertTrue(!actions.cameraClosed)
    }

    @Test
    fun `a new OpenCamera cancels the previous pending auto-close`() = runTest {
        val (_, actions, commands) = harness(defaultCloseAfterSeconds = 30)

        commands.emit(RemoteCommand.OpenCamera("camera.front_door", null))
        runCurrent()
        advanceTimeBy(20_000)
        commands.emit(RemoteCommand.OpenCamera("camera.back_door", null))
        runCurrent()
        assertEquals("camera.back_door", actions.openedCameraEntityId)

        // The first camera's timer would have fired around here; it must not close the new camera.
        advanceTimeBy(11_000)
        assertTrue(!actions.cameraClosed)

        advanceTimeBy(19_001)
        assertTrue(actions.cameraClosed)
    }

    @Test
    fun `CloseCamera cancels the auto-close and closes the camera`() = runTest {
        val (_, actions, commands) = harness(defaultCloseAfterSeconds = 30)

        commands.emit(RemoteCommand.OpenCamera("camera.front_door", null))
        runCurrent()
        commands.emit(RemoteCommand.CloseCamera)
        runCurrent()

        assertTrue(actions.cameraClosed)
    }

    @Test
    fun `Reload invokes the reload callback`() = runTest {
        var reloaded = 0
        val commands = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 16)
        val navigator = RemoteCommandNavigator(
            scope = backgroundScope,
            commands = commands,
            actions = FakeRemoteNavigationActions(),
            turnScreenOn = {},
            cameraCloseAfterSecondsProvider = { 60 },
            reload = { reloaded++ },
        )
        navigator.start()
        runCurrent()

        commands.emit(RemoteCommand.Reload)
        runCurrent()

        assertEquals(1, reloaded)
    }
}
