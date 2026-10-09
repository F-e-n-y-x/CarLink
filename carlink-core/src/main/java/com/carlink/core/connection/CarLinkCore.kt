package com.carlink.core.connection

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import com.andrerinas.openheadunit.utils.Settings
import com.carlink.core.connection.aa.AaEngineHooks
import com.carlink.core.connection.aa.AaProtocol
import com.carlink.core.core.PhoneKind
import com.carlink.core.diagnostics.EventLog
import com.carlink.core.entry.UsbWatcher
import com.shilapi.xcertplay.AirPlayPersistence

/**
 * What the backend needs from the app that hosts it. CarLink implements it with
 * `entry/HomeLauncher`; a launcher brings its own main screen forward.
 */
interface CarLinkHost {
    enum class OpenReason {
        /** The device booted and `CarLinkPreferences.startOnBoot` is on. */
        BOOT,

        /**
         * A known phone's Bluetooth connected (address, kind and name are set). The host's main
         * screen calls `Connection.get(context).onBluetoothPhone(address, kind, name)` once it is
         * in the foreground: Android does not let a background receiver start the engines.
         */
        PHONE,

        /** A USB session's phone was unplugged: the main screen replaces the dead projection. */
        UNPLUGGED,
    }

    /** Brings the host's main screen to the front from the background (main process, any thread). */
    fun openHome(context: Context, reason: OpenReason, address: String? = null, kind: PhoneKind? = null, name: String? = null)
}

/**
 * The backend's settings, fixed for the process.
 *
 * @param accessoryName the car's name as the phone shows it (CarPlay accessory, Android Auto car
 *   name, the iPhone's manufacturer and model for this car).
 * @param host see [CarLinkHost].
 * @param waitingView what CarPlay's projection window shows until the picture appears; null shows a
 *   neutral progress indicator. See [CarPlayWaitingView].
 */
class CarLinkConfig(
    val accessoryName: String,
    val host: CarLinkHost,
    val waitingView: ((Context) -> View)? = null,
)

/**
 * The backend's one entry point. Call [install] from `Application.onCreate` after
 * `super.onCreate()`, in every process of the app (the Android Auto engine runs in ":aa"); then use
 * [Connection.get] for sessions.
 */
object CarLinkCore {
    @Volatile private var installed: CarLinkConfig? = null

    /** The configuration given to [install]. */
    val config: CarLinkConfig
        get() = installed ?: error("CarLinkCore.install(application, config) was not called")

    /**
     * Installs the engines' hooks for this process. Main process: the connection log
     * (`files/logs/carlink.log`), the CarPlay hooks and waiting view, USB watching, and (after
     * [STARTUP_DEFER_MILLIS]) a warm ":aa" process and the decoder probe. ":aa": the Android Auto
     * engine's hooks.
     */
    fun install(app: Application, config: CarLinkConfig) {
        installed = config
        if (isAndroidAutoProcess(app)) {
            nameCarForPhone(app, config.accessoryName)
            // No sticky or self-restarting engine (DA§10.6.1), no BYD window claim off a BYD
            // (DA§12 #8), engine events and states to the main process.
            AaEngineHooks.install(app)
        } else {
            EventLog.attachFile(java.io.File(app.filesDir, "logs"))
            nameCarForIphone(app, config.accessoryName)
            // The car's name, the vehicle profile's BYD gate, the waiting view (DIPLAY.md §12, F2).
            CarPlayAdapter.install(app, Connection.vehicleProfile(app))
            CarPlayWaitingView.register(config.waitingView)
            UsbWatcher.get(app).start()
            deferStartupWork(app)
        }
    }

    /** True in the Android Auto engine's ":aa" process (the Application's `initializesAndroidAutoEngine`). */
    fun isAndroidAutoProcess(context: Context): Boolean = processName(context).endsWith(AaProtocol.ENGINE_PROCESS_SUFFIX)

    /**
     * Work that helps the first connection but not the first frame waits until the app has been up
     * for a moment (docs/LOW_END_PERFORMANCE.md). Starting Android Auto's ":aa" process here used to
     * fork and initialise a second app process while Home drew its first frame (two cores on a
     * cheap head unit), and made the session layer, created a few milliseconds later, see a live
     * engine process and run an orphan check and stop on every cold start.
     */
    private fun deferStartupWork(app: Application) {
        Handler(Looper.getMainLooper()).postDelayed({
            Connection.prewarm(app)
            // The decoder probe for Automatic picture, so the first session does not pay for it.
            Thread({ runCatching { DeviceCapabilities.read(app) } }, "carlink-capabilities").apply {
                priority = Thread.MIN_PRIORITY
            }.start()
        }, STARTUP_DEFER_MILLIS)
    }

    /**
     * The name the phone shows for this car in Android Auto (DiAuto's default is "DiAuto").
     * Cosmetic per DiAuto's Settings, and kept when the driver chose one.
     */
    private fun nameCarForPhone(app: Application, name: String) {
        val prefs = app.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_VEHICLE_DISPLAY_NAME)) {
            prefs.edit().putString(KEY_VEHICLE_DISPLAY_NAME, name).apply()
        }
    }

    /**
     * The car's manufacturer and model as CarPlay reports them to the iPhone (DiPlay's default is
     * "DiPlay"). Kept when the driver chose other values in the CarPlay menu.
     */
    private fun nameCarForIphone(app: Application, name: String) {
        if (AirPlayPersistence.loadManufacturer(app) == AirPlayPersistence.DEFAULT_MANUFACTURER) {
            AirPlayPersistence.saveManufacturer(app, name)
        }
        if (AirPlayPersistence.loadModel(app) == AirPlayPersistence.DEFAULT_MODEL) {
            AirPlayPersistence.saveModel(app, name)
        }
    }

    private fun processName(context: Context): String {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName()
        val pid = Process.myPid()
        return runCatching {
            java.io.File("/proc/$pid/cmdline").readText().trimEnd('\u0000')
        }.getOrDefault(context.packageName)
    }

    /** After Home's first frame on a slow head unit (≈1.3 s on the low-end emulator), with margin. */
    private const val STARTUP_DEFER_MILLIS = 2_500L
    private const val KEY_VEHICLE_DISPLAY_NAME = "vehicle-display-name"
}
