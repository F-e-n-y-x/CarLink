package com.carlink.app

import android.util.Log
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.HostIntegration
import com.carlink.app.connection.CarLinkConfig
import com.carlink.app.connection.CarLinkCore
import com.carlink.app.entry.HomeLauncher
import com.carlink.app.ui.HomeActivity
import com.carlink.app.ui.home.CarPlayWaitingContent
import com.shilapi.xcertplay.CarPlayHostIntegration

/**
 * One Application class for every process of the APK. It must be DiAuto's [App] because the
 * Android Auto engine casts the application context to it in ~100 places; the engine's own
 * startup only runs in the ":aa" process.
 */
class CarLinkApplication : App() {

    private val isAndroidAutoProcess: Boolean by lazy { CarLinkCore.isAndroidAutoProcess(this) }

    override fun initializesAndroidAutoEngine(): Boolean = isAndroidAutoProcess

    override fun onCreate() {
        // Before App.onCreate: it creates the engine's notification channels from these names.
        brandEngines()
        super.onCreate()
        // The connection backend (:carlink-core): engine hooks, USB watching, the session log.
        CarLinkCore.install(this, CarLinkConfig(
            accessoryName = getString(R.string.app_name),
            host = HomeLauncher,
            waitingView = CarPlayWaitingContent::create,
        ))
        if (isAndroidAutoProcess) {
            // Tells the main process's floating switcher when the Android Auto picture is in front.
            com.carlink.app.switcher.ProjectionVisibility.installInEngineProcess(this)
        } else {
            // The floating switcher over CarPlay / Android Auto (and the USB switch prompt).
            com.carlink.app.switcher.SwitcherController.install(this)
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

    private companion object {
        const val TOAST_TAG = "CarLink-EngineToast"
    }
}
