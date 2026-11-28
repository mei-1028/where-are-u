package com.whereareu.app.core.util

import android.content.Context
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

interface AddressResolver {
    suspend fun resolve(lat: Double, lng: Double): String
}

/**
 * Uses the device geocoder (no extra SDK/key). When geocoding is unavailable
 * (some AOSP builds, offline, or blocked) it falls back to a short coordinate
 * string, so the UI always has something to show.
 *
 * Coordinates arriving here are GCJ-02 (the datum AMap reports), while the
 * platform geocoder expects WGS-84. Querying with the raw value lands a few
 * hundred metres away in China — often on a neighbouring building — so the
 * datum is converted first.
 */
class AndroidAddressResolver(context: Context) : AddressResolver {

    private val appContext = context.applicationContext
    private val cache = object : LinkedHashMap<String, String>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_CACHE
    }

    override suspend fun resolve(lat: Double, lng: Double): String = withContext(Dispatchers.IO) {
        val key = cacheKey(lat, lng)
        synchronized(cache) { cache[key] }?.let { return@withContext it }

        val address = geocode(lat, lng)
        synchronized(cache) { cache[key] = address }
        address
    }

    private fun geocode(gcjLat: Double, gcjLng: Double): String {
        if (!Geocoder.isPresent()) return formatCoordinates(gcjLat, gcjLng)
        val (lat, lng) = GeoUtils.gcj02ToWgs84(gcjLat, gcjLng)
        return try {
            @Suppress("DEPRECATION")
            val results = Geocoder(appContext, Locale.getDefault()).getFromLocation(lat, lng, 1)
            val first = results?.firstOrNull()
            val line = first?.getAddressLine(0)?.trim()?.takeIf { it.isNotBlank() }
                ?: assembleAddress(first)
            line ?: formatCoordinates(gcjLat, gcjLng)
        } catch (t: Throwable) {
            Log.d(TAG, "Geocoder failed: ${t.message}")
            formatCoordinates(gcjLat, gcjLng)
        }
    }

    /**
     * Rebuilds a one-line address when the platform gives no address line.
     *
     * Chinese admin names nest ("苏州市" contains "常熟市" as a county-level
     * city), so naive concatenation produced "常熟市常熟市…". A part that is
     * already a suffix of the accumulated text is skipped.
     */
    private fun assembleAddress(address: android.location.Address?): String? {
        if (address == null) return null
        val parts = listOfNotNull(
            address.adminArea,
            address.locality,
            address.subLocality,
            address.thoroughfare,
            address.subThoroughfare,
        ).map { it.trim() }.filter { it.isNotEmpty() }

        val builder = StringBuilder()
        for (part in parts) {
            if (builder.endsWith(part)) continue
            builder.append(part)
        }
        return builder.toString().takeIf { it.isNotBlank() }
    }

    private fun cacheKey(lat: Double, lng: Double): String =
        String.format(Locale.US, "%.4f,%.4f", lat, lng)

    private companion object {
        const val TAG = "AddressResolver"
        const val MAX_CACHE = 256
    }
}

fun formatCoordinates(lat: Double, lng: Double): String =
    String.format(Locale.US, "%.5f, %.5f", lat, lng)
