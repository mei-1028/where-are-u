package com.whereareu.app.core.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.whereareu.app.core.model.LocationFix
import com.whereareu.app.core.permissions.PermissionChecker
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Platform one-shot fallback for devices where AMap's once-location callback
 * does not fire (observed with AMap 10.0.600). API 30+ uses
 * LocationManager.getCurrentLocation; older versions use requestSingleUpdate.
 */
class SystemOneShotLocator(private val context: Context) {

    suspend fun getCurrentLocation(timeoutMillis: Long): LocationFix? {
        if (!PermissionChecker.isFineLocationGranted(context)) {
            Log.w(TAG, "System one-shot skipped: fine location permission missing")
            return null
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = providerCandidates(manager)
        if (providers.isEmpty()) {
            Log.w(TAG, "System one-shot skipped: no enabled provider")
            return null
        }
        val perProviderTimeout = (timeoutMillis / providers.size).coerceAtLeast(1_500L)
        for (provider in providers) {
            val location = requestFromProvider(manager, provider, perProviderTimeout)
            if (location != null) {
                Log.i(TAG, "System one-shot succeeded via $provider")
                return location.toFix()
            }
            Log.w(TAG, "System one-shot returned no fix via $provider")
        }
        return null
    }

    private fun providerCandidates(manager: LocationManager): List<String> {
        val candidates = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) }
                .getOrDefault(false)
                .takeIf { it }
                ?.let { candidates += LocationManager.FUSED_PROVIDER }
        }
        runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }
            .getOrDefault(false)
            .takeIf { it }
            ?.let { candidates += LocationManager.GPS_PROVIDER }
        runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
            .getOrDefault(false)
            .takeIf { it }
            ?.let { candidates += LocationManager.NETWORK_PROVIDER }
        return candidates.distinct()
    }

    @Suppress("DEPRECATION")
    private suspend fun requestFromProvider(
        manager: LocationManager,
        provider: String,
        timeoutMillis: Long,
    ): Location? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cancellationSignal = CancellationSignal()
            return withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val finished = AtomicBoolean(false)
                    fun finish(location: Location?) {
                        if (finished.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                    continuation.invokeOnCancellation {
                        runCatching { cancellationSignal.cancel() }
                    }
                    try {
                        manager.getCurrentLocation(
                            provider,
                            cancellationSignal,
                            ContextCompat.getMainExecutor(context),
                        ) { location -> finish(location) }
                    } catch (t: Throwable) {
                        Log.w(TAG, "getCurrentLocation($provider) failed", t)
                        finish(null)
                    }
                }
            }.also { if (it == null) runCatching { cancellationSignal.cancel() } }
        }

        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val finished = AtomicBoolean(false)
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (finished.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(location)
                        }
                    }

                    override fun onProviderDisabled(provider: String) {
                        if (finished.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(null)
                        }
                    }

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                continuation.invokeOnCancellation {
                    runCatching { manager.removeUpdates(listener) }
                }
                try {
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (t: Throwable) {
                    Log.w(TAG, "requestSingleUpdate($provider) failed", t)
                    if (finished.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }

    private fun Location.toFix(): LocationFix = LocationFix(
        lat = latitude,
        lng = longitude,
        accuracy = accuracy,
        speed = speed,
        bearing = bearing,
        provider = provider?.takeIf { it.isNotBlank() } ?: "system",
        // The sample is requested now; acquisition time is more reliable than
        // a provider's possibly cached Location.time for command freshness.
        recordedAt = System.currentTimeMillis(),
        address = null,
        isMock = isFromMockProvider,
    )

    private companion object {
        const val TAG = "SystemOneShotLocator"
    }
}
