package com.whereareu.app.core.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    // Krasovsky 1940 ellipsoid, used by the GCJ-02 ("Mars coordinates") offset.
    private const val GCJ_A = 6_378_245.0
    private const val GCJ_EE = 0.006_693_421_622_965_943

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

    /**
     * GCJ-02 (the datum AMap and most Chinese SDKs report) to WGS-84 (the datum
     * Android's platform [android.location.Geocoder] expects).
     *
     * Skipping this conversion shifts a fix by roughly 400–500 m in mainland
     * China, which is enough to resolve a neighbouring building's address.
     * Outside China the two datums coincide and the input is returned as-is.
     */
    fun gcj02ToWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        if (isOutsideChina(lat, lng)) return lat to lng
        val (gcjLat, gcjLng) = wgs84ToGcj02(lat, lng)
        // One refinement pass is accurate to a few metres, well below GPS noise.
        return (lat * 2 - gcjLat) to (lng * 2 - gcjLng)
    }

    /**
     * WGS-84 (raw GPS) to GCJ-02 (the datum the app stores and the map shows).
     * Needed for the platform one-shot fallback: its GPS fix is WGS-84, while
     * everything else in the system (AMap fixes, places) is GCJ-02.
     */
    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (isOutsideChina(lat, lng)) return lat to lng
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - GCJ_EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic) * Math.PI)
        dLng = (dLng * 180.0) / (GCJ_A / sqrtMagic * cos(radLat) * Math.PI)
        return (lat + dLat) to (lng + dLng)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
            0.1 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }

    private fun isOutsideChina(lat: Double, lng: Double): Boolean =
        lng < 73.66 || lng > 135.05 || lat < 3.86 || lat > 53.55

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
