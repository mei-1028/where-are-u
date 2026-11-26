package com.whereareu.app.core.location

import com.whereareu.app.core.model.ReportFrequency

/**
 * Battery-first cadence policy (pure, unit-tested):
 *  - stationary (< 0.8 m/s): double the user's base interval, capped at 10 min
 *  - walking/driving (< 5 m/s): base interval
 *  - fast movement: half base, never below 1 min
 *
 * So "标准" means 3 min while moving, 6 min while parked.
 */
object AdaptiveIntervalPolicy {

    const val STATIONARY_SPEED_MPS = 0.8f
    const val MOVING_SPEED_MPS = 5.0f
    const val MAX_INTERVAL_MS = 10 * 60_000L
    const val MIN_INTERVAL_MS = 60_000L

    fun intervalFor(
        frequency: ReportFrequency,
        speedMetersPerSecond: Float,
        speedKnown: Boolean = true,
    ): Long {
        val base = frequency.baseIntervalMillis
        val speed = if (speedKnown) speedMetersPerSecond.coerceAtLeast(0f) else 0f
        return when {
            speed < STATIONARY_SPEED_MPS -> minOf(MAX_INTERVAL_MS, base * 2)
            speed < MOVING_SPEED_MPS -> base
            else -> maxOf(MIN_INTERVAL_MS, base / 2)
        }
    }
}
