package com.whereareu.app.core.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.whereareu.app.MainActivity
import com.whereareu.app.R
import com.whereareu.app.core.location.LocationTrackingService

object Notifications {

    const val CHANNEL_TRACKING = "tracking"
    const val CHANNEL_ALERTS = "alerts"

    const val TRACKING_NOTIFICATION_ID = 1001
    const val RESTORE_NOTIFICATION_ID = 1002
    const val SHARING_ACTION_FAILED_NOTIFICATION_ID = 1003
    const val LOCATE_FAILED_NOTIFICATION_ID = 1004

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val tracking = NotificationChannel(
            CHANNEL_TRACKING,
            context.getString(R.string.notification_channel_tracking),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_tracking_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.notification_channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_alerts_desc)
        }
        manager.createNotificationChannel(tracking)
        manager.createNotificationChannel(alerts)
    }

    fun trackingNotification(
        context: Context,
        contentText: String,
        subText: String?,
    ): Notification {
        ensureChannels(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle("正在与家人共享位置")
            .setContentText(contentText)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppPendingIntent(context))

        if (!subText.isNullOrBlank()) {
            builder.setSubText(subText)
        }

        val pausePendingIntent = PendingIntent.getService(
            context,
            11,
            Intent(context, LocationTrackingService::class.java)
                .setAction(LocationTrackingService.ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(0, "暂停共享", pausePendingIntent)
        return builder.build()
    }

    fun showRestoreNotification(context: Context, text: String = "点按打开「你在哪」恢复位置共享") {
        if (!canPostNotifications(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle("位置共享已暂停")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent(context))
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(RESTORE_NOTIFICATION_ID, notification)
        }
    }

    /** Shown when the notification "pause" action could not reach the server. */
    fun showSharingActionFailed(context: Context, message: String) {
        if (!canPostNotifications(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle("暂停共享失败")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent(context))
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(SHARING_ACTION_FAILED_NOTIFICATION_ID, notification)
        }
    }

    /** Visible failure receipt when a locate command produced no fresh fix. */
    fun showLocateFailed(context: Context, message: String) {
        if (!canPostNotifications(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle("立即定位失败")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent(context))
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(LOCATE_FAILED_NOTIFICATION_ID, notification)
        }
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAppPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            12,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
