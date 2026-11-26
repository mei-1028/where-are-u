package com.whereareu.app.core.location

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.permissions.PermissionChecker

/** Thin helper used by UI, boot receiver and the settings screen. */
object TrackingManager {

    fun start(context: Context): Boolean {
        AppGraph.init(context)
        if (!PermissionChecker.isForegroundLocationGranted(context)) {
            Log.w(TAG, "Not starting tracking: location permission missing")
            return false
        }
        return try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationTrackingService::class.java)
                    .setAction(LocationTrackingService.ACTION_START),
            )
            true
        } catch (t: Throwable) {
            // ForegroundServiceStartNotAllowedException on Android 12+ when the
            // app is in the background; callers should surface a notification.
            Log.w(TAG, "Foreground service start failed: ${t.message}")
            false
        }
    }

    fun stop(context: Context) {
        try {
            context.stopService(Intent(context, LocationTrackingService::class.java))
        } catch (t: Throwable) {
            Log.w(TAG, "Foreground service stop failed: ${t.message}")
        }
    }

    private const val TAG = "TrackingManager"
}
