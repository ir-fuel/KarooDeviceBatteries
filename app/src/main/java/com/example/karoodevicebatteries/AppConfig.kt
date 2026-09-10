package com.example.karoodevicebatteries

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AppConfig(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_app_config",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var mqttHost: String?
        get() = prefs.getString("mqtt_host", null)
        set(value) = prefs.edit().putString("mqtt_host", value).apply()

    var mqttUsername: String?
        get() = prefs.getString("mqtt_username", null)
        set(value) = prefs.edit().putString("mqtt_username", value).apply()

    var mqttPassword: String?
        get() = prefs.getString("mqtt_password", null)
        set(value) = prefs.edit().putString("mqtt_password", value).apply()

    var homeSsid: String?
        get() = prefs.getString("home_ssid", null)
        set(value) = prefs.edit().putString("home_ssid", value).apply()

    // Default MQTT port is 1883
    val mqttPort: Int = 1883
}
