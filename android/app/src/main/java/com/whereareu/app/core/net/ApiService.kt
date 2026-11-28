package com.whereareu.app.core.net

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * REST contract from docs/api-contract.md. Retrofit baseUrl is a placeholder
 * origin; the real host comes from [BaseUrlInterceptor] + RuntimeConfig and
 * the path always starts with /api/.
 */
interface ApiService {

    // Auth -------------------------------------------------------------------

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @GET("me")
    suspend fun me(): MeResponse

    // Family -----------------------------------------------------------------

    @POST("families")
    suspend fun createFamily(@Body body: CreateFamilyRequest): CreateFamilyResponse

    @POST("families/join")
    suspend fun joinFamily(@Body body: JoinFamilyRequest): JoinFamilyResponse

    @GET("families/me")
    suspend fun familyMe(): FamilyDetailResponse

    @POST("families/invite/reset")
    suspend fun resetInvite(): ResetInviteResponse

    @PATCH("families/me/sharing")
    suspend fun setSharingPaused(@Body body: SetSharingPausedRequest): SharingPausedResponse

    @DELETE("families/members/{userId}")
    suspend fun removeMember(@Path("userId") userId: String): Response<Unit>

    @POST("families/leave")
    suspend fun leaveFamily(): Response<Unit>

    // Devices ----------------------------------------------------------------

    @POST("devices")
    suspend fun registerDevice(@Body body: DeviceRegisterRequest): DeviceRegisterResponse

    @GET("devices")
    suspend fun devices(): DevicesResponse

    @PATCH("devices/{id}")
    suspend fun renameDevice(
        @Path("id") deviceId: String,
        @Body body: RenameDeviceRequest,
    ): DeviceResponse

    @DELETE("devices/{id}")
    suspend fun deleteDevice(@Path("id") deviceId: String): Response<Unit>

    // Locations --------------------------------------------------------------

    @POST("locations")
    suspend fun reportLocations(@Body body: ReportLocationsRequest): ReportLocationsResponse

    @GET("devices/{id}/locations")
    suspend fun locationHistory(
        @Path("id") deviceId: String,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("limit") limit: Int = 500,
    ): HistoryResponse

    @GET("families/me/latest")
    suspend fun latest(): LatestResponse

    // Locate now -------------------------------------------------------------

    @POST("devices/{id}/locate")
    suspend fun requestLocate(@Path("id") deviceId: String): LocateResponse

    @GET("commands/{id}")
    suspend fun commandStatus(@Path("id") commandId: String): CommandStatusResponse

    // Follows & alerts -------------------------------------------------------

    @GET("follows")
    suspend fun follows(): FollowsResponse

    @POST("follows")
    suspend fun createFollow(@Body body: CreateFollowRequest): FollowResponse

    @DELETE("follows/{id}")
    suspend fun deleteFollow(@Path("id") followId: String): Response<Unit>

    @GET("follows/followers")
    suspend fun followers(): FollowersResponse

    @POST("follows/{id}/rules")
    suspend fun createRule(
        @Path("id") followId: String,
        @Body body: CreateRuleRequest,
    ): RuleResponse

    @PATCH("follows/{id}/rules/{ruleId}")
    suspend fun updateRule(
        @Path("id") followId: String,
        @Path("ruleId") ruleId: String,
        @Body body: UpdateRuleRequest,
    ): RuleResponse

    @DELETE("follows/{id}/rules/{ruleId}")
    suspend fun deleteRule(
        @Path("id") followId: String,
        @Path("ruleId") ruleId: String,
    ): Response<Unit>

    @GET("alerts")
    suspend fun alerts(
        @Query("limit") limit: Int = 50,
        @Query("unread") unread: Int? = null,
        @Query("after") after: String? = null,
    ): AlertsResponse

    @POST("alerts/{id}/read")
    suspend fun markAlertRead(@Path("id") alertId: String): Response<Unit>

    @POST("alerts/read-all")
    suspend fun markAllAlertsRead(): ReadAllResponse
}
