package com.example.karoohaextension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class WifiTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.net.conn.CONNECTIVITY_CHANGE") return
        
        val config = AppConfig(context)
        val homeSsid = config.homeSsid ?: return

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val currentSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val transportInfo = capabilities.transportInfo
                (transportInfo as? WifiInfo)?.ssid
            } else {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiManager.connectionInfo?.ssid
            }?.replace("\"", "")

            if (currentSsid == homeSsid) {
                val workRequest = OneTimeWorkRequestBuilder<MqttWorker>().build()
                WorkManager.getInstance(context).enqueue(workRequest)
            }
        }
    }
}
