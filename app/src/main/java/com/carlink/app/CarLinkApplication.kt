package com.carlink.app

import android.app.Application
import android.os.Build
import android.os.Process
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.HostIntegration
import com.carlink.app.engine.AndroidAutoProtocol
import com.carlink.app.engine.AndroidAutoStateReporter
import com.carlink.app.ui.HomeActivity

/**
 * One Application class for every process of the APK. It must be DiAuto's [App] because the
 * Android Auto engine casts the application context to it in ~100 places; the engine's own
 * startup only runs in the ":aa" process.
 */
class CarLinkApplication : App() {

    private val isAndroidAutoProcess: Boolean by lazy {
        processName().endsWith(AndroidAutoProtocol.ENGINE_PROCESS_SUFFIX)
    }

    override fun initializesAndroidAutoEngine(): Boolean = isAndroidAutoProcess

    override fun onCreate() {
        super.onCreate()
        // Notification taps, boot launches and Wi-Fi Direct returns open CarLink, not DiAuto's home.
        HostIntegration.homeActivity = HomeActivity::class.java
        if (isAndroidAutoProcess) {
            AndroidAutoStateReporter.start(this)
        } else {
            // DiPlay's projection returns to CarLink's Home, never to DiPlay's own screens.
            com.shilapi.xcertplay.CarPlayHostIntegration.homeActivity = HomeActivity::class.java
            com.carlink.app.entry.UsbWatcher.get(this).start()
        }
    }

    private fun processName(): String {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName()
        val pid = Process.myPid()
        return runCatching {
            java.io.File("/proc/$pid/cmdline").readText().trimEnd('\u0000')
        }.getOrDefault(packageName)
    }
}
