package com.whereareu.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whereareu.app.core.auth.AuthRepository
import com.whereareu.app.core.auth.SessionStore
import com.whereareu.app.core.location.LocationProviderFactory
import com.whereareu.app.core.location.OfflineLocationQueue
import com.whereareu.app.core.location.SharingController
import com.whereareu.app.core.location.TrackingManager
import com.whereareu.app.core.model.ReportFrequency
import com.whereareu.app.core.net.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val displayName: String = "",
    val username: String = "",
    val familyName: String = "",
    val role: String = "member",
    val serverUrl: String = "",
    val sharingEnabled: Boolean = true,
    val sharingBusy: Boolean = false,
    val reportFrequency: ReportFrequency = ReportFrequency.STANDARD,
    val providerName: String = "",
    val usingFakeProvider: Boolean = false,
    val loggingOut: Boolean = false,
    val logoutComplete: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(
    private val context: Context,
    private val sessionStore: SessionStore,
    private val authRepository: AuthRepository,
    private val offlineQueue: OfflineLocationQueue,
    private val providerFactory: LocationProviderFactory,
    private val sharingController: SharingController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            providerName = providerFactory.providerName,
            usingFakeProvider = providerFactory.usingFakeProvider,
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sessionStore.state.collect { session ->
                _uiState.update {
                    it.copy(
                        displayName = session.displayName,
                        username = session.username,
                        familyName = session.familyName,
                        role = session.familyRole,
                        serverUrl = session.baseUrl,
                        sharingEnabled = session.sharingEnabled,
                        reportFrequency = session.reportFrequency,
                    )
                }
            }
        }
    }

    fun setSharingEnabled(enabled: Boolean) {
        if (_uiState.value.sharingBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(sharingBusy = true, message = null) }
            sharingController.setSharingEnabled(enabled).fold(
                onSuccess = { effectiveEnabled ->
                    _uiState.update {
                        it.copy(sharingBusy = false, sharingEnabled = effectiveEnabled)
                    }
                },
                onFailure = { error ->
                    val session = sessionStore.current()
                    _uiState.update {
                        it.copy(
                            sharingBusy = false,
                            sharingEnabled = session.sharingEnabled,
                            message = "切换共享失败：${error.toUserMessage()}",
                        )
                    }
                },
            )
        }
    }

    fun setReportFrequency(frequency: ReportFrequency) {
        viewModelScope.launch {
            sessionStore.setReportFrequency(frequency)
        }
    }

    fun clearPendingLocations() {
        viewModelScope.launch {
            offlineQueue.clear()
            _uiState.update { it.copy(message = "本地待上传位置已清除") }
        }
    }

    fun logout() {
        if (_uiState.value.loggingOut) return
        viewModelScope.launch {
            _uiState.update { it.copy(loggingOut = true) }
            TrackingManager.stop(context)
            offlineQueue.clear()
            authRepository.logout()
            _uiState.update { it.copy(loggingOut = false, logoutComplete = true) }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun consumeLogout() {
        _uiState.update { it.copy(logoutComplete = false) }
    }
}
