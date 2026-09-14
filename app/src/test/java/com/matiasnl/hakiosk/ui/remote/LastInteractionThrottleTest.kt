package com.matiasnl.hakiosk.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastInteractionThrottleTest {

    private class FakeClock(var now: Long = 0L) {
        val fn: () -> Long = { now }
    }

    @Test
    fun `starts unset`() {
        val throttle = LastInteractionThrottle(FakeClock().fn)

        assertNull(throttle.lastInteractionEpochMillis.value)
    }

    @Test
    fun `the first touch ever is reported immediately`() {
        val clock = FakeClock(1_000)
        val throttle = LastInteractionThrottle(clock.fn, throttleMillis = 10_000)

        throttle.onActivity()

        assertEquals(1_000L, throttle.lastInteractionEpochMillis.value)
    }

    @Test
    fun `touches within the throttle window are ignored`() {
        val clock = FakeClock(0)
        val throttle = LastInteractionThrottle(clock.fn, throttleMillis = 10_000)
        throttle.onActivity()

        clock.now = 5_000
        throttle.onActivity()

        assertEquals(0L, throttle.lastInteractionEpochMillis.value)
    }

    @Test
    fun `a touch after the throttle window updates again`() {
        val clock = FakeClock(0)
        val throttle = LastInteractionThrottle(clock.fn, throttleMillis = 10_000)
        throttle.onActivity()

        clock.now = 10_000
        throttle.onActivity()

        assertEquals(10_000L, throttle.lastInteractionEpochMillis.value)
    }

    @Test
    fun `forceImmediate always updates even inside the throttle window`() {
        val clock = FakeClock(0)
        val throttle = LastInteractionThrottle(clock.fn, throttleMillis = 10_000)
        throttle.onActivity()

        clock.now = 1_000
        throttle.onActivity(forceImmediate = true)

        assertEquals(1_000L, throttle.lastInteractionEpochMillis.value)
    }
}
