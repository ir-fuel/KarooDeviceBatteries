package be.astus.karoodevicebatteries

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

// BOOT_COMPLETED is one of the few implicit broadcasts still delivered to
// manifest-declared receivers on API 26+ (unlike CONNECTIVITY_CHANGE, which is not).
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        DiagnosticLog.init(context.applicationContext)
        DiagnosticLog.i("BootCompletedReceiver: starting HomeWifiForegroundService after boot")
        ContextCompat.startForegroundService(context, Intent(context, HomeWifiForegroundService::class.java))
    }
}
