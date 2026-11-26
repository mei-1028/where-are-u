package com.whereareu.app.core.location

import com.whereareu.app.core.net.ApiException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single entry point for the sharing toggle (map card, settings switch, welcome
 * flow and the foreground-notification "pause" action).
 *
 * Server-first for enabling; privacy-first for pausing:
 *  - pause + network failure: stop locally immediately, queue a background
 *    PATCH retry, keep the server to be reconciled later;
 *  - pause + business failure (401/403/...): reject without changing local state;
 *  - resume: require a successful PATCH and a confirmed foreground-service start.
 *
 * Dependencies are function values so this class stays pure JVM and unit
 * testable without Android or Retrofit.
 */
class SharingController(
    private val setRemotePaused: suspend (paused: Boolean) -> Result<Boolean>,
    private val readLocalSharingEnabled: suspend () -> Boolean,
    private val saveLocalSharingEnabled: suspend (enabled: Boolean) -> Unit,
    private val startTracking: () -> Boolean,
    private val awaitTrackingRunning: suspend () -> Boolean,
    private val stopTracking: () -> Unit,
    private val enqueueServerPauseRetry: suspend () -> Unit,
    private val clearServerSyncPending: suspend () -> Unit,
    private val hasPendingServerPause: suspend () -> Boolean,
) {
    private val mutex = Mutex()

    /**
     * Returns the effective local state (true = sharing, false = paused).
     * A successful result can mean the server PATCH is queued when pausing
     * offline.
     */
    suspend fun setSharingEnabled(enabled: Boolean): Result<Boolean> = mutex.withLock {
        // Reading the session also seeds RuntimeConfig (user JWT), which matters
        // if the process was restarted by the notification action.
        readLocalSharingEnabled()

        // Pausing must not wait for a full network timeout before stopping
        // location reporting. Bound the PATCH attempt; on timeout we treat it
        // as a network failure and queue the server sync.
        val remoteResult = if (enabled) {
            setRemotePaused(false)
        } else {
            withTimeoutOrNull(PAUSE_REMOTE_TIMEOUT_MS) { setRemotePaused(true) }
                ?: Result.failure(ApiException("TIMEOUT", "暂停请求超时", httpStatus = 0))
        }
        if (remoteResult.isFailure) {
            val error = remoteResult.exceptionOrNull()
                ?: ApiException("UNKNOWN", "共享状态更新失败")
            if (!enabled && error.isNetworkLike()) {
                // Privacy first: persist the pause and queue the server sync
                // BEFORE stopping the FGS. The notification action runs inside
                // the service scope, and stopping it cancels that coroutine.
                saveLocalSharingEnabled(false)
                enqueueServerPauseRetry()
                stopTracking()
                return@withLock Result.success(false)
            }
            return@withLock Result.failure(error)
        }

        val effectiveEnabled = !remoteResult.getOrThrow()
        saveLocalSharingEnabled(effectiveEnabled)
        clearServerSyncPending()

        if (effectiveEnabled) {
            val intentAccepted = startTracking()
            val running = intentAccepted && awaitTrackingRunning()
            if (!running) {
                // We told the server sharing is on but the FGS did not confirm
                // foreground start. Best-effort rollback to paused.
                stopTracking()
                val rollback = setRemotePaused(true)
                saveLocalSharingEnabled(false)
                if (rollback.isSuccess) {
                    clearServerSyncPending()
                } else {
                    enqueueServerPauseRetry()
                }
                return@withLock Result.failure(
                    ApiException(
                        code = "SHARING_START_FAILED",
                        message = "无法启动位置共享，请检查定位、通知和后台运行权限",
                    ),
                )
            }
        } else {
            stopTracking()
        }
        Result.success(effectiveEnabled)
    }

    /**
     * Reconciles the local switch with the server's FamilyMember.sharingPaused.
     * Skipped while a local pause is waiting to be synced. Returns the effective
     * local state, or null if calibration was skipped.
     */
    suspend fun applyServerSharingPaused(paused: Boolean): Boolean? = mutex.withLock {
        if (hasPendingServerPause()) return@withLock null
        val current = readLocalSharingEnabled()
        val enabled = !paused
        if (current == enabled) {
            if (!enabled) stopTracking()
            return@withLock enabled
        }
        saveLocalSharingEnabled(enabled)
        if (!enabled) {
            stopTracking()
        }
        // Enabling is picked up by the map's "sharing is on" startup effect.
        enabled
    }

    private fun Throwable.isNetworkLike(): Boolean {
        val error = this as? ApiException ?: return false
        return error.httpStatus == 0 || error.code == "NETWORK" || error.code == "TIMEOUT"
    }

    private companion object {
        const val PAUSE_REMOTE_TIMEOUT_MS = 4_000L
    }
}
