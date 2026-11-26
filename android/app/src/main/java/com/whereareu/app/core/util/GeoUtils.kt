package com.whereareu.app.core.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun distanceMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lng2 - lng1)
        val a = sin(deltaPhi / 2).let { it * it } +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2).let { it * it }
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun totalDistanceMeters(points: List<Pair<Double, Double>>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            total += distanceMeters(previous.first, previous.second, current.first, current.second)
        }
        return total
    }

    fun formatDistance(meters: Double): String =
        if (meters >= 1000) {
            String.format(java.util.Locale.CHINA, "%.1f km", meters / 1000)
        } else {
            "${meters.toInt()} m"
        }
}
