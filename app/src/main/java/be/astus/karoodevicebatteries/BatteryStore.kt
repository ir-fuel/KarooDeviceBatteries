package be.astus.karoodevicebatteries

import android.content.Context
import android.content.SharedPreferences

class BatteryStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("battery_store", Context.MODE_PRIVATE)

    fun savePercentage(sensorId: String, percentage: Int) {
        prefs.edit().putInt(sensorId, percentage).apply()
    }

    fun getPercentage(sensorId: String): Int {
        return prefs.getInt(sensorId, -1)
    }
}
