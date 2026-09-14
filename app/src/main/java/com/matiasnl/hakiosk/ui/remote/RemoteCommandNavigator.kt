package com.matiasnl.hakiosk.ui.remote

import com.matiasnl.hakiosk.data.device.RemoteCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** What [RemoteCommandNavigator] needs from the nav graph, kept separate so the command handling
 * (edit-mode guard, auto-close scheduling) is testable without Compose Navigation. */
interface RemoteNavigationActions {
    /** True while the dashboard is in edit mode; navigation commands are ignored then. */
    val isDashboardEditing: Boolean

    /** Switches the dashboard to [viewId], navigating to it first if another screen is showing. No-op for unknown ids. */
    fun goToView(viewId: String)

    /** Switches the dashboard to its first view, navigating to it first if another screen is showing. */
    fun goToFirstView()

    /** Opens [entityId]'s camera focus view, replacing another one if already open. */
    fun openCamera(entityId: String)

    /** Leaves the camera focus view if it's the current screen. */
    fun closeCamera()
}

/**
 * Applies the navigation [RemoteCommand]s (everything but screen/brightness, handled by
 * [ScreenControlViewModel]): [RemoteCommand.ShowView], [RemoteCommand.ShowMainView],
 * [RemoteCommand.OpenCamera], [RemoteCommand.CloseCamera] and [RemoteCommand.Reload].
 *
 * ShowView/ShowMainView/OpenCamera are ignored while the dashboard is in edit mode, so an unattended
 * remote command never discards the user's in-progress edits; [RemoteCommand.Reload] and
 * [RemoteCommand.CloseCamera] still apply.
 *
 * A camera opened by [RemoteCommand.OpenCamera] auto-closes after `closeAfterSeconds` (falling back to
 * [cameraCloseAfterSecondsProvider]) unless that's 0; a new [RemoteCommand.OpenCamera] or
 * [onCameraScreenTouch] (a user touch on the camera screen) cancels the pending auto-close.
 */
class RemoteCommandNavigator(
    private val scope: CoroutineScope,
    private val commands: Flow<RemoteCommand>,
    private val actions: RemoteNavigationActions,
    private val turnScreenOn: () -> Unit,
    private val cameraCloseAfterSecondsProvider: () -> Int,
    private val reload: () -> Unit,
) {
    private var autoCloseJob: Job? = null

    /** Starts collecting [commands]; call once (e.g. from a `LaunchedEffect(Unit)`). */
    fun start() {
        scope.launch {
            commands.collect { command ->
                when (command) {
                    is RemoteCommand.ShowView -> if (!actions.isDashboardEditing) actions.goToView(command.viewId)
                    RemoteCommand.ShowMainView -> if (!actions.isDashboardEditing) actions.goToFirstView()
                    is RemoteCommand.OpenCamera -> handleOpenCamera(command)
                    RemoteCommand.CloseCamera -> {
                        cancelAutoClose()
                        actions.closeCamera()
                    }
                    RemoteCommand.Reload -> reload()
                    else -> Unit // Screen/brightness commands: ScreenControlViewModel's own collector.
                }
            }
        }
    }

    /** A user touch on the open camera screen: cancels its pending auto-close, if any. */
    fun onCameraScreenTouch() {
        cancelAutoClose()
    }

    private fun handleOpenCamera(command: RemoteCommand.OpenCamera) {
        if (actions.isDashboardEditing) return
        turnScreenOn()
        cancelAutoClose()
        actions.openCamera(command.entityId)
        val seconds = command.closeAfterSeconds ?: cameraCloseAfterSecondsProvider()
        if (seconds > 0) {
            autoCloseJob = scope.launch {
                delay(seconds * 1_000L)
                actions.closeCamera()
            }
        }
    }

    private fun cancelAutoClose() {
        autoCloseJob?.cancel()
        autoCloseJob = null
    }
}
