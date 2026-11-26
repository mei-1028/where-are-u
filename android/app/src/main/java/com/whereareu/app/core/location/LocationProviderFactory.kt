package com.whereareu.app.core.location

import android.content.Context
import com.whereareu.app.BuildConfig

/**
 * AMap is used only when an AMAP_KEY is configured. Without a key the app still
 * runs end-to-end with the schematic map and simulated location fixes.
 */
class LocationProviderFactory(private val context: Context) {

    val usingFakeProvider: Boolean get() = BuildConfig.AMAP_KEY.isBlank()

    val providerName: String get() = if (usingFakeProvider) "模拟定位" else "高德定位"

    fun create(): LocationProvider =
        if (usingFakeProvider) {
            FakeLocationProvider(context)
        } else {
            AmapLocationProvider(context)
        }
}
