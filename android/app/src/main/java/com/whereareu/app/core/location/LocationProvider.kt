package com.whereareu.app.core.location

import com.whereareu.app.core.model.LocationFix
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction so the app can run with AMap in production and with a simulated
 * provider when no AMap key is configured (e.g. CI / a fresh checkout).
 */
interface LocationProvider {
    val name: String

    /** Cold stream of fixes; only active between [start] and [stop]. */
    val fixes: Flow<LocationFix>

    fun start(intervalMillis: Long)

    /** Called when the adaptive policy changes cadence. */
    fun updateInterval(intervalMillis: Long)

    fun stop()

    /** One high-accuracy sample for the "locate now" WebSocket command. */
    suspend fun getOneShotHighAccuracyFix(timeoutMillis: Long = 20_000L): LocationFix?
}
