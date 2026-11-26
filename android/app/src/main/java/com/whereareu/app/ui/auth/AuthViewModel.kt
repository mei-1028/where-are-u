package com.whereareu.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whereareu.app.core.auth.AuthRepository
import com.whereareu.app.core.auth.AuthSession
import com.whereareu.app.core.auth.SessionStore
import com.whereareu.app.core.net.RuntimeConfig
import com.whereareu.app.core.net.ServerConfig
import com.whereareu.app.core.net.toUserMessage
import com.whereareu.app.ui.nav.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val navigateTo: String? = null,
    val serverUrl: String = RuntimeConfig.serverBaseUrl,
)

class AuthViewModel(
    private val authRepository: AuthRepository,
    private val sessionStore: SessionStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun login(username: String, password: String) {
        if (_uiState.value.loading) return
        if (username.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(error = "请输入用户名和密码") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null, navigateTo = null) }
            authRepository.login(username, password).fold(
                onSuccess = { session -> onAuthenticated(session) },
                onFailure = { error ->
                    _uiState.update { it.copy(loading = false, error = error.toUserMessage()) }
                },
            )
        }
    }

    fun register(
        username: String,
        nickname: String,
        password: String,
        inviteCode: String?,
    ) {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null, navigateTo = null) }
            authRepository.register(username, nickname, password, inviteCode).fold(
                onSuccess = { session -> onAuthenticated(session) },
                onFailure = { error ->
                    _uiState.update { it.copy(loading = false, error = error.toUserMessage()) }
                },
            )
        }
    }

    private suspend fun onAuthenticated(session: AuthSession) {
        val onboardingDone = sessionStore.current().onboardingDone
        val route = when {
            session.familyId.isNullOrBlank() -> Routes.FAMILY_SETUP
            !onboardingDone -> Routes.ONBOARDING
            else -> Routes.MAIN
        }
        _uiState.update { it.copy(loading = false, navigateTo = route) }
    }

    fun saveServerUrl(raw: String) {
        val normalized = ServerConfig.normalize(raw)
        // Apply immediately so the next login cannot race the DataStore write.
        RuntimeConfig.serverBaseUrl = normalized
        _uiState.update { it.copy(serverUrl = normalized, error = null) }
        viewModelScope.launch {
            authRepository.saveServerUrl(normalized)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun consumeNavigation() {
        _uiState.update { it.copy(navigateTo = null) }
    }
}
