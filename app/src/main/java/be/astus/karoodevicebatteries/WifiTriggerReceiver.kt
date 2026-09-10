package be.astus.karoodevicebatteries

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class WifiTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Handle both legacy and modern connectivity actions
        if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION && 
            intent.action != "android.net.wifi.STATE_CHANGE") return
        
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return

        // Check if we are now connected to ANY Wi-Fi network
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            
            Log.d("KarooMQTT", "Wi-Fi connection detected, triggering sync...")
            
            val workRequest = OneTimeWorkRequestBuilder<MqttWorker>().build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}
