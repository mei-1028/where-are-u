package com.whereareu.app.core.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.whereareu.app.BuildConfig
import com.whereareu.app.core.model.ReportFrequency
import com.whereareu.app.core.net.RuntimeConfig
import com.whereareu.app.core.net.ServerConfig
import com.whereareu.app.core.net.UserDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

data class SessionState(
    val userJwt: String? = null,
    val deviceToken: String? = null,
    val deviceId: String? = null,
    val userId: String? = null,
    val username: String = "",
    val nickname: String = "",
    val familyId: String? = null,
    val familyName: String = "",
    val familyRole: String = "member",
    val baseUrl: String = ServerConfig.normalize(BuildConfig.API_BASE_URL),
    val sharingEnabled: Boolean = true,
    val pendingSharingPause: Boolean = false,
    val reportFrequency: ReportFrequency = ReportFrequency.STANDARD,
    val onboardingDone: Boolean = false,
) {
    val hasSession: Boolean get() = !userJwt.isNullOrBlank()
    val isAdmin: Boolean get() = familyRole == "admin"
    val displayName: String get() = nickname.ifBlank { username.ifBlank { "家人" } }
}

/**
 * Single source of truth for persisted state. The user JWT and the device token
 * are stored under separate keys; only the JWT is used for user APIs, only the
 * device token is used for location reporting and the device WebSocket.
 */
class SessionStore(private val context: Context) : DeviceTokenStore {

    private object Keys {
        val USER_JWT = stringPreferencesKey("user_jwt")
        val DEVICE_TOKEN = stringPreferencesKey("device_token")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val USER_ID = stringPreferencesKey("user_id")
        val USERNAME = stringPreferencesKey("username")
        val NICKNAME = stringPreferencesKey("nickname")
        val FAMILY_ID = stringPreferencesKey("family_id")
        val FAMILY_NAME = stringPreferencesKey("family_name")
        val FAMILY_ROLE = stringPreferencesKey("family_role")
        val BASE_URL = stringPreferencesKey("base_url")
        val SHARING_ENABLED = booleanPreferencesKey("sharing_enabled")
        val PENDING_SHARING_PAUSE = booleanPreferencesKey("pending_sharing_pause")
        val REPORT_FREQUENCY = stringPreferencesKey("report_frequency")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }

    val state: Flow<SessionState> = context.sessionDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            val storedBaseUrl = prefs[Keys.BASE_URL]
            SessionState(
                userJwt = prefs[Keys.USER_JWT],
                deviceToken = prefs[Keys.DEVICE_TOKEN],
                deviceId = prefs[Keys.DEVICE_ID],
                userId = prefs[Keys.USER_ID],
                username = prefs[Keys.USERNAME].orEmpty(),
                nickname = prefs[Keys.NICKNAME].orEmpty(),
                familyId = prefs[Keys.FAMILY_ID],
                familyName = prefs[Keys.FAMILY_NAME].orEmpty(),
                familyRole = prefs[Keys.FAMILY_ROLE] ?: "member",
                baseUrl = ServerConfig.normalize(
                    storedBaseUrl?.takeIf { it.isNotBlank() } ?: BuildConfig.API_BASE_URL,
                ),
                sharingEnabled = prefs[Keys.SHARING_ENABLED] ?: true,
                pendingSharingPause = prefs[Keys.PENDING_SHARING_PAUSE] ?: false,
                reportFrequency = ReportFrequency.fromStored(prefs[Keys.REPORT_FREQUENCY]),
                onboardingDone = prefs[Keys.ONBOARDING_DONE] ?: false,
            )
        }
        .distinctUntilChanged()

    /**
     * Reading the session also seeds [RuntimeConfig]. This closes a cold-start
     * race where an interceptor could fire before the DataStore collector has
     * delivered the first value.
     */
    override suspend fun current(): SessionState = state.first().also { session ->
        RuntimeConfig.serverBaseUrl = session.baseUrl
        RuntimeConfig.userToken = session.userJwt
        RuntimeConfig.deviceToken = session.deviceToken
    }

    suspend fun saveAuth(token: String, user: UserDto, familyId: String?) {
        context.sessionDataStore.edit { prefs ->
            prefs[Keys.USER_JWT] = token
            prefs[Keys.USER_ID] = user.id
            prefs[Keys.USERNAME] = user.username
            prefs[Keys.NICKNAME] = user.nickname
            if (familyId.isNullOrBlank()) {
                prefs.remove(Keys.FAMILY_ID)
                prefs.remove(Keys.FAMILY_NAME)
                prefs.remove(Keys.FAMILY_ROLE)
            } else {
                prefs[Keys.FAMILY_ID] = familyId
                // Name/role are filled by GET /api/me right after auth.
                prefs.remove(Keys.FAMILY_NAME)
                prefs.remove(Keys.FAMILY_ROLE)
            }
        }
        RuntimeConfig.userToken = token
    }

    suspend fun saveFamily(id: String, name: String, role: String?) {
        val resolvedRole = role ?: "member"
        val session = current()
        if (session.familyId == id && session.familyName == name && session.familyRole == resolvedRole) {
            return
        }
        context.sessionDataStore.edit { prefs ->
            prefs[Keys.FAMILY_ID] = id
            prefs[Keys.FAMILY_NAME] = name
            prefs[Keys.FAMILY_ROLE] = resolvedRole
        }
    }

    suspend fun clearFamily() {
        context.sessionDataStore.edit { prefs ->
            prefs.remove(Keys.FAMILY_ID)
            prefs.remove(Keys.FAMILY_NAME)
            prefs.remove(Keys.FAMILY_ROLE)
        }
    }

    override suspend fun saveDevice(deviceId: String, deviceToken: String) {
        context.sessionDataStore.edit { prefs ->
            prefs[Keys.DEVICE_ID] = deviceId
            prefs[Keys.DEVICE_TOKEN] = deviceToken
        }
        RuntimeConfig.deviceToken = deviceToken
    }

    override suspend fun clearDevice() {
        context.sessionDataStore.edit { prefs ->
            prefs.remove(Keys.DEVICE_ID)
            prefs.remove(Keys.DEVICE_TOKEN)
            // A pending pause belonged to the previous family/device session.
            prefs.remove(Keys.PENDING_SHARING_PAUSE)
        }
        RuntimeConfig.deviceToken = null
    }

    suspend fun setBaseUrl(raw: String) {
        val normalized = ServerConfig.normalize(raw)
        context.sessionDataStore.edit { prefs -> prefs[Keys.BASE_URL] = normalized }
        RuntimeConfig.serverBaseUrl = normalized
    }

    suspend fun setSharingEnabled(enabled: Boolean) {
        context.sessionDataStore.edit { prefs -> prefs[Keys.SHARING_ENABLED] = enabled }
    }

    suspend fun setPendingSharingPause(pending: Boolean) {
        context.sessionDataStore.edit { prefs ->
            if (pending) {
                prefs[Keys.PENDING_SHARING_PAUSE] = true
            } else {
                prefs.remove(Keys.PENDING_SHARING_PAUSE)
            }
        }
    }

    suspend fun setReportFrequency(frequency: ReportFrequency) {
        context.sessionDataStore.edit { prefs -> prefs[Keys.REPORT_FREQUENCY] = frequency.name }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        context.sessionDataStore.edit { prefs -> prefs[Keys.ONBOARDING_DONE] = done }
    }

    /** Keeps the server address and onboarding/permission knowledge. */
    suspend fun logout() {
        context.sessionDataStore.edit { prefs ->
            prefs.remove(Keys.USER_JWT)
            prefs.remove(Keys.DEVICE_TOKEN)
            prefs.remove(Keys.DEVICE_ID)
            prefs.remove(Keys.USER_ID)
            prefs.remove(Keys.USERNAME)
            prefs.remove(Keys.NICKNAME)
            prefs.remove(Keys.FAMILY_ID)
            prefs.remove(Keys.FAMILY_NAME)
            prefs.remove(Keys.FAMILY_ROLE)
            prefs.remove(Keys.PENDING_SHARING_PAUSE)
            prefs[Keys.SHARING_ENABLED] = false
        }
        RuntimeConfig.userToken = null
        RuntimeConfig.deviceToken = null
    }
}
