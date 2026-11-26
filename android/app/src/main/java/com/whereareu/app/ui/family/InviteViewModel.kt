package com.whereareu.app.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whereareu.app.core.data.FamilyRepository
import com.whereareu.app.core.net.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InviteUiState(
    val loading: Boolean = true,
    val familyName: String = "",
    val inviteCode: String? = null,
    val isAdmin: Boolean = false,
    val expiresAt: String? = null,
    val error: String? = null,
    val resetting: Boolean = false,
    val message: String? = null,
)

class InviteViewModel(
    private val familyRepository: FamilyRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InviteUiState())
    val uiState: StateFlow<InviteUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            familyRepository.familyDetail().fold(
                onSuccess = { detail ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            familyName = detail.family.name,
                            inviteCode = detail.family.inviteCode,
                            isAdmin = detail.family.role == "admin",
                            error = null,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(loading = false, error = error.toUserMessage()) }
                },
            )
        }
    }

    fun resetInvite() {
        if (_uiState.value.resetting || !_uiState.value.isAdmin) return
        viewModelScope.launch {
            _uiState.update { it.copy(resetting = true, error = null) }
            familyRepository.resetInvite().fold(
                onSuccess = { response ->
                    _uiState.update {
                        it.copy(
                            resetting = false,
                            inviteCode = response.inviteCode,
                            expiresAt = response.expiresAt,
                            message = "邀请码已重置，旧码已失效",
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(resetting = false, error = error.toUserMessage())
                    }
                },
            )
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
