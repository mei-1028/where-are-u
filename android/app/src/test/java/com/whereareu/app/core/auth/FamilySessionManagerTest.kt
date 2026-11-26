package com.whereareu.app.core.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilySessionManagerTest {

    private class FakeRegistrar : DeviceRegistrar {
        var forceRequests = 0
        override suspend fun ensureRegistered(force: Boolean): Result<String> {
            if (force) forceRequests += 1
            return Result.success("new-token")
        }
    }

    @Test
    fun `joining a family forces device re-registration`() = runTest {
        val registrar = FakeRegistrar()
        var stopCalls = 0
        val manager = FamilySessionManager(
            deviceRegistrar = registrar,
            clearDevice = {},
            clearFamily = {},
            stopTracking = { stopCalls += 1 },
        )

        val result = manager.onJoinedFamily()

        assertTrue(result.isSuccess)
        assertEquals(1, registrar.forceRequests)
        assertEquals(0, stopCalls)
    }

    @Test
    fun `leave or removal stops tracking and clears device plus family`() = runTest {
        var deviceCleared = false
        var familyCleared = false
        var stopCalls = 0
        val manager = FamilySessionManager(
            deviceRegistrar = FakeRegistrar(),
            clearDevice = { deviceCleared = true },
            clearFamily = { familyCleared = true },
            stopTracking = { stopCalls += 1 },
        )

        manager.onLeftFamilyOrRemoved()

        assertEquals(1, stopCalls)
        assertTrue(deviceCleared)
        assertTrue(familyCleared)
    }
}
