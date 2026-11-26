package com.whereareu.app.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whereareu.app.core.auth.FamilySessionManager
import com.whereareu.app.core.auth.SessionStore
import com.whereareu.app.core.data.FamilyRepository
import com.whereareu.app.core.net.ApiException
import com.whereareu.app.core.net.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FamilyMemberUi(
    val userId: String,
    val nickname: String,
    val role: String,
    val online: Boolean,
    val sharingPaused: Boolean,
) {
    val isAdmin: Boolean get() = role == "admin"
}

data class FamilyUiState(
    val loading: Boolean = true,
    val familyName: String = "",
    val inviteCode: String? = null,
    val myRole: String = "member",
    val myUserId: String? = null,
    val members: List<FamilyMemberUi> = emptyList(),
    val error: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val leftFamily: Boolean = false,
) {
    val isAdmin: Boolean get() = myRole == "admin"
}

class FamilyViewModel(
    private val familyRepository: FamilyRepository,
    private val sessionStore: SessionStore,
    private val familySessionManager: FamilySessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FamilyUiState())
    val uiState: StateFlow<FamilyUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sessionStore.state.collect { session ->
                _uiState.update { it.copy(myUserId = session.userId ?: it.myUserId) }
            }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            familyRepository.familyDetail().fold(
                onSuccess = { detail ->
                    _uiState.update { state ->
                        state.copy(
                            loading = false,
                            error = null,
                            familyName = detail.family.name,
                            inviteCode = detail.family.inviteCode,
                            myRole = detail.family.role ?: state.myRole,
                            members = detail.members.map { member ->
                                FamilyMemberUi(
                                    userId = member.userId,
                                    nickname = member.nickname.ifBlank { "家人" },
                                    role = member.role,
                                    online = member.online,
                                    sharingPaused = member.sharingPaused,
                                )
                            },
                        )
                    }
                },
                onFailure = { error ->
                    val apiError = error as? ApiException
                    if (apiError != null &&
                        (apiError.httpStatus == 404 || apiError.code == "NOT_FOUND")
                    ) {
                        // The user was removed from the family (or it was
                        // dissolved). Device credentials for that family are no
                        // longer valid; clear them and go back to setup.
                        familySessionManager.onLeftFamilyOrRemoved()
                        _uiState.update {
                            it.copy(
                                loading = false,
                                error = null,
                                leftFamily = true,
                            )
                        }
                    } else {
                        _uiState.update { it.copy(loading = false, error = error.toUserMessage()) }
                    }
                },
            )
        }
    }

    fun removeMember(member: FamilyMemberUi) {
        if (_uiState.value.busy || !_uiState.value.isAdmin) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            familyRepository.removeMember(member.userId).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(busy = false, message = "已移除 ${member.nickname}")
                    }
                    load()
                },
                onFailure = { error ->
                    _uiState.update { it.copy(busy = false, error = error.toUserMessage()) }
                },
            )
        }
    }

    fun leaveFamily() {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            familyRepository.leaveFamily().fold(
                onSuccess = {
                    familySessionManager.onLeftFamilyOrRemoved()
                    _uiState.update { it.copy(busy = false, leftFamily = true) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(busy = false, error = error.toUserMessage()) }
                },
            )
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun consumeLeftFamily() {
        _uiState.update { it.copy(leftFamily = false) }
    }
}
