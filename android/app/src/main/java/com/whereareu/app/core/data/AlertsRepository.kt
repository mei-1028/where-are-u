package com.whereareu.app.core.data

import com.whereareu.app.core.net.AlertDto
import com.whereareu.app.core.net.AlertRuleDto
import com.whereareu.app.core.net.AlertsResponse
import com.whereareu.app.core.net.ApiService
import com.whereareu.app.core.net.CreateFollowRequest
import com.whereareu.app.core.net.CreateFollowRuleInput
import com.whereareu.app.core.net.CreateRuleRequest
import com.whereareu.app.core.net.FollowDto
import com.whereareu.app.core.net.FollowerDto
import com.whereareu.app.core.net.UpdateRuleRequest
import com.whereareu.app.core.net.safeApiCall
import com.whereareu.app.core.net.toResult
import kotlinx.serialization.json.JsonObject
import retrofit2.Response

/**
 * Follows and the alert inbox. Both use the user JWT (not the device token):
 * the inbox belongs to the person, the device socket is only the delivery
 * channel.
 */
class AlertsRepository(private val api: ApiService) {

    suspend fun alerts(
        limit: Int = 50,
        unreadOnly: Boolean = false,
        after: String? = null,
    ): Result<AlertsResponse> = safeApiCall {
        api.alerts(
            limit = limit,
            unread = if (unreadOnly) 1 else null,
            after = after,
        )
    }

    suspend fun markRead(alertId: String): Result<Unit> =
        unitCall { api.markAlertRead(alertId) }

    suspend fun markAllRead(): Result<Int> =
        safeApiCall { api.markAllAlertsRead().updated }

    suspend fun follows(): Result<List<FollowDto>> =
        safeApiCall { api.follows().follows }

    suspend fun followers(): Result<List<FollowerDto>> =
        safeApiCall { api.followers().followers }

    suspend fun createFollow(
        targetUserId: String,
        types: List<String>,
    ): Result<FollowDto> = safeApiCall {
        api.createFollow(
            CreateFollowRequest(
                targetUserId = targetUserId,
                rules = types.map { CreateFollowRuleInput(type = it) },
            ),
        ).follow
    }

    suspend fun deleteFollow(followId: String): Result<Unit> =
        unitCall { api.deleteFollow(followId) }

    suspend fun createRule(
        followId: String,
        type: String,
        config: JsonObject? = null,
    ): Result<AlertRuleDto> = safeApiCall {
        api.createRule(followId, CreateRuleRequest(type = type, config = config)).rule
    }

    suspend fun updateRule(
        followId: String,
        ruleId: String,
        enabled: Boolean? = null,
        config: JsonObject? = null,
    ): Result<AlertRuleDto> = safeApiCall {
        api.updateRule(
            followId,
            ruleId,
            UpdateRuleRequest(enabled = enabled, config = config),
        ).rule
    }

    suspend fun deleteRule(followId: String, ruleId: String): Result<Unit> =
        unitCall { api.deleteRule(followId, ruleId) }

    /** Newest unread alerts, used by the foreground service on first connect. */
    suspend fun unreadSummary(limit: Int = 20): Result<Pair<List<AlertDto>, Int>> =
        safeApiCall {
            val response = api.alerts(limit = limit, unread = 1)
            response.alerts to response.unreadCount
        }

    private suspend fun unitCall(block: suspend () -> Response<Unit>): Result<Unit> =
        safeApiCall { block().toResult().getOrThrow() }
}
