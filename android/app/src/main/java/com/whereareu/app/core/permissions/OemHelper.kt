package com.whereareu.app.core.permissions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.util.Locale

enum class OemFamily(val label: String) {
    XIAOMI("小米 / 红米"),
    HUAWEI("华为 / 荣耀"),
    OPPO("OPPO / 一加"),
    VIVO("vivo / iQOO"),
    STOCK("其他 / 原生"),
}

/**
 * Best-effort OEM keep-alive helpers. Components differ between ROM versions,
 * so every explicit intent is attempted and falls back to the app settings page.
 */
object OemHelper {

    fun detect(manufacturer: String = Build.MANUFACTURER.orEmpty()): OemFamily {
        val name = manufacturer.lowercase(Locale.ROOT)
        return when {
            listOf("xiaomi", "redmi", "poco").any(name::contains) -> OemFamily.XIAOMI
            listOf("huawei", "honor").any(name::contains) -> OemFamily.HUAWEI
            listOf("oppo", "oneplus", "realme").any(name::contains) -> OemFamily.OPPO
            listOf("vivo", "iqoo").any(name::contains) -> OemFamily.VIVO
            else -> OemFamily.STOCK
        }
    }

    fun steps(oem: OemFamily): List<String> = when (oem) {
        OemFamily.XIAOMI -> listOf(
            "在「自启动」列表中允许「你在哪」自启动",
            "把省电策略改为「无限制」",
            "在最近任务中下拉锁定「你在哪」，避免一键清理",
        )

        OemFamily.HUAWEI -> listOf(
            "进入应用启动管理，将「你在哪」改为手动管理",
            "允许自启动、关联启动和后台活动",
            "在电池优化中将应用设为「不允许优化」",
        )

        OemFamily.OPPO -> listOf(
            "在自启动管理中允许「你在哪」",
            "允许后台活动，耗电管理选择不限制",
            "在最近任务中锁定应用",
        )

        OemFamily.VIVO -> listOf(
            "打开自启动开关",
            "允许后台高耗电运行",
            "在电池优化中设为不优化",
        )

        OemFamily.STOCK -> listOf(
            "在电池优化中将「你在哪」设为「不优化」",
            "允许应用在后台运行",
            "保持常驻通知开启，便于确认共享状态",
        )
    }

    fun openAutostartSettings(context: Context, oem: OemFamily): Boolean {
        val candidates = when (oem) {
            OemFamily.XIAOMI -> listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            )

            OemFamily.HUAWEI -> listOf(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            )

            OemFamily.OPPO -> listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                ComponentName("com.oplus.securitypermission", "com.oplus.securitypermission.permission.startup.StartupAppListActivity"),
            )

            OemFamily.VIVO -> listOf(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            )

            OemFamily.STOCK -> emptyList()
        }
        for (component in candidates) {
            try {
                context.startActivity(
                    Intent().setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return true
            } catch (t: Throwable) {
                Log.d(TAG, "OEM component unavailable: $component (${t.message})")
            }
        }
        return openAppSettings(context)
    }

    fun openAppSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (t: Throwable) {
        Log.w(TAG, "Cannot open app settings", t)
        false
    }

    fun openNotificationSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (t: Throwable) {
        openAppSettings(context)
    }

    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return true
            } catch (t: Throwable) {
                Log.d(TAG, "Battery exemption intent unavailable", t)
            }
        }
        return openBatteryOptimizationSettings(context)
    }

    fun openBatteryOptimizationSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (t: Throwable) {
        openAppSettings(context)
    }

    fun openLocationSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (t: Throwable) {
        openAppSettings(context)
    }

    private const val TAG = "OemHelper"
}
