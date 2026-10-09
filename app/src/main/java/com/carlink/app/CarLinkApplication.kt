package com.carlink.app

import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.HostIntegration
import com.andrerinas.openheadunit.utils.Settings
import com.carlink.app.engine.AndroidAutoProtocol
import com.carlink.app.ui.HomeActivity
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayHostIntegration

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
        // Before App.onCreate: it creates the engine's notification channels from these names.
        brandEngines()
        super.onCreate()
        if (isAndroidAutoProcess) {
            nameCarForPhone()
            // No sticky or self-restarting engine (DA§10.6.1), no BYD window claim off a BYD
            // (DA§12 #8), engine events and states to the main process.
            com.carlink.app.connection.aa.AaEngineHooks.install(this)
        } else {
            nameCarForIphone()
            // The car's name, the vehicle profile's BYD gate, CarLink's waiting view (DIPLAY.md §12, F2).
            com.carlink.app.connection.CarPlayAdapter.install(this, com.carlink.app.connection.Connection.vehicleProfile(this))
            com.carlink.app.ui.home.CarPlayWaitingContent.register()
            com.carlink.app.entry.UsbWatcher.get(this).start()
        }
    }

    /**
     * The driver sees CarLink, never the engines' names: notification titles and channels, media
     * sessions, and the screens the engines open on their own (notification taps, boot launches,
     * Wi-Fi Direct returns, the projection's home button). Engine toasts are logged and dropped:
     * CarLink's Home shows the connection status itself. A toast the engine forces past its own
     * "toasts off" setting (a dead end with no other explanation) still shows.
     */
    private fun brandEngines() {
        val name = getString(R.string.app_name)
        HostIntegration.homeActivity = HomeActivity::class.java
        HostIntegration.appName = name
        HostIntegration.serviceChannelName = getString(R.string.carlink_notification_channel_android_auto)
        HostIntegration.mediaSessionTag = "CarLink Android Auto"
        HostIntegration.toastHandler = { _, text, forced ->
            Log.i(TOAST_TAG, "Android Auto engine toast forced=$forced: $text")
            !forced
        }
        CarPlayHostIntegration.homeActivity = HomeActivity::class.java
        CarPlayHostIntegration.appName = name
        CarPlayHostIntegration.mediaSessionTag = "CarLink CarPlay"
        CarPlayHostIntegration.toastHandler = { _, text ->
            Log.i(TOAST_TAG, "CarPlay engine toast: $text")
            true
        }
        // The projections' own chrome (waiting screens, session menus, panels) in CarLink's look.
        com.carlink.app.engine.EngineChrome.install(this)
    }

    /**
     * The name the phone shows for this car in Android Auto (DiAuto's default is "DiAuto").
     * Cosmetic per DiAuto's Settings, and kept when the driver chose one.
     */
    private fun nameCarForPhone() {
        val prefs = getSharedPreferences(Settings.PREFS_NAME, MODE_PRIVATE)
        if (!prefs.contains(KEY_VEHICLE_DISPLAY_NAME)) {
            prefs.edit().putString(KEY_VEHICLE_DISPLAY_NAME, getString(R.string.app_name)).apply()
        }
    }

    /**
     * The car's manufacturer and model as CarPlay reports them to the iPhone (DiPlay's default is
     * "DiPlay"). Kept when the driver chose other values in the CarPlay menu.
     */
    private fun nameCarForIphone() {
        val name = getString(R.string.app_name)
        if (AirPlayPersistence.loadManufacturer(this) == AirPlayPersistence.DEFAULT_MANUFACTURER) {
            AirPlayPersistence.saveManufacturer(this, name)
        }
        if (AirPlayPersistence.loadModel(this) == AirPlayPersistence.DEFAULT_MODEL) {
            AirPlayPersistence.saveModel(this, name)
        }
    }

    private fun processName(): String {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName()
        val pid = Process.myPid()
        return runCatching {
            java.io.File("/proc/$pid/cmdline").readText().trimEnd('\u0000')
        }.getOrDefault(packageName)
    }

    private companion object {
        const val TOAST_TAG = "CarLink-EngineToast"
        const val KEY_VEHICLE_DISPLAY_NAME = "vehicle-display-name"
    }
}
