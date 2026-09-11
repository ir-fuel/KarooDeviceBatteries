package be.astus.karoodevicebatteries

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Stays alive in the background and reacts to Wi-Fi connectivity changes in real time
 * (via NetworkCallback) rather than relying on a periodic WorkManager check, which has
 * a 15-minute worst-case delay. Runs as a foreground service (with a low-priority,
 * persistent notification) because that's required for a background component to
 * survive Android's process management long enough to be useful here.
 *
 * Emits a periodic heartbeat log line specifically so gaps in HomeWifiForegroundService
 * logging (visible via the in-app log viewer) reveal whether/when the OS killed it.
 */
class HomeWifiForegroundService : Service() {

    private lateinit var connectivityManager: ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastSyncedSsid: String? = null

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            DiagnosticLog.d("Heartbeat: service alive, currentSsid=${WifiSsidHelper.getCurrentSsid(applicationContext) ?: "none"}")
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            DiagnosticLog.i("NetworkCallback.onAvailable")
            evaluateNetwork(connectivityManager.getNetworkCapabilities(network))
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            evaluateNetwork(capabilities)
        }

        override fun onLost(network: Network) {
            DiagnosticLog.i("NetworkCallback.onLost")
            lastSyncedSsid = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.init(applicationContext)
        DiagnosticLog.i("HomeWifiForegroundService onCreate")
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        startForeground(NOTIFICATION_ID, buildNotification("Watching for home Wi-Fi..."))

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        }.onFailure { DiagnosticLog.e("Failed to register NetworkCallback", it) }

        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagnosticLog.d("HomeWifiForegroundService onStartCommand (startId=$startId, action=${intent?.action})")
        if (intent?.action == ACTION_RECHECK) {
            // Settings (e.g. the home SSID) changed while already connected - there's no
            // new NetworkCallback event in that case, so re-evaluate the active network now
            // instead of waiting for the next connectivity transition.
            DiagnosticLog.i("HomeWifiForegroundService: explicit recheck requested")
            val activeNetwork = connectivityManager.activeNetwork
            evaluateNetwork(activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) })
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DiagnosticLog.w("HomeWifiForegroundService onTaskRemoved (app removed from recents)")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        DiagnosticLog.w("HomeWifiForegroundService onDestroy")
        handler.removeCallbacks(heartbeatRunnable)
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            .onFailure { DiagnosticLog.e("Failed to unregister NetworkCallback", it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun evaluateNetwork(capabilities: NetworkCapabilities?) {
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == false) {
            DiagnosticLog.d("evaluateNetwork: network not yet validated, waiting")
            return
        }

        val config = AppConfig(applicationContext)
        val homeSsid = config.homeSsid
        val currentSsid = WifiSsidHelper.getCurrentSsid(applicationContext)

        if (homeSsid.isNullOrBlank()) {
            DiagnosticLog.w("evaluateNetwork: home SSID not configured, ignoring (currentSsid=$currentSsid)")
            return
        }
        if (currentSsid == null) {
            DiagnosticLog.w("evaluateNetwork: could not read current SSID - check Location permission is granted, including \"Allow all the time\"")
            return
        }
        if (!currentSsid.equals(homeSsid.trim(), ignoreCase = true)) {
            DiagnosticLog.d("evaluateNetwork: currentSsid='$currentSsid' does not match home SSID='$homeSsid', skipping")
            lastSyncedSsid = null
            return
        }
        if (currentSsid == lastSyncedSsid) {
            DiagnosticLog.d("evaluateNetwork: already synced for '$currentSsid' on this connection, skipping duplicate trigger")
            return
        }

        DiagnosticLog.i("evaluateNetwork: matched home Wi-Fi '$currentSsid', enqueueing MqttWorker")
        lastSyncedSsid = currentSsid
        updateNotification("Syncing on home Wi-Fi ($currentSsid)...")
        WorkManager.getInstance(applicationContext).enqueue(OneTimeWorkRequestBuilder<MqttWorker>().build())
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Home Wi-Fi Sync",
                NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Karoo to MQTT")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "home_wifi_sync"
        private val HEARTBEAT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)
        private const val ACTION_RECHECK = "be.astus.karoodevicebatteries.action.RECHECK"

        fun recheckIntent(context: android.content.Context): Intent =
            Intent(context, HomeWifiForegroundService::class.java).setAction(ACTION_RECHECK)
    }
}
