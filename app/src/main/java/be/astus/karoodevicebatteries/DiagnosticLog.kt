package be.astus.karoodevicebatteries

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Persists log lines to a capped file in app-private storage so behavior of the
 * background foreground service (which may be killed and restarted, or run after a
 * reboot with no UI ever opened) can be inspected later from the app's own log
 * viewer, without needing adb attached to the Karoo.
 */
object DiagnosticLog {
    private const val TAG = "KarooMQTT"
    private const val FILE_NAME = "karoo_mqtt_diagnostic.log"
    private const val MAX_SIZE_BYTES = 512 * 1024L

    private val lock = ReentrantLock()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile != null) return
        logFile = File(context.applicationContext.filesDir, FILE_NAME)
    }

    fun d(message: String) {
        Log.d(TAG, message)
        write("D", message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
        write("I", message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
        write("W", message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        write("E", if (throwable != null) "$message: $throwable" else message)
    }

    fun readAll(): String {
        val file = logFile ?: return "(log not initialized yet)"
        if (!file.exists()) return "(no log entries yet)"
        return lock.withLock { runCatching { file.readText() }.getOrDefault("(failed to read log)") }
    }

    fun clear() {
        val file = logFile ?: return
        lock.withLock { runCatching { file.writeText("") } }
    }

    private fun write(level: String, message: String) {
        val file = logFile ?: return
        lock.withLock {
            runCatching {
                if (file.exists() && file.length() > MAX_SIZE_BYTES) {
                    val tail = file.readText().takeLast((MAX_SIZE_BYTES / 2).toInt())
                    file.writeText(tail)
                }
                file.appendText("${timeFormat.format(Date())} [$level] $message\n")
            }
        }
    }
}
