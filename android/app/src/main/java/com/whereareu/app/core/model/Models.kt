package com.whereareu.app.core.model

/**
 * Report cadence chosen by the user. The actual interval is adapted at runtime
 * by [com.whereareu.app.core.location.AdaptiveIntervalPolicy].
 */
enum class ReportFrequency(
    val label: String,
    val shortLabel: String,
    val baseIntervalMillis: Long,
) {
    POWER_SAVER("省电", "10 分钟", 10 * 60_000L),
    STANDARD("标准", "3 分钟", 3 * 60_000L),
    HIGH("高频", "1 分钟", 60_000L),
    ;

    companion object {
        fun fromStored(value: String?): ReportFrequency =
            entries.firstOrNull { it.name == value } ?: STANDARD
    }
}

/** Normalized single location sample produced by a [LocationProvider]. */
data class LocationFix(
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float,
    val provider: String,
    val recordedAt: Long,
    val address: String? = null,
    val isMock: Boolean = false,
)

/** Result of a "locate now" command, shown in the member sheet. */
sealed interface LocateUiState {
    data object Idle : LocateUiState
    data object Requesting : LocateUiState
    data class Success(val completedAt: Long) : LocateUiState
    data class Failed(val message: String) : LocateUiState
}
