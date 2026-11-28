package com.whereareu.app.core.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

@Serializable
data class RegisterRequest(
    val username: String,
    val nickname: String,
    val password: String,
    val inviteCode: String? = null,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val nickname: String,
)

@Serializable
data class AuthResponse(
    val token: String,
    val user: UserDto,
    val familyId: String? = null,
)

@Serializable
data class MeResponse(
    val user: UserDto,
    val family: FamilySummaryDto? = null,
    val devices: List<DeviceDto> = emptyList(),
)

// ---------------------------------------------------------------------------
// Family
// ---------------------------------------------------------------------------

@Serializable
data class FamilySummaryDto(
    val id: String,
    val name: String,
    val inviteCode: String? = null,
    val role: String? = null,
)

@Serializable
data class CreateFamilyRequest(val name: String)

@Serializable
data class CreateFamilyResponse(val family: FamilySummaryDto)

@Serializable
data class JoinFamilyRequest(val code: String)

@Serializable
data class JoinFamilyResponse(val family: FamilySummaryDto)

@Serializable
data class FamilyDetailResponse(
    val family: FamilySummaryDto,
    val members: List<MemberDto> = emptyList(),
)

@Serializable
data class MemberDto(
    val userId: String,
    val username: String = "",
    val nickname: String = "",
    val role: String = "member",
    val sharingPaused: Boolean = false,
    val online: Boolean = false,
    val lastLocation: LocationSnapshotDto? = null,
)

@Serializable
data class ResetInviteResponse(
    val inviteCode: String,
    val expiresAt: String? = null,
)

@Serializable
data class SetSharingPausedRequest(val paused: Boolean)

@Serializable
data class SharingPausedResponse(val sharingPaused: Boolean)

// ---------------------------------------------------------------------------
// Devices
// ---------------------------------------------------------------------------

@Serializable
data class DeviceRegisterRequest(
    val name: String,
    val platform: String,
    val model: String,
    val osVersion: String,
    val appVersion: String,
)

@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val platform: String? = null,
    val model: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
    val online: Boolean = false,
    val lastSeenAt: String? = null,
    val lastLocation: LocationSnapshotDto? = null,
)

@Serializable
data class DeviceRegisterResponse(
    val device: DeviceDto,
    val deviceToken: String,
)

@Serializable
data class DeviceResponse(val device: DeviceDto)

@Serializable
data class RenameDeviceRequest(val name: String)

@Serializable
data class DevicesResponse(val devices: List<DeviceDto> = emptyList())

// ---------------------------------------------------------------------------
// Locations
// ---------------------------------------------------------------------------

@Serializable
data class LocationSnapshotDto(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val accuracy: Float? = null,
    val speed: Float? = null,
    val bearing: Float? = null,
    val battery: Int? = null,
    val provider: String? = null,
    val recordedAt: String? = null,
)

@Serializable
data class ReportPointDto(
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float,
    val battery: Int? = null,
    val provider: String,
    val recordedAt: String,
)

@Serializable
data class ReportLocationsRequest(
    val commandId: String? = null,
    val points: List<ReportPointDto>,
)

@Serializable
data class ReportLocationsResponse(val accepted: Int = 0)

@Serializable
data class HistoryPointDto(
    val id: String? = null,
    val lat: Double,
    val lng: Double,
    val accuracy: Float? = null,
    val speed: Float? = null,
    val bearing: Float? = null,
    val battery: Int? = null,
    val provider: String? = null,
    val recordedAt: String,
)

@Serializable
data class HistoryResponse(val points: List<HistoryPointDto> = emptyList())

@Serializable
data class LatestResponse(val members: List<LatestMemberDto> = emptyList())

@Serializable
data class LatestMemberDto(
    val userId: String,
    val deviceId: String? = null,
    val online: Boolean = false,
    val lastSeenAt: String? = null,
    val lastLocation: LocationSnapshotDto? = null,
)

// ---------------------------------------------------------------------------
// Locate commands
// ---------------------------------------------------------------------------

@Serializable
data class LocateResponse(
    val commandId: String,
    val deadlineAt: String? = null,
)

@Serializable
data class CommandStatusResponse(
    val status: String,
    val completedAt: String? = null,
)

// ---------------------------------------------------------------------------
// Follows & alerts
// ---------------------------------------------------------------------------

@Serializable
data class FollowTargetDto(
    val userId: String,
    val nickname: String = "",
)

@Serializable
data class AlertRuleDto(
    val id: String,
    val type: String,
    val enabled: Boolean = true,
    /** Type-specific JSON: thresholdMeters/cooldownMinutes/hours/percent. */
    val config: JsonObject? = null,
    val lastFiredAt: String? = null,
)

@Serializable
data class FollowDto(
    val id: String,
    val target: FollowTargetDto,
    val createdAt: String? = null,
    val rules: List<AlertRuleDto> = emptyList(),
)

@Serializable
data class FollowsResponse(val follows: List<FollowDto> = emptyList())

@Serializable
data class FollowResponse(val follow: FollowDto)

@Serializable
data class FollowerDto(
    val userId: String,
    val nickname: String = "",
    val since: String? = null,
)

@Serializable
data class FollowersResponse(val followers: List<FollowerDto> = emptyList())

@Serializable
data class CreateFollowRuleInput(
    val type: String,
    val config: JsonObject? = null,
)

@Serializable
data class CreateFollowRequest(
    val targetUserId: String,
    val rules: List<CreateFollowRuleInput> = emptyList(),
)

@Serializable
data class CreateRuleRequest(
    val type: String,
    val config: JsonObject? = null,
)

@Serializable
data class UpdateRuleRequest(
    val enabled: Boolean? = null,
    val config: JsonObject? = null,
)

@Serializable
data class RuleResponse(val rule: AlertRuleDto)

@Serializable
data class AlertTargetDto(
    val userId: String,
    val nickname: String = "",
)

@Serializable
data class AlertDto(
    val id: String,
    val type: String,
    val target: AlertTargetDto,
    val title: String,
    val body: String = "",
    val silent: Boolean = false,
    val data: JsonObject? = null,
    val occurredAt: String? = null,
    val createdAt: String? = null,
    val readAt: String? = null,
)

@Serializable
data class AlertsResponse(
    val alerts: List<AlertDto> = emptyList(),
    val unreadCount: Int = 0,
)

@Serializable
data class ReadAllResponse(val updated: Int = 0)

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

@Serializable
data class ApiErrorBody(
    val code: String = "UNKNOWN",
    val message: String = "",
)

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody)
