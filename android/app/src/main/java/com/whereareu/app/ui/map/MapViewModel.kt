package com.whereareu.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whereareu.app.core.auth.AuthRepository
import com.whereareu.app.core.auth.FamilySessionManager
import com.whereareu.app.core.auth.SessionStore
import com.whereareu.app.core.data.FamilyRepository
import com.whereareu.app.core.location.SharingController
import com.whereareu.app.core.model.LocateUiState
import com.whereareu.app.core.net.ApiException
import com.whereareu.app.core.net.FamilyDetailResponse
import com.whereareu.app.core.net.LatestResponse
import com.whereareu.app.core.net.LocateResponse
import com.whereareu.app.core.net.toUserMessage
import com.whereareu.app.core.util.AddressResolver
import com.whereareu.app.core.util.TimeUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

data class MemberUi(
    val userId: String,
    val nickname: String,
    val role: String,
    val online: Boolean,
    val sharingPaused: Boolean,
    val deviceId: String?,
    val lat: Double?,
    val lng: Double?,
    val accuracy: Float?,
    val speed: Float?,
    val battery: Int?,
    val recordedAtMillis: Long?,
    val address: String? = null,
) {
    val hasLocation: Boolean get() = lat != null && lng != null
    val isAdmin: Boolean get() = role == "admin"
    val isStale: Boolean
        get() = recordedAtMillis?.let { System.currentTimeMillis() - it > 30 * 60_000L } ?: true
}

data class MapUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val familyName: String = "",
    val members: List<MemberUi> = emptyList(),
    val myUserId: String? = null,
    val selectedUserId: String? = null,
    val connectionError: String? = null,
    val lastSyncedAt: Long? = null,
    val locateState: LocateUiState = LocateUiState.Idle,
    val sharingEnabled: Boolean = true,
    val sharingBusy: Boolean = false,
    val sharingActionError: String? = null,
    val sessionExpired: Boolean = false,
    val familyRemoved: Boolean = false,
    val recenterNonce: Int = 0,
) {
    val selectedMember: MemberUi? get() = members.firstOrNull { it.userId == selectedUserId }
    val myMember: MemberUi? get() = members.firstOrNull { it.userId == myUserId }
    val onlineCount: Int get() = members.count { it.online }
}

class MapViewModel(
    private val familyRepository: FamilyRepository,
    private val sessionStore: SessionStore,
    private val addressResolver: AddressResolver,
    private val authRepository: AuthRepository,
    private val sharingController: SharingController,
    private val familySessionManager: FamilySessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val refreshMutex = Mutex()
    private val addressCache = LinkedHashMap<String, String>()
    private val resolvingKeys = mutableSetOf<String>()
    private var locateJob: Job? = null
    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            sessionStore.state.collect { session ->
                _uiState.update { state ->
                    state.copy(
                        myUserId = session.userId ?: state.myUserId,
                        sharingEnabled = session.sharingEnabled,
                        familyName = state.familyName.ifBlank { session.familyName },
                    )
                }
            }
        }
    }

    /**
     * Polling is driven by the screen lifecycle: only while the map is visible
     * and the Activity is resumed. This avoids background 10-second requests and
     * spurious 401 logout while the app is hidden.
     */
    fun setPollingActive(active: Boolean) {
        if (!active) {
            pollJob?.cancel()
            pollJob = null
            return
        }
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            load(initial = _uiState.value.members.isEmpty())
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (_uiState.value.sessionExpired || _uiState.value.familyRemoved) break
                load(initial = false)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { load(initial = false, manual = true) }
        if (pollJob?.isActive != true) {
            setPollingActive(true)
        }
    }

    fun setSharingEnabled(enabled: Boolean) {
        if (_uiState.value.sharingBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(sharingBusy = true, sharingActionError = null) }
            sharingController.setSharingEnabled(enabled).fold(
                onSuccess = { effectiveEnabled ->
                    _uiState.update {
                        it.copy(
                            sharingBusy = false,
                            sharingEnabled = effectiveEnabled,
                            sharingActionError = null,
                        )
                    }
                },
                onFailure = { error ->
                    val session = sessionStore.current()
                    _uiState.update {
                        it.copy(
                            sharingBusy = false,
                            sharingEnabled = session.sharingEnabled,
                            sharingActionError = error.toUserMessage(),
                        )
                    }
                },
            )
        }
    }

    fun clearSharingActionError() {
        _uiState.update { it.copy(sharingActionError = null) }
    }

    fun selectMember(userId: String) {
        val current = _uiState.value
        if (current.selectedUserId == userId) return
        locateJob?.cancel()
        locateJob = null
        _uiState.update {
            it.copy(selectedUserId = userId, locateState = LocateUiState.Idle)
        }
    }

    fun clearSelection() {
        locateJob?.cancel()
        locateJob = null
        _uiState.update {
            it.copy(selectedUserId = null, locateState = LocateUiState.Idle)
        }
    }

    fun recenter() {
        locateJob?.cancel()
        locateJob = null
        _uiState.update {
            it.copy(
                selectedUserId = null,
                locateState = LocateUiState.Idle,
                recenterNonce = it.recenterNonce + 1,
            )
        }
    }

    fun locateNow(userId: String) {
        val state = _uiState.value
        if (userId == state.myUserId) return
        if (locateJob?.isActive == true) return
        val member = state.members.firstOrNull { it.userId == userId } ?: return
        val deviceId = member.deviceId
        if (deviceId.isNullOrBlank()) {
            _uiState.update { it.copy(locateState = LocateUiState.Failed("没有找到该成员的设备")) }
            return
        }
        locateJob = viewModelScope.launch {
            _uiState.update { it.copy(locateState = LocateUiState.Requesting) }
            val result = familyRepository.requestLocate(deviceId)
            result.fold(
                onSuccess = { response -> awaitLocateCompletion(response) },
                onFailure = { error ->
                    _uiState.update { it.copy(locateState = LocateUiState.Failed(error.toUserMessage())) }
                },
            )
        }
    }

    private suspend fun awaitLocateCompletion(response: LocateResponse) {
        val serverDeadline = TimeUtils.parseIsoEpochMillis(response.deadlineAt)
        val endAt = minOf(
            serverDeadline ?: Long.MAX_VALUE,
            System.currentTimeMillis() + MAX_LOCATE_WAIT_MS,
        )
        var terminalStatus: String? = null

        while (System.currentTimeMillis() < endAt) {
            delay(COMMAND_POLL_MS)
            familyRepository.commandStatus(response.commandId).fold(
                onSuccess = { status ->
                    if (status.status != "pending") terminalStatus = status.status
                },
                onFailure = { error ->
                    if (isUnauthorized(error)) return
                    // Transient network errors: keep polling until the deadline.
                },
            )
            if (terminalStatus != null) break
        }

        when (terminalStatus) {
            "completed" -> {
                _uiState.update { it.copy(locateState = LocateUiState.Success(System.currentTimeMillis())) }
                load(initial = false)
            }

            "timeout" -> {
                _uiState.update {
                    it.copy(locateState = LocateUiState.Failed("请求超时：对方可能离线或未开启定位"))
                }
            }

            "failed" -> {
                _uiState.update { it.copy(locateState = LocateUiState.Failed("对方定位失败，请稍后重试")) }
            }

            else -> {
                _uiState.update { it.copy(locateState = LocateUiState.Failed("等待超时，请稍后再试")) }
            }
        }
    }

    private suspend fun load(initial: Boolean, manual: Boolean = false) {
        refreshMutex.withLock {
            if (_uiState.value.sessionExpired || _uiState.value.familyRemoved) return@withLock
            if (initial) _uiState.update { it.copy(loading = true) }
            if (manual) _uiState.update { it.copy(refreshing = true) }

            val (familyResult, latestResult) = coroutineScope {
                val familyDeferred = async { familyRepository.familyDetail() }
                val latestDeferred = async { familyRepository.latest() }
                familyDeferred.await() to latestDeferred.await()
            }

            val failure = familyResult.exceptionOrNull() ?: latestResult.exceptionOrNull()
            if (failure != null && failure is ApiException && failure.isUnauthorized) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        sessionExpired = true,
                        connectionError = "登录已过期，请重新登录",
                    )
                }
                authRepository.logout()
                return@withLock
            }

            val notFound = familyResult.exceptionOrNull() as? ApiException
            if (notFound != null &&
                (notFound.httpStatus == 404 || notFound.code == "NOT_FOUND")
            ) {
                // Removed by the admin: invalidate device credentials and stop
                // sharing; the map screen sends the user back to family setup.
                familySessionManager.onLeftFamilyOrRemoved()
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        familyRemoved = true,
                        members = emptyList(),
                        connectionError = null,
                        locateState = LocateUiState.Idle,
                    )
                }
                return@withLock
            }

            val family = familyResult.getOrNull()
            val latest = latestResult.getOrNull()
            if (family == null && latest == null) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        connectionError = failure?.toUserMessage() ?: "加载失败",
                    )
                }
                return@withLock
            }

            val merged = mergeMembers(family, latest)
            val selected = _uiState.value.selectedUserId

            _uiState.update { state ->
                state.copy(
                    loading = false,
                    refreshing = false,
                    familyName = family?.family?.name?.takeIf { it.isNotBlank() } ?: state.familyName,
                    members = merged,
                    connectionError = failure?.toUserMessage(),
                    lastSyncedAt = System.currentTimeMillis(),
                    selectedUserId = selected?.takeIf { id -> merged.any { it.userId == id } },
                    locateState = if (selected != null && merged.none { it.userId == selected }) {
                        LocateUiState.Idle
                    } else {
                        state.locateState
                    },
                )
            }

            // The server is authoritative for sharingPaused (e.g. paused from
            // web). Calibrate the local switch and stop tracking when paused.
            val meId = _uiState.value.myUserId ?: sessionStore.current().userId
            val serverPaused = family?.members
                ?.firstOrNull { it.userId == meId }
                ?.sharingPaused
            if (serverPaused != null) {
                val effective = sharingController.applyServerSharingPaused(serverPaused)
                if (effective != null) {
                    _uiState.update { it.copy(sharingEnabled = effective) }
                }
            }

            resolveAddresses(merged)
        }
    }

    private fun mergeMembers(
        family: FamilyDetailResponse?,
        latest: LatestResponse?,
    ): List<MemberUi> {
        val detailMembers = family?.members.orEmpty()
        val latestMembers = latest?.members.orEmpty().associateBy { it.userId }
        val orderedIds = LinkedHashSet<String>()
        detailMembers.forEach { orderedIds.add(it.userId) }
        latestMembers.keys.forEach { orderedIds.add(it) }

        return orderedIds.map { userId ->
            val detail = detailMembers.firstOrNull { it.userId == userId }
            val live = latestMembers[userId]
            val snapshot = live?.lastLocation ?: detail?.lastLocation
            val lat = snapshot?.lat
            val lng = snapshot?.lng
            val key = if (lat != null && lng != null) addressKey(lat, lng) else null
            MemberUi(
                userId = userId,
                nickname = detail?.nickname?.takeIf { it.isNotBlank() } ?: "家人",
                role = detail?.role ?: "member",
                online = live?.online ?: detail?.online ?: false,
                sharingPaused = detail?.sharingPaused ?: false,
                deviceId = live?.deviceId,
                lat = lat,
                lng = lng,
                accuracy = snapshot?.accuracy,
                speed = snapshot?.speed,
                battery = snapshot?.battery,
                recordedAtMillis = TimeUtils.parseIsoEpochMillis(snapshot?.recordedAt),
                address = key?.let { addressCache[it] },
            )
        }
    }

    private fun resolveAddresses(members: List<MemberUi>) {
        members.forEach { member ->
            val lat = member.lat ?: return@forEach
            val lng = member.lng ?: return@forEach
            val key = addressKey(lat, lng)
            val cached = addressCache[key]
            if (cached != null) {
                if (member.address != cached) {
                    updateAddressByCoordinates(lat, lng, cached)
                }
                return@forEach
            }
            if (!resolvingKeys.add(key)) return@forEach
            viewModelScope.launch {
                val resolved = addressResolver.resolve(lat, lng)
                addressCache[key] = resolved
                resolvingKeys.remove(key)
                updateAddressByCoordinates(lat, lng, resolved)
            }
        }
    }

    private fun updateAddressByCoordinates(lat: Double, lng: Double, address: String) {
        _uiState.update { state ->
            state.copy(
                members = state.members.map { member ->
                    if (member.lat == lat && member.lng == lng) {
                        member.copy(address = address)
                    } else {
                        member
                    }
                },
            )
        }
    }

    private fun isUnauthorized(error: Throwable): Boolean {
        val apiError = error as? ApiException ?: return false
        if (!apiError.isUnauthorized) return false
        _uiState.update {
            it.copy(
                sessionExpired = true,
                connectionError = "登录已过期，请重新登录",
                locateState = LocateUiState.Failed("登录已过期"),
            )
        }
        viewModelScope.launch { authRepository.logout() }
        return true
    }

    private fun addressKey(lat: Double, lng: Double): String =
        String.format(Locale.US, "%.4f,%.4f", lat, lng)

    private companion object {
        const val POLL_INTERVAL_MS = 10_000L
        const val COMMAND_POLL_MS = 2_000L
        const val MAX_LOCATE_WAIT_MS = 40_000L
    }
}
