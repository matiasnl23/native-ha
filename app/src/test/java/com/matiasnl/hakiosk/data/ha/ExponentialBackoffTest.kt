package com.matiasnl.hakiosk.data.ha

import com.matiasnl.hakiosk.data.ha.ws.ExponentialBackoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ExponentialBackoffTest {
    @Test
    fun growsExponentiallyAndCapsWithoutJitter() {
        val backoff = ExponentialBackoff(initialMillis = 1_000, maxMillis = 60_000, jitterRatio = 0.0)
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            (0..7).map(backoff::delayMillis),
        )
        assertEquals(60_000L, backoff.delayMillis(10_000))
    }

    @Test
    fun jitterStaysWithinBoundsAndCap() {
        val backoff = ExponentialBackoff(jitterRatio = 0.2, random = Random(42))
        repeat(200) {
            val first = backoff.delayMillis(0)
            assertTrue(first in 800L..1_200L)
            assertTrue(backoff.delayMillis(20) in 48_000L..60_000L)
        }
    }
}
