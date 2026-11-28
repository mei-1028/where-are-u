package com.whereareu.app.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.data.AlertsRepository
import com.whereareu.app.core.net.AlertDto
import com.whereareu.app.core.net.FollowerDto
import com.whereareu.app.core.net.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class AlertsUiState(
    val loading: Boolean = true,
    val alerts: List<AlertDto> = emptyList(),
    val unreadCount: Int = 0,
    val followers: List<FollowerDto> = emptyList(),
    val message: String? = null,
)

class AlertsViewModel(private val repository: AlertsRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AlertsUiState())
    val uiState: StateFlow<AlertsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            repository.alerts(limit = 50)
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            alerts = response.alerts,
                            unreadCount = response.unreadCount,
                            message = null,
                        )
                    }
                    AppGraph.unreadAlerts.value = response.unreadCount
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(loading = false, message = error.toUserMessage())
                    }
                }
            repository.followers().onSuccess { list ->
                _uiState.update { it.copy(followers = list) }
            }
        }
    }

    fun markRead(alert: AlertDto) {
        if (alert.readAt != null) return
        val now = Instant.now().toString()
        _uiState.update { state ->
            state.copy(
                alerts = state.alerts.map {
                    if (it.id == alert.id) it.copy(readAt = now) else it
                },
                unreadCount = (state.unreadCount - 1).coerceAtLeast(0),
            )
        }
        AppGraph.unreadAlerts.update { (it - 1).coerceAtLeast(0) }
        viewModelScope.launch { repository.markRead(alert.id) }
    }

    fun markAllRead() {
        val now = Instant.now().toString()
        _uiState.update { state ->
            state.copy(
                alerts = state.alerts.map {
                    if (it.readAt == null) it.copy(readAt = now) else it
                },
                unreadCount = 0,
            )
        }
        AppGraph.unreadAlerts.value = 0
        viewModelScope.launch { repository.markAllRead() }
    }
}
