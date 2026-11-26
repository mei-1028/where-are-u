package com.whereareu.app.core.auth

import com.whereareu.app.core.net.ApiException
import com.whereareu.app.core.net.DeviceDto
import com.whereareu.app.core.net.DeviceRegisterRequest
import com.whereareu.app.core.net.DeviceRegisterResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class DeviceManagerTest {

    private class FakeDeviceApi : DeviceApi {
        var deleted = mutableListOf<String>()
        var registerCalls = 0
        var registerError: Throwable? = null
        var registerResponse = DeviceRegisterResponse(
            device = DeviceDto(id = "dev_new", name = "phone"),
            deviceToken = "token_new",
        )

        override suspend fun deleteDevice(deviceId: String): Response<Unit> {
            deleted += deviceId
            return Response.success(Unit)
        }

        override suspend fun registerDevice(request: DeviceRegisterRequest): DeviceRegisterResponse {
            registerCalls += 1
            registerError?.let { throw it }
            return registerResponse
        }
    }

    private class FakeStore(state: SessionState) : DeviceTokenStore {
        var state: SessionState = state
            private set

        override suspend fun current(): SessionState = state

        override suspend fun saveDevice(deviceId: String, deviceToken: String) {
            state = state.copy(deviceId = deviceId, deviceToken = deviceToken)
        }

        override suspend fun clearDevice() {
            state = state.copy(deviceId = null, deviceToken = null)
        }
    }

    private fun session(
        deviceId: String? = null,
        deviceToken: String? = null,
        userJwt: String? = "user-jwt",
    ) = SessionState(deviceId = deviceId, deviceToken = deviceToken, userJwt = userJwt)

    @Test
    fun `force registration deletes the old device and saves a fresh token`() = runTest {
        val api = FakeDeviceApi()
        val store = FakeStore(session(deviceId = "dev_old", deviceToken = "old"))
        val manager = DeviceManager(api, store)

        val result = manager.ensureRegistered(force = true)

        assertTrue(result.isSuccess)
        assertEquals("token_new", result.getOrThrow())
        assertEquals(listOf("dev_old"), api.deleted)
        assertEquals(1, api.registerCalls)
        assertEquals("dev_new", store.state.deviceId)
        assertEquals("token_new", store.state.deviceToken)
    }

    @Test
    fun `non-force registration reuses an existing device token`() = runTest {
        val api = FakeDeviceApi()
        val store = FakeStore(session(deviceId = "dev_old", deviceToken = "old"))
        val manager = DeviceManager(api, store)

        val result = manager.ensureRegistered()

        assertEquals("old", result.getOrThrow())
        assertEquals(0, api.registerCalls)
        assertTrue(api.deleted.isEmpty())
    }

    @Test
    fun `non-force registration registers when no token exists`() = runTest {
        val api = FakeDeviceApi()
        val store = FakeStore(session())
        val manager = DeviceManager(api, store)

        val result = manager.ensureRegistered()

        assertEquals("token_new", result.getOrThrow())
        assertEquals(1, api.registerCalls)
        assertEquals("dev_new", store.state.deviceId)
    }

    @Test
    fun `failed force registration leaves no stale local device token`() = runTest {
        val api = FakeDeviceApi().apply {
            registerError = ApiException("NETWORK", "网络不可达", httpStatus = 0)
        }
        val store = FakeStore(session(deviceId = "dev_old", deviceToken = "old"))
        val manager = DeviceManager(api, store)

        val result = manager.ensureRegistered(force = true)

        assertTrue(result.isFailure)
        assertNull(store.state.deviceId)
        assertNull(store.state.deviceToken)
        assertEquals(listOf("dev_old"), api.deleted)
    }

    @Test
    fun `registration without a user session fails without API calls`() = runTest {
        val api = FakeDeviceApi()
        val store = FakeStore(session(userJwt = null))
        val manager = DeviceManager(api, store)

        val result = manager.ensureRegistered(force = true)

        assertTrue(result.isFailure)
        assertEquals(0, api.registerCalls)
    }
}
