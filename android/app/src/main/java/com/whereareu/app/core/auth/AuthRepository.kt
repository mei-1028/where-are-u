package com.whereareu.app.core.auth

import android.util.Log
import com.whereareu.app.core.net.ApiException
import com.whereareu.app.core.net.ApiService
import com.whereareu.app.core.net.LoginRequest
import com.whereareu.app.core.net.MeResponse
import com.whereareu.app.core.net.RegisterRequest
import com.whereareu.app.core.net.safeApiCall
import com.whereareu.app.core.net.toResult

data class AuthSession(
    val familyId: String?,
    val onboardingDone: Boolean,
)

class AuthRepository(
    private val api: ApiService,
    private val store: SessionStore,
    private val deviceManager: DeviceManager,
    private val stopTracking: () -> Unit = {},
    private val clearOfflineQueue: suspend () -> Unit = {},
) {

    suspend fun login(username: String, password: String): Result<AuthSession> {
        val result = safeApiCall { api.login(LoginRequest(username.trim(), password)) }
        val response = result.getOrElse { error ->
            return Result.failure(error.asLoginError())
        }
        return persistSession(response.token, response.user, response.familyId)
    }

    suspend fun register(
        username: String,
        nickname: String,
        password: String,
        inviteCode: String?,
    ): Result<AuthSession> {
        val result = safeApiCall {
            api.register(
                RegisterRequest(
                    username = username.trim(),
                    nickname = nickname.trim(),
                    password = password,
                    inviteCode = inviteCode?.trim()?.takeIf { it.isNotBlank() },
                ),
            )
        }
        val response = result.getOrElse { error ->
            return Result.failure(error.asLoginError())
        }
        return persistSession(response.token, response.user, response.familyId)
    }

    private suspend fun persistSession(
        token: String,
        user: com.whereareu.app.core.net.UserDto,
        familyId: String?,
    ): Result<AuthSession> {
        return try {
            store.saveAuth(token, user, familyId)
            // Fill family name/role and validate the session. Offline login still
            // works for cached map data, so failure here is not fatal.
            safeApiCall { api.me() }
                .onSuccess { applyMe(it) }
                .onFailure { Log.w(TAG, "Post-login /me failed: ${it.message}") }
            deviceManager.ensureRegistered()
                .onFailure { Log.w(TAG, "Post-login device registration failed: ${it.message}") }

            val session = store.current()
            Result.success(
                AuthSession(
                    familyId = session.familyId,
                    onboardingDone = session.onboardingDone,
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to persist session", t)
            Result.failure(ApiException("UNKNOWN", "保存登录状态失败，请重试"))
        }
    }

    /** Refreshes /api/me and mirrors family info into local storage. */
    suspend fun refreshMe(): Result<MeResponse> {
        val result = safeApiCall { api.me() }
        result.onSuccess { applyMe(it) }
        return result
    }

    private suspend fun applyMe(me: MeResponse) {
        val family = me.family
        if (family == null) {
            store.clearFamily()
        } else {
            store.saveFamily(family.id, family.name, family.role)
        }
    }

    suspend fun ensureDeviceRegistered(force: Boolean = false): Result<String> =
        deviceManager.ensureRegistered(force = force)

    suspend fun saveServerUrl(raw: String) {
        store.setBaseUrl(raw)
    }

    /** Unbinds the current device (best effort) before clearing the session. */
    suspend fun logout() {
        val session = store.current()
        stopTracking()
        clearOfflineQueue()
        val deviceId = session.deviceId
        if (!deviceId.isNullOrBlank() && !session.userJwt.isNullOrBlank()) {
            safeApiCall { api.deleteDevice(deviceId).toResult().getOrThrow() }
                .onFailure { Log.w(TAG, "Device unbind on logout failed: ${it.message}") }
        }
        store.logout()
    }

    private fun Throwable.asLoginError(): Throwable {
        val apiError = this as? ApiException ?: return this
        return if (apiError.isUnauthorized) {
            ApiException(
                code = apiError.code,
                message = "用户名或密码错误",
                httpStatus = apiError.httpStatus,
                cause = apiError,
            )
        } else {
            apiError
        }
    }

    private companion object {
        const val TAG = "AuthRepository"
    }
}
