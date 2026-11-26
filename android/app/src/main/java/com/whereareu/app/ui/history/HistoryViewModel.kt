package com.whereareu.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whereareu.app.core.data.HistoryRepository
import com.whereareu.app.core.net.toUserMessage
import com.whereareu.app.core.util.GeoUtils
import com.whereareu.app.core.util.TimeUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HistoryPointUi(
    val lat: Double,
    val lng: Double,
    val recordedAtMillis: Long?,
)

data class HistorySummary(
    val distanceMeters: Double = 0.0,
    val durationMillis: Long = 0L,
    val firstAtMillis: Long? = null,
    val lastAtMillis: Long? = null,
    val pointCount: Int = 0,
)

data class HistoryUiState(
    val deviceId: String,
    val memberName: String,
    val selectedDate: LocalDate = LocalDate.now(),
    val loading: Boolean = true,
    val points: List<HistoryPointUi> = emptyList(),
    val summary: HistorySummary = HistorySummary(),
    val error: String? = null,
)

class HistoryViewModel(
    deviceId: String,
    memberName: String,
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        HistoryUiState(deviceId = deviceId, memberName = memberName),
    )
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun selectDate(date: LocalDate) {
        val today = LocalDate.now()
        if (date.isAfter(today)) return
        if (date == _uiState.value.selectedDate) return
        _uiState.update { it.copy(selectedDate = date) }
        load()
    }

    fun selectDaysAgo(daysAgo: Long) {
        selectDate(LocalDate.now().minusDays(daysAgo))
    }

    fun reload() {
        load()
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(loading = true, error = null) }

            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val from = state.selectedDate.atStartOfDay(zone).toInstant()
            val to = if (state.selectedDate == today) {
                Instant.now()
            } else {
                state.selectedDate.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1)
            }

            historyRepository.locations(
                deviceId = state.deviceId,
                from = from,
                to = to,
                limit = 5000,
            ).fold(
                onSuccess = { dtoPoints ->
                    val points = dtoPoints.map { dto ->
                        HistoryPointUi(
                            lat = dto.lat,
                            lng = dto.lng,
                            recordedAtMillis = TimeUtils.parseIsoEpochMillis(dto.recordedAt),
                        )
                    }
                    val times = points.mapNotNull { it.recordedAtMillis }
                    val firstAt = times.minOrNull()
                    val lastAt = times.maxOrNull()
                    val summary = HistorySummary(
                        distanceMeters = GeoUtils.totalDistanceMeters(
                            points.map { it.lat to it.lng },
                        ),
                        durationMillis = if (firstAt != null && lastAt != null) {
                            (lastAt - firstAt).coerceAtLeast(0)
                        } else {
                            0L
                        },
                        firstAtMillis = firstAt,
                        lastAtMillis = lastAt,
                        pointCount = points.size,
                    )
                    _uiState.update {
                        it.copy(
                            loading = false,
                            points = points,
                            summary = summary,
                            error = null,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            points = emptyList(),
                            summary = HistorySummary(),
                            error = error.toUserMessage(),
                        )
                    }
                },
            )
        }
    }
}
