package be.astus.karoodevicebatteries

import android.content.Context
import android.net.wifi.WifiManager

object WifiSsidHelper {
    /**
     * Returns the currently connected Wi-Fi SSID, or null if not connected to Wi-Fi
     * or if the SSID can't be read. On Android 10+, reading the real SSID from a
     * background context (as opposed to a foreground Activity) requires
     * ACCESS_BACKGROUND_LOCATION - without it this silently returns "<unknown ssid>",
     * which is treated here the same as null.
     */
    fun getCurrentSsid(context: Context): String? {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ssid = wifiManager?.connectionInfo?.ssid?.trim('"')
        return if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") null else ssid
    }
}
