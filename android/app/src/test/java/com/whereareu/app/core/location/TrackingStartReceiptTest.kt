package com.whereareu.app.core.location

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingStartReceiptTest {

    @Test
    fun `returns true immediately when the service is already running`() = runTest {
        val received = TrackingStartReceipt.awaitRunning(
            isRunning = { true },
            timeoutMillis = 1_000,
            pollIntervalMillis = 100,
        )
        assertTrue(received)
    }

    @Test
    fun `waits for the service receipt before reporting success`() = runTest {
        var checks = 0
        val received = TrackingStartReceipt.awaitRunning(
            isRunning = {
                checks += 1
                checks >= 3
            },
            timeoutMillis = 1_000,
            pollIntervalMillis = 100,
        )
        assertTrue(received)
        assertTrue(checks >= 3)
    }

    @Test
    fun `times out when startForeground never acknowledges`() = runTest {
        val received = TrackingStartReceipt.awaitRunning(
            isRunning = { false },
            timeoutMillis = 500,
            pollIntervalMillis = 100,
        )
        assertFalse(received)
    }
}
