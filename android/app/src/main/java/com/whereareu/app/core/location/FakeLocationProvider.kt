package com.whereareu.app.core.location

import android.content.Context
import android.util.Log
import com.whereareu.app.core.model.LocationFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Simulated provider used when AMAP_KEY is blank (or during development).
 * It walks a small loop around West Lake, Hangzhou and alternates between
 * stationary and moving speed so the adaptive cadence is visible.
 */
class FakeLocationProvider(@Suppress("UNUSED_PARAMETER") context: Context) : LocationProvider {

    override val name: String = "fake"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _fixes = MutableSharedFlow<LocationFix>(extraBufferCapacity = 8)
    override val fixes: Flow<LocationFix> = _fixes.asSharedFlow()

    @Volatile private var job: Job? = null
    @Volatile private var intervalMillis: Long = 180_000L
    private var sampleIndex = 0

    override fun start(intervalMillis: Long) {
        this.intervalMillis = intervalMillis.coerceAtLeast(10_000L)
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                _fixes.emit(nextFix())
                delay(this@FakeLocationProvider.intervalMillis)
            }
        }
        Log.i(TAG, "Fake location provider started, interval=${this.intervalMillis}ms")
    }

    override fun updateInterval(intervalMillis: Long) {
        this.intervalMillis = intervalMillis.coerceAtLeast(10_000L)
    }

    override fun stop() {
        job?.cancel()
        job = null
        Log.i(TAG, "Fake location provider stopped")
    }

    override suspend fun getOneShotHighAccuracyFix(timeoutMillis: Long): LocationFix = nextFix()

    private fun nextFix(): LocationFix {
        sampleIndex += 1
        val step = sampleIndex % 20
        val lat = BASE_LAT + step * 0.00035 + (sampleIndex % 3) * 0.00004
        val lng = BASE_LNG + step * 0.00042
        val stationary = sampleIndex % 4 < 2
        return LocationFix(
            lat = lat,
            lng = lng,
            accuracy = if (stationary) 16f else 22f,
            speed = if (stationary) 0f else 6.4f,
            bearing = 34f,
            provider = "fake",
            recordedAt = System.currentTimeMillis(),
            address = "模拟位置 · 杭州西湖",
            isMock = true,
        )
    }

    private companion object {
        const val TAG = "FakeLocationProvider"
        const val BASE_LAT = 30.2741
        const val BASE_LNG = 120.1551
    }
}
