package com.whereareu.app.core.auth

import android.os.Build
import android.util.Log
import com.whereareu.app.BuildConfig
import com.whereareu.app.core.net.ApiException
import com.whereareu.app.core.net.ApiService
import com.whereareu.app.core.net.DeviceRegisterRequest
import com.whereareu.app.core.net.DeviceRegisterResponse
import com.whereareu.app.core.net.safeApiCall
import com.whereareu.app.core.net.toResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response

object DeviceInfo {
    fun displayName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return when {
            manufacturer.isBlank() && model.isBlank() -> "Android 手机"
            manufacturer.isBlank() -> model
            model.isBlank() -> manufacturer
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
    }

    fun osVersion(): String = "Android ${Build.VERSION.RELEASE}"
    fun appVersion(): String = BuildConfig.VERSION_NAME
}

interface DeviceRegistrar {
    suspend fun ensureRegistered(force: Boolean = false): Result<String>
}

/** Narrow persistence boundary so DeviceManager is unit-testable. */
interface DeviceTokenStore {
    suspend fun current(): SessionState
    suspend fun saveDevice(deviceId: String, deviceToken: String)
    suspend fun clearDevice()
}

/** Narrow API boundary for device registration / unbind. */
interface DeviceApi {
    suspend fun registerDevice(request: DeviceRegisterRequest): DeviceRegisterResponse
    suspend fun deleteDevice(deviceId: String): Response<Unit>
}

class ApiDeviceApi(private val api: ApiService) : DeviceApi {
    override suspend fun registerDevice(request: DeviceRegisterRequest): DeviceRegisterResponse =
        api.registerDevice(request)

    override suspend fun deleteDevice(deviceId: String): Response<Unit> =
        api.deleteDevice(deviceId)
}

/**
 * Registers this phone and persists the one-time deviceToken. Safe to call
 * repeatedly; concurrent calls are serialized.
 *
 * [force] is used after create/join: the previous device binding (if any) is
 * deleted server-side and locally, then a fresh deviceToken is issued so the
 * server associates the device with the current family.
 */
class DeviceManager(
    private val api: DeviceApi,
    private val store: DeviceTokenStore,
) : DeviceRegistrar {
    private val mutex = Mutex()

    override suspend fun ensureRegistered(force: Boolean): Result<String> = mutex.withLock {
        val session = store.current()
        if (session.userJwt.isNullOrBlank()) {
            return@withLock Result.failure(ApiException("UNAUTHORIZED", "请先登录", 401))
        }

        if (!force) {
            val existing = session.deviceToken
            if (!existing.isNullOrBlank() && !session.deviceId.isNullOrBlank()) {
                return@withLock Result.success(existing)
            }
        } else {
            val oldDeviceId = session.deviceId
            if (!oldDeviceId.isNullOrBlank()) {
                // Best effort: unbind the old device before creating the new one.
                safeApiCall { api.deleteDevice(oldDeviceId).toResult().getOrThrow() }
                    .onFailure { Log.w(TAG, "Old device unbind failed: ${it.message}") }
            }
            store.clearDevice()
        }

        val request = DeviceRegisterRequest(
            name = DeviceInfo.displayName(),
            platform = "android",
            model = Build.MODEL.orEmpty().ifBlank { "Android" },
            osVersion = DeviceInfo.osVersion(),
            appVersion = DeviceInfo.appVersion(),
        )
        val result = safeApiCall { api.registerDevice(request) }
        result.fold(
            onSuccess = { response ->
                store.saveDevice(response.device.id, response.deviceToken)
                Log.i(TAG, "Device registered: ${response.device.id}")
                Result.success(response.deviceToken)
            },
            onFailure = { error ->
                Log.w(TAG, "Device registration failed: ${error.message}")
                Result.failure(error)
            },
        )
    }

    private companion object {
        const val TAG = "DeviceManager"
    }
}
