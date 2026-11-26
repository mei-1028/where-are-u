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
 * (some AOSP builds, offline, or blocked in China) it falls back to coordinates,
 * so the UI always has something to show.
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

    private fun geocode(lat: Double, lng: Double): String {
        if (!Geocoder.isPresent()) return formatCoordinates(lat, lng)
        return try {
            @Suppress("DEPRECATION")
            val results = Geocoder(appContext, Locale.getDefault()).getFromLocation(lat, lng, 1)
            val first = results?.firstOrNull()
            val line = first?.getAddressLine(0)?.trim()?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(
                    first?.adminArea,
                    first?.locality,
                    first?.subLocality,
                    first?.thoroughfare,
                    first?.subThoroughfare,
                ).joinToString("").takeIf { it.isNotBlank() }
            line ?: formatCoordinates(lat, lng)
        } catch (t: Throwable) {
            Log.d(TAG, "Geocoder failed: ${t.message}")
            formatCoordinates(lat, lng)
        }
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
