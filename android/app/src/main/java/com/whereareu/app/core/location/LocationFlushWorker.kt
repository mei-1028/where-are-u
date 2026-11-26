package com.whereareu.app.core.location

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.net.ApiException
import com.whereareu.app.core.net.RuntimeConfig

/** Retries the disk-backed queue when the network comes back. */
class LocationFlushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        var session = AppGraph.sessionStore.current()
        if (session.deviceToken.isNullOrBlank()) {
            if (session.userJwt.isNullOrBlank() || session.familyId.isNullOrBlank()) {
                // Logged out / removed: queued points were already cleared.
                return Result.success()
            }
            // A 401 may have invalidated the device token; register again and
            // keep the queued points.
            val registration = AppGraph.deviceManager.ensureRegistered(force = true)
            if (registration.isFailure) {
                val error = registration.exceptionOrNull()
                if (error is ApiException && error.isUnauthorized) {
                    AppGraph.authRepository.logout()
                    AppGraph.offlineQueue.clear()
                    return Result.success()
                }
                return Result.retry()
            }
            session = AppGraph.sessionStore.current()
        }
        RuntimeConfig.serverBaseUrl = session.baseUrl
        RuntimeConfig.userToken = session.userJwt
        RuntimeConfig.deviceToken = session.deviceToken
        return try {
            AppGraph.locationReporter.flush()
            if (AppGraph.offlineQueue.size() > 0) Result.retry() else Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "Flush worker failed", t)
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "LocationFlushWorker"
    }
}
