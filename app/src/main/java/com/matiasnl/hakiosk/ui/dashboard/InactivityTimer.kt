package com.matiasnl.hakiosk.ui.dashboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Calls [onTimeout] once a timeout passes without [onActivity]. After firing it stays idle (no
 * wakeups at all) until the next activity starts a new countdown. Used for the kiosk "return to the
 * first view after inactivity" setting.
 *
 * Cheap to feed from every pointer event: while counting down, [onActivity] only stores [clock]'s
 * time. A single coroutine sleeps until the deadline, re-checks the last activity time on waking and
 * sleeps again if there was any, so touches never cancel or launch coroutines (only the first touch
 * after a timeout launches the next countdown).
 *
 * [clock] must be monotonic milliseconds on the same timeline as [scope]'s `delay` (virtual time in
 * tests). All calls happen on [scope]'s (main) thread.
 */
class InactivityTimer(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val onTimeout: () -> Unit,
) {
    private var job: Job? = null
    private var timeoutMillis = 0L
    private var lastActivityAt = 0L

    /** True while a countdown is pending (enabled and not yet fired since the last activity). */
    val isRunning: Boolean get() = job?.isActive == true

    /** Enables the timer with [timeoutMillis], counting from now; zero or less disables it. */
    fun start(timeoutMillis: Long) {
        stop()
        if (timeoutMillis <= 0) return
        this.timeoutMillis = timeoutMillis
        lastActivityAt = clock()
        launchCountdown()
    }

    /** Disables the timer; [onActivity] does nothing until the next [start]. */
    fun stop() {
        job?.cancel()
        job = null
        timeoutMillis = 0
    }

    /** A touch happened: the countdown starts over. */
    fun onActivity() {
        lastActivityAt = clock()
        if (timeoutMillis > 0 && !isRunning) launchCountdown()
    }

    private fun launchCountdown() {
        val timeout = timeoutMillis
        job = scope.launch {
            while (true) {
                val remaining = lastActivityAt + timeout - clock()
                if (remaining <= 0) break
                delay(remaining)
            }
            onTimeout()
        }
    }
}
