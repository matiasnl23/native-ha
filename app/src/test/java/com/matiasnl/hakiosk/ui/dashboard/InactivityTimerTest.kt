package com.matiasnl.hakiosk.ui.dashboard

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InactivityTimerTest {

    private class Harness(scope: TestScope) {
        var fired = 0
        val timer = InactivityTimer(scope.backgroundScope, { scope.testScheduler.currentTime }) { fired++ }
    }

    @Test
    fun `fires once the timeout passes without activity`() = runTest {
        val harness = Harness(this)

        harness.timer.start(60_000)
        advanceTimeBy(59_999)
        assertEquals(0, harness.fired)

        advanceTimeBy(2)
        assertEquals(1, harness.fired)
    }

    @Test
    fun `activity restarts the countdown`() = runTest {
        val harness = Harness(this)
        harness.timer.start(60_000)

        advanceTimeBy(50_000)
        harness.timer.onActivity()
        advanceTimeBy(59_000) // 109 s since start, 59 s since the touch.
        assertEquals(0, harness.fired)

        advanceTimeBy(1_001)
        assertEquals(1, harness.fired)
    }

    @Test
    fun `fires once per idle period and the next touch starts a new countdown`() = runTest {
        val harness = Harness(this)
        harness.timer.start(1_000)

        advanceTimeBy(3_001)
        assertEquals(1, harness.fired)
        assertFalse(harness.timer.isRunning)

        harness.timer.onActivity()
        assertTrue(harness.timer.isRunning)
        advanceTimeBy(1_001)
        assertEquals(2, harness.fired)
    }

    @Test
    fun `activity after stop does not re-enable the timer`() = runTest {
        val harness = Harness(this)
        harness.timer.start(1_000)
        harness.timer.stop()

        harness.timer.onActivity()
        advanceTimeBy(5_000)

        assertFalse(harness.timer.isRunning)
        assertEquals(0, harness.fired)
    }

    @Test
    fun `stop and a zero timeout never fire`() = runTest {
        val harness = Harness(this)

        harness.timer.start(0)
        assertFalse(harness.timer.isRunning)
        harness.timer.start(1_000)
        assertTrue(harness.timer.isRunning)
        harness.timer.stop()
        advanceTimeBy(10_000)

        assertEquals(0, harness.fired)
        assertFalse(harness.timer.isRunning)
    }

    @Test
    fun `restarting counts from the restart`() = runTest {
        val harness = Harness(this)
        harness.timer.start(1_000)

        advanceTimeBy(900)
        harness.timer.start(1_000)
        advanceTimeBy(900)

        assertEquals(0, harness.fired)
    }
}
