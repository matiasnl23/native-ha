package com.matiasnl.hakiosk.data.ha.ws

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Delay before reconnection attempt number [attempt] (0-based). */
fun interface Backoff {
    fun delayMillis(attempt: Int): Long
}

/**
 * Exponential backoff with symmetric jitter: `initial * multiplier^attempt`, capped at [maxMillis],
 * then randomized by +/- [jitterRatio] (still never above [maxMillis]). Jitter avoids several
 * tablets hammering HA at the same instant after it restarts.
 */
class ExponentialBackoff(
    private val initialMillis: Long = 1_000,
    private val maxMillis: Long = 60_000,
    private val multiplier: Double = 2.0,
    private val jitterRatio: Double = 0.2,
    private val random: Random = Random.Default,
) : Backoff {
    override fun delayMillis(attempt: Int): Long {
        val exponential = initialMillis * multiplier.pow(attempt.coerceIn(0, 30))
        val base = min(maxMillis.toDouble(), exponential)
        val jitter = base * jitterRatio * (random.nextDouble() * 2 - 1)
        return (base + jitter).toLong().coerceIn(0, maxMillis)
    }
}
