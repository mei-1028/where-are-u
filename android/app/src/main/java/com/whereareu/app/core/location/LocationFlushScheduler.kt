package com.whereareu.app.core.location

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object LocationFlushScheduler {
    private const val TAG = "LocationFlushScheduler"
    private const val WORK_NAME = "location-flush"

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<LocationFlushWorker>()
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
                ExistingWorkPolicy.KEEP,
                request,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not enqueue flush worker", t)
        }
    }
}
