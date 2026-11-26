package com.whereareu.app.core.location

import com.whereareu.app.core.model.LocationFix

/**
 * Chooses the point reported for a locate command. It must be no older than the
 * command receipt so the server sees recordedAt after command.createdAt.
 * A fresh continuous fix is used only when the one-shot attempts failed.
 */
object LocateFixPolicy {
    const val MAX_CONTINUOUS_FIX_AGE_MS = 60_000L

    fun selectForCommand(
        oneShot: LocationFix?,
        latestContinuous: LocationFix?,
        commandReceivedAt: Long,
        now: Long,
        maxContinuousAgeMillis: Long = MAX_CONTINUOUS_FIX_AGE_MS,
    ): LocationFix? {
        if (oneShot != null && oneShot.recordedAt >= commandReceivedAt) {
            return oneShot
        }
        val continuous = latestContinuous ?: return null
        val age = now - continuous.recordedAt
        return continuous.takeIf {
            it.recordedAt >= commandReceivedAt && age in 0..maxContinuousAgeMillis
        }
    }
}
