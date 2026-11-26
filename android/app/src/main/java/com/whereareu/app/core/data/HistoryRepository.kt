package com.whereareu.app.core.data

import com.whereareu.app.core.net.ApiService
import com.whereareu.app.core.net.HistoryPointDto
import com.whereareu.app.core.net.safeApiCall
import java.time.Instant

class HistoryRepository(private val api: ApiService) {

    suspend fun locations(
        deviceId: String,
        from: Instant,
        to: Instant,
        limit: Int = MAX_LIMIT,
    ): Result<List<HistoryPointDto>> = safeApiCall {
        api.locationHistory(
            deviceId = deviceId,
            from = from.toString(),
            to = to.toString(),
            limit = limit.coerceIn(1, MAX_LIMIT),
        ).points
    }

    private companion object {
        const val MAX_LIMIT = 5000
    }
}
