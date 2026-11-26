package com.whereareu.app.core.boot

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.location.LocationTrackingService
import com.whereareu.app.core.location.TrackingManager
import com.whereareu.app.core.notifications.Notifications
import java.util.concurrent.TimeUnit

/**
 * WorkManager fallback for the boot path. A worker also runs in the background,
 * so on Android 12+ starting a location FGS may still be rejected; we attempt it
 * and otherwise show the restore notification. Opening the app is the reliable
 * path on aggressive OEM ROMs.
 */
class TrackingRestartWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        val session = AppGraph.sessionStore.current()
        if (!session.hasSession || !session.sharingEnabled || session.familyId.isNullOrBlank()) {
            return Result.success()
        }
        if (session.deviceToken.isNullOrBlank()) return Result.success()
        if (LocationTrackingService.isRunning) return Result.success()

        val started = TrackingManager.start(applicationContext)
        if (started) return Result.success()

        if (runAttemptCount < MAX_ATTEMPTS) {
            Log.i(TAG, "Restart attempt ${runAttemptCount + 1} failed, will retry")
            return Result.retry()
        }
        Notifications.showRestoreNotification(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "TrackingRestartWorker"
        private const val WORK_NAME = "tracking-restart"
        private const val MAX_ATTEMPTS = 2

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<TrackingRestartWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Could not enqueue restart worker", t)
            }
        }
    }
}
