package com.whereareu.app.core.data

import com.whereareu.app.core.auth.SessionStore
import com.whereareu.app.core.net.ApiService
import com.whereareu.app.core.net.CommandStatusResponse
import com.whereareu.app.core.net.CreateFamilyRequest
import com.whereareu.app.core.net.CreateFamilyResponse
import com.whereareu.app.core.net.DevicesResponse
import com.whereareu.app.core.net.FamilyDetailResponse
import com.whereareu.app.core.net.JoinFamilyRequest
import com.whereareu.app.core.net.JoinFamilyResponse
import com.whereareu.app.core.net.LatestResponse
import com.whereareu.app.core.net.LocateResponse
import com.whereareu.app.core.net.ResetInviteResponse
import com.whereareu.app.core.net.SetSharingPausedRequest
import com.whereareu.app.core.net.toResult
import com.whereareu.app.core.net.safeApiCall
import retrofit2.Response

class FamilyRepository(
    private val api: ApiService,
    private val store: SessionStore,
) {

    suspend fun familyDetail(): Result<FamilyDetailResponse> {
        val result = safeApiCall { api.familyMe() }
        result.onSuccess { detail ->
            store.saveFamily(
                id = detail.family.id,
                name = detail.family.name,
                role = detail.family.role,
            )
        }
        return result
    }

    suspend fun latest(): Result<LatestResponse> = safeApiCall { api.latest() }

    suspend fun createFamily(name: String): Result<CreateFamilyResponse> {
        val result = safeApiCall { api.createFamily(CreateFamilyRequest(name.trim())) }
        result.onSuccess { response ->
            store.saveFamily(response.family.id, response.family.name, response.family.role ?: "admin")
        }
        return result
    }

    suspend fun joinFamily(code: String): Result<JoinFamilyResponse> {
        val result = safeApiCall { api.joinFamily(JoinFamilyRequest(code.trim())) }
        result.onSuccess { response ->
            store.saveFamily(response.family.id, response.family.name, response.family.role ?: "member")
        }
        return result
    }

    suspend fun resetInvite(): Result<ResetInviteResponse> = safeApiCall { api.resetInvite() }

    /** PATCH /families/me/sharing. Returns the server's authoritative paused flag. */
    suspend fun setSharingPaused(paused: Boolean): Result<Boolean> =
        safeApiCall {
            api.setSharingPaused(SetSharingPausedRequest(paused = paused)).sharingPaused
        }

    suspend fun removeMember(userId: String): Result<Unit> = unitCall { api.removeMember(userId) }

    suspend fun leaveFamily(): Result<Unit> {
        val result = unitCall { api.leaveFamily() }
        result.onSuccess {
            // Device credentials are family-scoped; force a fresh registration
            // when joining another family.
            store.clearDevice()
            store.clearFamily()
        }
        return result
    }

    suspend fun devices(): Result<DevicesResponse> = safeApiCall { api.devices() }

    suspend fun requestLocate(deviceId: String): Result<LocateResponse> =
        safeApiCall { api.requestLocate(deviceId) }

    suspend fun commandStatus(commandId: String): Result<CommandStatusResponse> =
        safeApiCall { api.commandStatus(commandId) }

    private suspend fun unitCall(block: suspend () -> Response<Unit>): Result<Unit> =
        safeApiCall { block().toResult().getOrThrow() }
}
