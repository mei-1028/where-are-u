package com.whereareu.app.core.util

import android.content.Context
import android.os.BatteryManager

object BatteryReader {
    fun percent(context: Context): Int? {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level.takeIf { it in 0..100 }
    }
}
