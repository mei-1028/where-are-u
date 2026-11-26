package com.whereareu.app.core.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeUtils {

    private val dateFormatter = DateTimeFormatter.ofPattern("MM月dd日", Locale.CHINA)
    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("MM月dd日 HH:mm", Locale.CHINA)

    fun parseIsoOrNull(value: String?): Instant? =
        value?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun parseIsoEpochMillis(value: String?): Long? = parseIsoOrNull(value)?.toEpochMilli()

    fun relativeTime(iso: String?): String = relativeFromEpoch(parseIsoEpochMillis(iso))

    fun relativeFromEpoch(epochMillis: Long?, now: Long = System.currentTimeMillis()): String {
        if (epochMillis == null) return "暂无数据"
        val diff = (now - epochMillis).coerceAtLeast(0)
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
            else -> formatDate(epochMillis)
        }
    }

    fun formatDate(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateFormatter)

    fun formatClock(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(clockFormatter)

    fun formatDateTime(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateTimeFormatter)

    fun formatClock(iso: String?): String =
        parseIsoEpochMillis(iso)?.let(::formatClock) ?: "--:--"

    fun formatDateTime(iso: String?): String =
        parseIsoEpochMillis(iso)?.let(::formatDateTime) ?: "暂无"

    fun formatDuration(durationMillis: Long): String {
        val totalMinutes = durationMillis.coerceAtLeast(0) / 60_000L
        return when {
            totalMinutes < 1L -> "不足 1 分钟"
            totalMinutes < 60L -> "$totalMinutes 分钟"
            else -> {
                val hours = totalMinutes / 60
                val minutes = totalMinutes % 60
                if (minutes == 0L) "$hours 小时" else "$hours 小时 $minutes 分钟"
            }
        }
    }

    fun intervalLabel(intervalMillis: Long): String =
        if (intervalMillis >= 60_000L) {
            "${(intervalMillis / 60_000L)} 分钟"
        } else {
            "${(intervalMillis / 1_000L).coerceAtLeast(1)} 秒"
        }
}
