package com.whereareu.app.core.ws

import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with symmetric jitter.
 *
 * attempt 0 -> ~initialDelay, attempt grows by [multiplier] until [maxDelayMs].
 * Pure and deterministic when a seeded [Random] is injected, which keeps the
 * reconnect policy unit-testable without a real socket.
 */
class BackoffPolicy(
    private val initialDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 60_000L,
    private val multiplier: Double = 2.0,
    private val jitterFraction: Double = 0.2,
    private val random: Random = Random.Default,
) {
    init {
        require(initialDelayMs > 0) { "initialDelayMs must be > 0" }
        require(maxDelayMs >= initialDelayMs) { "maxDelayMs must be >= initialDelayMs" }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0" }
        require(jitterFraction in 0.0..1.0) { "jitterFraction must be in [0, 1]" }
    }

    fun nextDelay(attempt: Int): Long {
        val exponent = attempt.coerceIn(0, MAX_EXPONENT)
        val raw = (initialDelayMs * multiplier.pow(exponent.toDouble()))
            .coerceAtMost(maxDelayMs.toDouble())
        val jitter = raw * jitterFraction
        val value = raw - jitter + random.nextDouble() * 2.0 * jitter
        return value.toLong().coerceIn(0L, maxDelayMs)
    }

    private companion object {
        const val MAX_EXPONENT = 30
    }
}
