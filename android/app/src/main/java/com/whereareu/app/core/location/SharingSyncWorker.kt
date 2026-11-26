package com.whereareu.app.core.location

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.net.ApiException
import java.util.concurrent.TimeUnit

/**
 * Retries a locally-applied pause when the PATCH could not reach the server.
 * The local state is already paused and the FGS already stopped; this only
 * reconciles FamilyMember.sharingPaused.
 */
class SharingSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        val session = AppGraph.sessionStore.current()
        if (!session.pendingSharingPause) return Result.success()
        if (session.userJwt.isNullOrBlank() || session.familyId.isNullOrBlank()) {
            AppGraph.sessionStore.setPendingSharingPause(false)
            return Result.success()
        }
        if (session.sharingEnabled) {
            // The user resumed before this retry ran.
            AppGraph.sessionStore.setPendingSharingPause(false)
            return Result.success()
        }

        return AppGraph.familyRepository.setSharingPaused(paused = true).fold(
            onSuccess = { paused ->
                if (paused) {
                    AppGraph.sessionStore.setPendingSharingPause(false)
                    Result.success()
                } else {
                    Result.retry()
                }
            },
            onFailure = { error ->
                val apiError = error as? ApiException
                when {
                    apiError?.isUnauthorized == true -> {
                        AppGraph.authRepository.logout()
                        AppGraph.offlineQueue.clear()
                        Result.success()
                    }

                    apiError != null && apiError.httpStatus in 400..499 &&
                        apiError.code != "RATE_LIMITED" -> {
                        // Permanent business rejection: stop retrying.
                        AppGraph.sessionStore.setPendingSharingPause(false)
                        Result.success()
                    }

                    else -> {
                        Log.w(TAG, "Sharing pause sync failed, retrying: ${error.message}")
                        Result.retry()
                    }
                }
            },
        )
    }

    companion object {
        private const val TAG = "SharingSyncWorker"
        private const val WORK_NAME = "sharing-pause-sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SharingSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Could not enqueue sharing sync", t)
            }
        }
    }
}
