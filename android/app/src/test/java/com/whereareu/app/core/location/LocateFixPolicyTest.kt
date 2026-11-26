package com.whereareu.app.core.location

import com.whereareu.app.core.model.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocateFixPolicyTest {

    private fun fix(recordedAt: Long, provider: String = "test") = LocationFix(
        lat = 30.0,
        lng = 120.0,
        accuracy = 10f,
        speed = 0f,
        bearing = 0f,
        provider = provider,
        recordedAt = recordedAt,
    )

    @Test
    fun `fresh one-shot wins`() {
        val selected = LocateFixPolicy.selectForCommand(
            oneShot = fix(1_100, "gps"),
            latestContinuous = fix(1_050, "amap"),
            commandReceivedAt = 1_000,
            now = 1_200,
        )

        assertEquals("gps", selected?.provider)
    }

    @Test
    fun `stale one-shot falls back to a continuous fix newer than the command`() {
        val selected = LocateFixPolicy.selectForCommand(
            oneShot = fix(900, "gps"),
            latestContinuous = fix(1_050, "amap"),
            commandReceivedAt = 1_000,
            now = 1_200,
        )

        assertEquals("amap", selected?.provider)
    }

    @Test
    fun `continuous fix older than the command is rejected`() {
        val selected = LocateFixPolicy.selectForCommand(
            oneShot = null,
            latestContinuous = fix(900, "amap"),
            commandReceivedAt = 1_000,
            now = 1_100,
        )

        assertNull(selected)
    }

    @Test
    fun `continuous fix older than the freshness window is rejected`() {
        val selected = LocateFixPolicy.selectForCommand(
            oneShot = null,
            latestContinuous = fix(1_000, "amap"),
            commandReceivedAt = 1_000,
            now = 1_000 + LocateFixPolicy.MAX_CONTINUOUS_FIX_AGE_MS + 1,
        )

        assertNull(selected)
    }

    @Test
    fun `no candidate yields no report`() {
        val selected = LocateFixPolicy.selectForCommand(
            oneShot = null,
            latestContinuous = null,
            commandReceivedAt = 1_000,
            now = 1_100,
        )

        assertNull(selected)
    }
}
