package com.whereareu.app.core.location

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A start request is only an intent. Poll [isRunning] briefly for the service
 * receipt after its startForeground() succeeded, so the UI does not report
 * "共享中" for a service that immediately died.
 */
object TrackingStartReceipt {

    suspend fun awaitRunning(
        isRunning: () -> Boolean,
        timeoutMillis: Long = 1_500L,
        pollIntervalMillis: Long = 50L,
    ): Boolean {
        if (isRunning()) return true
        return withTimeoutOrNull(timeoutMillis) {
            while (!isRunning()) {
                delay(pollIntervalMillis)
            }
            true
        } ?: false
    }
}
