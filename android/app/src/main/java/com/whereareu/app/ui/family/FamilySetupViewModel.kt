package com.whereareu.app.ui.family

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whereareu.app.core.auth.AuthRepository
import com.whereareu.app.core.auth.FamilySessionManager
import com.whereareu.app.core.auth.SessionStore
import com.whereareu.app.core.data.FamilyRepository
import com.whereareu.app.core.net.ApiException
import com.whereareu.app.core.net.toUserMessage
import com.whereareu.app.ui.nav.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FamilySetupUiState(
    val displayName: String = "",
    val loading: Boolean = false,
    val busyAction: String? = null,
    val error: String? = null,
    val navigateTo: String? = null,
)

class FamilySetupViewModel(
    private val familyRepository: FamilyRepository,
    private val authRepository: AuthRepository,
    private val sessionStore: SessionStore,
    private val familySessionManager: FamilySessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FamilySetupUiState())
    val uiState: StateFlow<FamilySetupUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(displayName = sessionStore.current().displayName) }
        }
    }

    fun createFamily(name: String) {
        if (_uiState.value.loading) return
        if (name.isBlank()) {
            _uiState.update { it.copy(error = "请输入家庭名称") }
            return
        }
        runAction("create") {
            familyRepository.createFamily(name)
        }
    }

    fun joinFamily(code: String) {
        if (_uiState.value.loading) return
        if (!code.matches(Regex("\\d{6}"))) {
            _uiState.update { it.copy(error = "请输入 6 位数字邀请码") }
            return
        }
        runAction("join") {
            familyRepository.joinFamily(code)
        }
    }

    private fun runAction(action: String, block: suspend () -> Result<*>) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, busyAction = action, error = null) }
            val result = block()
            result.fold(
                onSuccess = {
                    // Bind a fresh device token to the new family.
                    familySessionManager.onJoinedFamily()
                        .onFailure { Log.w(TAG, "Force device re-register failed: ${it.message}") }
                    val done = sessionStore.current().onboardingDone
                    _uiState.update {
                        it.copy(
                            loading = false,
                            busyAction = null,
                            navigateTo = if (done) Routes.MAIN else Routes.ONBOARDING,
                        )
                    }
                },
                onFailure = { error -> handleFailure(error) },
            )
        }
    }

    private suspend fun handleFailure(error: Throwable) {
        val apiError = error as? ApiException
        if (apiError?.code == "ALREADY_IN_FAMILY") {
            val me = authRepository.refreshMe()
            if (me.isSuccess && me.getOrNull()?.family != null) {
                val done = sessionStore.current().onboardingDone
                _uiState.update {
                    it.copy(
                        loading = false,
                        busyAction = null,
                        navigateTo = if (done) Routes.MAIN else Routes.ONBOARDING,
                    )
                }
                return
            }
        }
        _uiState.update {
            it.copy(loading = false, busyAction = null, error = error.toUserMessage())
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _uiState.update { it.copy(navigateTo = Routes.LOGIN) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun consumeNavigation() {
        _uiState.update { it.copy(navigateTo = null) }
    }

    private companion object {
        const val TAG = "FamilySetupViewModel"
    }
}
