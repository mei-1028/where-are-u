package com.whereareu.app.ui.splash

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whereareu.app.core.auth.AuthRepository
import com.whereareu.app.core.auth.SessionStore
import com.whereareu.app.core.net.ApiException
import com.whereareu.app.ui.nav.Routes
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StartupViewModel(
    private val authRepository: AuthRepository,
    private val sessionStore: SessionStore,
) : ViewModel() {

    private val _route = MutableStateFlow<String?>(null)
    val route: StateFlow<String?> = _route.asStateFlow()

    init {
        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val destination = resolveDestination()
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed < MIN_SPLASH_MILLIS) {
                delay(MIN_SPLASH_MILLIS - elapsed)
            }
            _route.value = destination
        }
    }

    private suspend fun resolveDestination(): String {
        val session = sessionStore.current()
        if (!session.hasSession) return Routes.LOGIN

        // Validate quickly, but never block startup on a slow/unreachable server.
        // If this times out we trust the cached family and let the map surface a
        // 401 / connection banner later.
        val meResult = withTimeoutOrNull(QUICK_SESSION_TIMEOUT_MS) {
            authRepository.refreshMe()
        }
        val failure = meResult?.exceptionOrNull()
        if (failure is ApiException && failure.isUnauthorized) {
            authRepository.logout()
            return Routes.LOGIN
        }

        val serverFamily = meResult?.getOrNull()?.family
        val latestSession = sessionStore.current()
        val hasFamily = if (meResult != null && meResult.isSuccess) {
            serverFamily != null
        } else {
            latestSession.familyId != null
        }
        val onboardingDone = latestSession.onboardingDone

        return when {
            !hasFamily -> Routes.FAMILY_SETUP
            !onboardingDone -> Routes.ONBOARDING
            else -> Routes.MAIN
        }
    }

    private companion object {
        const val MIN_SPLASH_MILLIS = 900L
        const val QUICK_SESSION_TIMEOUT_MS = 1_500L
    }
}
