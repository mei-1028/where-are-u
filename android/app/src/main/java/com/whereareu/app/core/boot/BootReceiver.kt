package com.whereareu.app.core.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.location.TrackingManager
import com.whereareu.app.core.notifications.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restores sharing after reboot or app update.
 *
 * Android 14/15 restrictions: starting a location foreground service from the
 * background is heavily restricted (while-in-use permission + background-start
 * rules). A BOOT_COMPLETED receiver is not a reliable place to start a location
 * FGS: Android 15 restricts several FGS types from BOOT_COMPLETED, and OEM ROMs
 * may block it entirely. We therefore try once, then fall back to WorkManager
 * and a notification that asks the user to open the app. When the app is opened
 * in the foreground, TrackingManager.start() is always allowed.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val appContext = context.applicationContext
        AppGraph.init(appContext)
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val session = AppGraph.sessionStore.current()
                val shouldRestore = session.hasSession && session.sharingEnabled &&
                    !session.familyId.isNullOrBlank() && !session.deviceToken.isNullOrBlank()
                if (!shouldRestore) return@launch

                val started = TrackingManager.start(appContext)
                if (!started) {
                    Log.i(TAG, "Background FGS start rejected; using notification fallback")
                    Notifications.showRestoreNotification(appContext)
                    TrackingRestartWorker.enqueue(appContext)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Boot restore failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
