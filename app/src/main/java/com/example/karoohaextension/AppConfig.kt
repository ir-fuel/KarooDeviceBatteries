package com.example.karoohaextension

import android.content.Context
import android.content.SharedPreferences

class AppConfig(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_config", Context.MODE_PRIVATE)

    var mqttHost: String?
        get() = prefs.getString("mqtt_host", null)
        set(value) = prefs.edit().putString("mqtt_host", value).apply()

    var homeSsid: String?
        get() = prefs.getString("home_ssid", null)
        set(value) = prefs.edit().putString("home_ssid", value).apply()

    // Default HA MQTT port is 1883
    val mqttPort: Int = 1883
}
