package be.astus.karoodevicebatteries

import android.app.Application

class KarooBatteriesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.init(this)
        DiagnosticLog.i("Process started")
    }
}
