package com.whereareu.app.core.location

import com.whereareu.app.core.model.ReportFrequency
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveIntervalPolicyTest {

    @Test
    fun `standard frequency doubles when stationary`() {
        assertEquals(
            6 * 60_000L,
            AdaptiveIntervalPolicy.intervalFor(ReportFrequency.STANDARD, speedMetersPerSecond = 0f),
        )
    }

    @Test
    fun `standard frequency keeps base cadence while walking`() {
        assertEquals(
            3 * 60_000L,
            AdaptiveIntervalPolicy.intervalFor(ReportFrequency.STANDARD, speedMetersPerSecond = 1.4f),
        )
    }

    @Test
    fun `standard frequency speeds up when moving fast`() {
        assertEquals(
            90_000L,
            AdaptiveIntervalPolicy.intervalFor(ReportFrequency.STANDARD, speedMetersPerSecond = 12f),
        )
    }

    @Test
    fun `power saver stationary is capped at ten minutes`() {
        assertEquals(
            10 * 60_000L,
            AdaptiveIntervalPolicy.intervalFor(ReportFrequency.POWER_SAVER, speedMetersPerSecond = 0f),
        )
    }

    @Test
    fun `power saver fast movement uses half the base`() {
        assertEquals(
            5 * 60_000L,
            AdaptiveIntervalPolicy.intervalFor(ReportFrequency.POWER_SAVER, speedMetersPerSecond = 20f),
        )
    }

    @Test
    fun `high frequency never goes below one minute`() {
        assertEquals(
            60_000L,
            AdaptiveIntervalPolicy.intervalFor(ReportFrequency.HIGH, speedMetersPerSecond = 30f),
        )
        assertEquals(
            2 * 60_000L,
            AdaptiveIntervalPolicy.intervalFor(ReportFrequency.HIGH, speedMetersPerSecond = 0f),
        )
    }

    @Test
    fun `unknown speed is treated as stationary`() {
        assertEquals(
            6 * 60_000L,
            AdaptiveIntervalPolicy.intervalFor(
                frequency = ReportFrequency.STANDARD,
                speedMetersPerSecond = 12f,
                speedKnown = false,
            ),
        )
    }

    @Test
    fun `negative speed is clamped to stationary`() {
        assertEquals(
            6 * 60_000L,
            AdaptiveIntervalPolicy.intervalFor(ReportFrequency.STANDARD, speedMetersPerSecond = -1f),
        )
    }
}
