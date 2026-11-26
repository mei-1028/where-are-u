package com.whereareu.app

import android.app.Application
import android.util.Log
import com.amap.api.maps.MapsInitializer
import com.whereareu.app.core.AppGraph
import com.whereareu.app.core.notifications.Notifications

class WhereAreUApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        Notifications.ensureChannels(this)

        // AMap requires privacy consent before any map/location SDK call.
        // Only initialize when a key is configured; otherwise the app uses the
        // Compose schematic map and the fake location provider.
        if (BuildConfig.AMAP_KEY.isNotBlank()) {
            runCatching {
                MapsInitializer.updatePrivacyShow(this, true, true)
                MapsInitializer.updatePrivacyAgree(this, true)
                MapsInitializer.setApiKey(BuildConfig.AMAP_KEY)
            }.onFailure { error ->
                Log.w(TAG, "AMap privacy initialization failed", error)
            }
        }
    }

    private companion object {
        const val TAG = "WhereAreUApp"
    }
}
