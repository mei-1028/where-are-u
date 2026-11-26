package com.whereareu.app.core.location

import com.whereareu.app.core.net.ApiException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingControllerTest {

    private class Harness(
        initialLocalEnabled: Boolean,
        startAccepted: Boolean = true,
        startRunning: Boolean = true,
    ) {
        var localEnabled: Boolean = initialLocalEnabled
        var pendingPause: Boolean = false
        var startAcceptedResult: Boolean = startAccepted
        var awaitRunningResult: Boolean = startRunning
        var startCalls: Int = 0
        var awaitCalls: Int = 0
        var stopCalls: Int = 0
        var retryCalls: Int = 0
        var clearPendingCalls: Int = 0
        val remoteCalls = mutableListOf<Boolean>()
        var activeRemoteCalls: Int = 0
        var maxConcurrentRemoteCalls: Int = 0
        var remoteHandler: suspend (paused: Boolean) -> Result<Boolean> = { paused ->
            Result.success(paused)
        }

        val controller = SharingController(
            setRemotePaused = { paused ->
                activeRemoteCalls += 1
                maxConcurrentRemoteCalls = maxOf(maxConcurrentRemoteCalls, activeRemoteCalls)
                remoteCalls += paused
                try {
                    remoteHandler(paused)
                } finally {
                    activeRemoteCalls -= 1
                }
            },
            readLocalSharingEnabled = { localEnabled },
            saveLocalSharingEnabled = { enabled -> localEnabled = enabled },
            startTracking = {
                startCalls += 1
                startAcceptedResult
            },
            awaitTrackingRunning = {
                awaitCalls += 1
                awaitRunningResult
            },
            stopTracking = { stopCalls += 1 },
            enqueueServerPauseRetry = {
                retryCalls += 1
                pendingPause = true
            },
            clearServerSyncPending = {
                clearPendingCalls += 1
                pendingPause = false
            },
            hasPendingServerPause = { pendingPause },
        )
    }

    @Test
    fun `pause success updates local state and stops tracking`() = runTest {
        val harness = Harness(initialLocalEnabled = true)

        val result = harness.controller.setSharingEnabled(false)

        assertTrue(result.isSuccess)
        assertEquals(false, result.getOrThrow())
        assertEquals(listOf(true), harness.remoteCalls)
        assertFalse(harness.localEnabled)
        assertEquals(0, harness.startCalls)
        assertEquals(1, harness.stopCalls)
    }

    @Test
    fun `resume success updates local state and starts tracking`() = runTest {
        val harness = Harness(initialLocalEnabled = false)

        val result = harness.controller.setSharingEnabled(true)

        assertTrue(result.isSuccess)
        assertEquals(true, result.getOrThrow())
        assertEquals(listOf(false), harness.remoteCalls)
        assertTrue(harness.localEnabled)
        assertEquals(1, harness.startCalls)
        assertEquals(1, harness.awaitCalls)
        assertEquals(0, harness.stopCalls)
    }

    @Test
    fun `PATCH business failure leaves local state and tracking untouched`() = runTest {
        val harness = Harness(initialLocalEnabled = true)
        harness.remoteHandler = {
            Result.failure(ApiException("FORBIDDEN", "没有权限", 403))
        }

        val result = harness.controller.setSharingEnabled(false)

        assertTrue(result.isFailure)
        assertEquals("FORBIDDEN", (result.exceptionOrNull() as ApiException).code)
        assertTrue(harness.localEnabled)
        assertEquals(0, harness.startCalls)
        assertEquals(0, harness.stopCalls)
        assertEquals(0, harness.retryCalls)
        assertEquals(listOf(true), harness.remoteCalls)
    }

    @Test
    fun `pause network failure pauses locally first and queues server retry`() = runTest {
        val harness = Harness(initialLocalEnabled = true)
        harness.remoteHandler = {
            Result.failure(ApiException("NETWORK", "网络不可达", httpStatus = 0))
        }

        val result = harness.controller.setSharingEnabled(false)

        assertTrue(result.isSuccess)
        assertEquals(false, result.getOrThrow())
        assertFalse(harness.localEnabled)
        assertEquals(1, harness.stopCalls)
        assertEquals(1, harness.retryCalls)
        assertTrue(harness.pendingPause)
        assertEquals(0, harness.startCalls)
    }

    @Test
    fun `pause request timeout still pauses locally immediately`() = runTest {
        val harness = Harness(initialLocalEnabled = true)
        harness.remoteHandler = {
            delay(10_000)
            Result.success(true)
        }

        val result = harness.controller.setSharingEnabled(false)

        assertTrue(result.isSuccess)
        assertEquals(false, result.getOrThrow())
        assertFalse(harness.localEnabled)
        assertEquals(1, harness.stopCalls)
        assertEquals(1, harness.retryCalls)
        assertTrue(harness.pendingPause)
    }

    @Test
    fun `resume with FGS receipt failure rolls the server back to paused`() = runTest {
        val harness = Harness(
            initialLocalEnabled = false,
            startAccepted = true,
            startRunning = false,
        )

        val result = harness.controller.setSharingEnabled(true)

        assertTrue(result.isFailure)
        assertEquals(
            "SHARING_START_FAILED",
            (result.exceptionOrNull() as ApiException).code,
        )
        // First resume (paused=false), then rollback (paused=true).
        assertEquals(listOf(false, true), harness.remoteCalls)
        assertFalse(harness.localEnabled)
        assertEquals(1, harness.startCalls)
        assertEquals(1, harness.awaitCalls)
        assertEquals(1, harness.stopCalls)
    }

    @Test
    fun `concurrent toggles are serialized`() = runTest {
        val harness = Harness(initialLocalEnabled = true)
        harness.remoteHandler = { paused ->
            // Suspension point makes an unserialized implementation overlap here.
            delay(10)
            Result.success(paused)
        }

        val jobs = (0 until 6).map { index ->
            launch {
                harness.controller.setSharingEnabled(index % 2 == 0)
            }
        }
        jobs.forEach { it.join() }

        assertEquals(1, harness.maxConcurrentRemoteCalls)
        assertEquals(6, harness.remoteCalls.size)
        // The last server call determines the final local mirror.
        assertEquals(!harness.remoteCalls.last(), harness.localEnabled)
    }

    @Test
    fun `server pause calibration turns local sharing off and stops tracking`() = runTest {
        val harness = Harness(initialLocalEnabled = true)

        val effective = harness.controller.applyServerSharingPaused(paused = true)

        assertEquals(false, effective)
        assertFalse(harness.localEnabled)
        assertEquals(1, harness.stopCalls)
        assertTrue(harness.remoteCalls.isEmpty())
    }

    @Test
    fun `calibration is skipped while a local pause awaits server sync`() = runTest {
        val harness = Harness(initialLocalEnabled = false)
        harness.pendingPause = true

        val effective = harness.controller.applyServerSharingPaused(paused = false)

        assertNull(effective)
        assertFalse(harness.localEnabled)
        assertEquals(0, harness.stopCalls)
        assertTrue(harness.remoteCalls.isEmpty())
    }
}
