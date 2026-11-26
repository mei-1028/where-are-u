package com.whereareu.app.core.location

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.whereareu.app.BuildConfig
import com.whereareu.app.core.model.LocationFix
import com.whereareu.app.core.permissions.PermissionChecker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.min

/**
 * AMap (com.amap.api:location) implementation.
 *
 * Privacy compliance: updatePrivacyShow/updatePrivacyAgree must be called before
 * any location request. The key is injected into the manifest (and set again via
 * the SDK API) from gradle.properties / local.properties.
 */
class AmapLocationProvider(context: Context) : LocationProvider {

    private val appContext = context.applicationContext
    private val lock = Any()
    private val systemOneShotLocator = SystemOneShotLocator(appContext)

    override val name: String = "amap"

    private val _fixes = MutableSharedFlow<LocationFix>(extraBufferCapacity = 8)
    override val fixes: Flow<LocationFix> = _fixes.asSharedFlow()

    private var client: AMapLocationClient? = null
    private var currentIntervalMillis: Long = 180_000L

    init {
        initPrivacyCompliance()
    }

    private fun initPrivacyCompliance() {
        try {
            AMapLocationClient.updatePrivacyShow(appContext, true, true)
            AMapLocationClient.updatePrivacyAgree(appContext, true)
            if (BuildConfig.AMAP_KEY.isNotBlank()) {
                AMapLocationClient.setApiKey(BuildConfig.AMAP_KEY)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AMap privacy setup failed", t)
        }
    }

    override fun start(intervalMillis: Long) {
        if (!PermissionChecker.isForegroundLocationGranted(appContext)) {
            Log.w(TAG, "Location permission missing, not starting AMap")
            return
        }
        synchronized(lock) {
            stopLocked()
            currentIntervalMillis = intervalMillis.coerceAtLeast(MIN_INTERVAL_MILLIS)
            try {
                val newClient = AMapLocationClient(appContext)
                newClient.setLocationListener { location ->
                    if (location != null) {
                        if (location.errorCode == 0) {
                            _fixes.tryEmit(location.toFix())
                        } else {
                            Log.w(TAG, "AMap error ${location.errorCode}: ${location.errorInfo}")
                        }
                    }
                }
                newClient.setLocationOption(buildOption(currentIntervalMillis, once = false))
                newClient.startLocation()
                client = newClient
                Log.i(TAG, "AMap started, interval=${currentIntervalMillis}ms")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start AMap location", t)
            }
        }
    }

    override fun updateInterval(intervalMillis: Long) {
        val normalized = intervalMillis.coerceAtLeast(MIN_INTERVAL_MILLIS)
        if (abs(normalized - currentIntervalMillis) < INTERVAL_CHANGE_THRESHOLD_MS) return
        synchronized(lock) {
            currentIntervalMillis = normalized
            val active = client ?: return
            try {
                // AMap may ignore a changed interval on a running client, so
                // restart with the new option in place.
                active.stopLocation()
                active.setLocationOption(buildOption(currentIntervalMillis, once = false))
                active.startLocation()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to update AMap interval", t)
            }
        }
    }

    override fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        client?.let { active ->
            runCatching { active.stopLocation() }
            runCatching { active.onDestroy() }
        }
        client = null
    }

    override suspend fun getOneShotHighAccuracyFix(timeoutMillis: Long): LocationFix? {
        if (!PermissionChecker.isForegroundLocationGranted(appContext)) {
            Log.w(TAG, "One-shot skipped: location permission missing")
            return null
        }
        val startedAt = SystemClock.elapsedRealtime()

        val amapFix = withTimeoutOrNull(min(timeoutMillis, AMAP_ONE_SHOT_TIMEOUT_MS)) {
            requestAmapOneShot()
        }
        if (amapFix != null) {
            Log.i(TAG, "One-shot AMap fix succeeded")
            return amapFix
        }

        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val remaining = (timeoutMillis - elapsed).coerceAtLeast(MIN_SYSTEM_TIMEOUT_MS)
        Log.w(
            TAG,
            "AMap one-shot produced no fix after ${elapsed}ms; " +
                "falling back to system LocationManager for ${remaining}ms",
        )
        val systemFix = systemOneShotLocator.getCurrentLocation(
            min(remaining, SYSTEM_ONE_SHOT_TIMEOUT_MS),
        )
        if (systemFix != null) {
            Log.i(TAG, "One-shot system fallback succeeded")
            return systemFix
        }
        Log.e(TAG, "One-shot location failed: both AMap and system LocationManager returned no fix")
        return null
    }

    private suspend fun requestAmapOneShot(): LocationFix? =
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            var onceClient: AMapLocationClient? = null

            fun finish(fix: LocationFix?) {
                if (!finished.compareAndSet(false, true)) return
                runCatching {
                    onceClient?.stopLocation()
                    onceClient?.onDestroy()
                }
                if (continuation.isActive) continuation.resume(fix)
            }

            try {
                val newClient = AMapLocationClient(appContext)
                onceClient = newClient
                newClient.setLocationOption(buildOption(intervalMillis = 10_000L, once = true))
                newClient.setLocationListener { location ->
                    if (location != null && location.errorCode == 0) {
                        // Acquisition happens now; use the receipt time as
                        // recordedAt so the server sees a command-fresh point.
                        finish(location.toFix().copy(recordedAt = System.currentTimeMillis()))
                    } else {
                        Log.w(TAG, "One-shot AMap fix failed: ${location?.errorInfo}")
                        finish(null)
                    }
                }
                continuation.invokeOnCancellation {
                    if (finished.compareAndSet(false, true)) {
                        runCatching {
                            newClient.stopLocation()
                            newClient.onDestroy()
                        }
                    }
                }
                newClient.startLocation()
            } catch (t: Throwable) {
                Log.e(TAG, "One-shot AMap start failed", t)
                finish(null)
            }
        }

    private fun buildOption(intervalMillis: Long, once: Boolean): AMapLocationClientOption =
        AMapLocationClientOption().apply {
            setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy)
            setInterval(intervalMillis.coerceAtLeast(MIN_INTERVAL_MILLIS))
            setOnceLocation(once)
            if (once) {
                setOnceLocationLatest(true)
            }
            setNeedAddress(true)
            setMockEnable(false)
            setLocationCacheEnable(false)
        }

    private fun AMapLocation.toFix(): LocationFix = LocationFix(
        lat = latitude,
        lng = longitude,
        accuracy = accuracy,
        speed = speed,
        bearing = bearing,
        provider = provider?.takeIf { it.isNotBlank() } ?: "amap",
        recordedAt = time.takeIf { it > 0 } ?: System.currentTimeMillis(),
        address = address?.takeIf { it.isNotBlank() },
        isMock = isMock,
    )

    private companion object {
        const val TAG = "AmapLocationProvider"
        const val MIN_INTERVAL_MILLIS = 10_000L
        const val INTERVAL_CHANGE_THRESHOLD_MS = 15_000L
        const val AMAP_ONE_SHOT_TIMEOUT_MS = 6_000L
        const val SYSTEM_ONE_SHOT_TIMEOUT_MS = 8_000L
        const val MIN_SYSTEM_TIMEOUT_MS = 2_000L
    }
}
