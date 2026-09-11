package be.astus.karoodevicebatteries

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

    // Comma-separated Karoo device ids to hide from the sensor list and skip when
    // publishing - for stale SavedDevices entries the Karoo system service keeps
    // reporting even after they've disappeared from Karoo's own Sensors settings.
    var ignoredDeviceIds: String?
        get() = prefs.getString("ignored_device_ids", null)
        set(value) = prefs.edit().putString("ignored_device_ids", value).apply()

    fun isDeviceIgnored(deviceId: String): Boolean =
        ignoredDeviceIds.orEmpty().split(",").map { it.trim() }.contains(deviceId)

    // Default MQTT port is 1883
    val mqttPort: Int = 1883
}
