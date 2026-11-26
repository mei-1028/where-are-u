package com.whereareu.app.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

enum class PermissionKind {
    FOREGROUND_LOCATION,
    BACKGROUND_LOCATION,
    NOTIFICATIONS,
    BATTERY_OPTIMIZATION,
    AUTOSTART,
}

data class PermissionStatus(
    val kind: PermissionKind,
    val title: String,
    val description: String,
    /** true = granted/whitelisted, false = missing, null = cannot be verified. */
    val granted: Boolean?,
)

object PermissionChecker {

    fun isForegroundLocationGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun isFineLocationGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun isBackgroundLocationGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return isForegroundLocationGranted(context)
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED && isForegroundLocationGranted(context)
    }

    fun areNotificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isSystemLocationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    fun statuses(context: Context): List<PermissionStatus> = listOf(
        PermissionStatus(
            kind = PermissionKind.FOREGROUND_LOCATION,
            title = "定位权限",
            description = "用于把位置分享给家人，仅家庭成员可见",
            granted = isForegroundLocationGranted(context),
        ),
        PermissionStatus(
            kind = PermissionKind.BACKGROUND_LOCATION,
            title = "后台定位",
            description = "锁屏或切到后台后仍能更新位置（始终允许）",
            granted = isBackgroundLocationGranted(context),
        ),
        PermissionStatus(
            kind = PermissionKind.NOTIFICATIONS,
            title = "通知",
            description = "常驻通知表示共享正在进行，也用于提醒",
            granted = areNotificationsGranted(context),
        ),
        PermissionStatus(
            kind = PermissionKind.BATTERY_OPTIMIZATION,
            title = "电池优化",
            description = "忽略电池优化可减少后台被系统清理的概率",
            granted = isIgnoringBatteryOptimizations(context),
        ),
        PermissionStatus(
            kind = PermissionKind.AUTOSTART,
            title = "自启动",
            description = "部分系统需要手动允许自启动/后台运行，无法自动检测",
            granted = null,
        ),
    )
}
