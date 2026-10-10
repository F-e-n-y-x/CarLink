package com.carlink.core.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.carlink.core.core.Engine
import com.carlink.core.settings.WifiEnvironmentReader
import com.carlink.core.settings.UnifiedSettings
import com.carlink.core.settings.UnifiedSettingsStore
import com.carlink.core.settings.CarBrand
import com.carlink.core.settings.CarBrandStore
import com.carlink.core.settings.VehicleProfile
import com.carlink.core.settings.WifiEnvironment
import com.carlink.core.settings.WirelessPath
import com.carlink.core.settings.isValid
import com.carlink.core.connection.aa.AaSessionSettings
import java.util.concurrent.Executors

/**
 * CarLink's single [SessionManager] with the real engines (main process). Everything that starts,
 * stops or watches a session goes through [get].
 */
object Connection {
    private const val TAG = "CarLink-Connection"

    @Volatile private var instance: SessionManager? = null

    fun get(context: Context): SessionManager = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    /**
     * Starts Android Auto's ":aa" process ahead of time (reading one setting starts the process
     * but not the engine), so a phone plugged in later does not wait for a cold process start:
     * 4.5 s from plug-in to the engine on the tablet (2026-10-09). Cheap when it already runs.
     */
    fun prewarm(context: Context) {
        val app = context.applicationContext
        // App start and Home's resume both ask within milliseconds of each other on a cold start.
        if (!prewarming.compareAndSet(false, true)) return
        Thread({
            try {
                val started = android.os.SystemClock.uptimeMillis()
                runCatching { com.carlink.core.engine.AndroidAutoSettings.read(app, listOf("wifi-connection-mode")) }
                val took = android.os.SystemClock.uptimeMillis() - started
                if (took > 300) com.carlink.core.diagnostics.EventLog.add("CarLink", "Android Auto engine process warmed in $took ms")
            } finally {
                prewarming.set(false)
            }
        }, "carlink-prewarm").start()
    }

    private val prewarming = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Home is in front: start the last-used phone wirelessly when the rules allow (see [SessionManager.onHomeShown]). */
    @android.annotation.SuppressLint("MissingPermission") // The name is read only when the permission allows.
    fun onHomeShown(context: Context) {
        val app = context.applicationContext
        val address = DeviceEnvironment.lastPhoneAddress(app) ?: return
        val kind = com.carlink.core.detect.KnownPhones(app).kindOf(address)
        val name = runCatching {
            app.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.firstOrNull { it.address.equals(address, ignoreCase = true) }?.name
        }.getOrNull()
        get(app).onHomeShown(address, kind, name)
    }

    /**
     * What the engines may do in this car: the chosen [carBrand]'s profile (only BYD is
     * [VehicleProfile.BYD_DILINK]), or what this device looks like when the driver never chose.
     */
    fun vehicleProfile(context: Context): VehicleProfile = CarBrandStore(context).profile()

    /** The driver's car brand, or null when they never chose (then [CarBrand.detect] may still say BYD). */
    fun carBrand(context: Context): CarBrand? = CarBrandStore(context).chosen()

    /**
     * Stores the driver's car brand. Only its [CarBrand.profile] reaches the engines; the brand is
     * never sent to the phone (see [CarBrand]). Android Auto reads the profile at each session
     * start; CarPlay's hooks read it at app start, so they are installed again here when the
     * profile changes ([CarPlayAdapter.install] only sets DiPlay's host fields, so a second call is
     * safe). The driver is told it applies from the next connection: Android Auto's session keeps
     * its profile, and DiPlay reads the BYD switch each time it uses a BYD feature, so a running
     * CarPlay session is consistent only after a reconnect.
     */
    fun setCarBrand(context: Context, brand: CarBrand) {
        val app = context.applicationContext
        if (CarBrandStore(app).set(brand)) CarPlayAdapter.install(app, brand.profile)
    }

    /**
     * Before car brands: [VehicleProfile.BYD_DILINK] stores [CarBrand.BYD]; [VehicleProfile.GENERIC]
     * keeps a chosen non-BYD brand and otherwise stores [CarBrand.OTHER].
     */
    @Deprecated("Choose a car brand; its profile follows from it.", ReplaceWith("Connection.setCarBrand(context, brand)"))
    fun setVehicleProfile(context: Context, profile: VehicleProfile) {
        val current = carBrand(context)
        val brand = if (current != null && current.profile == profile) current else CarBrand.fromProfile(profile)
        setCarBrand(context, brand)
    }

    private fun create(app: Context): SessionManager {
        val unified = UnifiedSettingsStore(app)
        val adapters = mapOf(
            Engine.CARPLAY to CarPlayAdapter(app),
            Engine.ANDROID_AUTO to AndroidAutoAdapter(app) { vehicleProfile(app) },
        )
        val environment = DeviceEnvironment(app) { request, path -> enginePreflight(app, unified.load(), request, path) }
        return SessionManager(
            adapters = adapters,
            environment = environment,
            applySettings = { plan -> applySession(app, plan) },
            worker = Executors.newSingleThreadExecutor { Thread(it, "carlink-session") },
            main = Handler(Looper.getMainLooper()),
            profiles = ConnectionProfiles.sharedPreferences(app),
        )
    }

    /** Settings with the car's own hotspot filled in where the firmware lets CarLink read it. */
    fun effectiveSettings(settings: UnifiedSettings, wifi: WifiEnvironment): UnifiedSettings =
        if (!settings.carHotspot.isValid() && wifi.systemHotspot != null) settings.copy(carHotspot = wifi.systemHotspot) else settings

    /** Writes the session's settings into its engine (worker thread; IPC to ":aa" for Android Auto). */
    private fun applySession(app: Context, plan: SessionPlan) {
        val started = android.os.SystemClock.uptimeMillis()
        try {
            writeSession(app, plan)
        } finally {
            com.carlink.core.diagnostics.EventLog.add("CarLink",
                "${plan.engine} settings written in ${android.os.SystemClock.uptimeMillis() - started} ms")
        }
    }

    private fun writeSession(app: Context, plan: SessionPlan) {
        val stored = UnifiedSettingsStore(app).load()
        val wifi = WifiEnvironmentReader.read(app, stored)
        val settings = effectiveSettings(stored, wifi)
        val profile = vehicleProfile(app)
        val picture = automaticPicture(app, settings)
        when (plan.engine) {
            Engine.CARPLAY -> {
                val fineLocation = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                CarPlaySettingsPolicy.apply(app, CarPlaySettingsPolicy.map(settings, plan.path, profile, fineLocation,
                    brandName = CarLinkCore.config.accessoryName, picture = picture))
            }
            Engine.ANDROID_AUTO -> {
                val values = AaSessionSettings.values(settings, plan.transport, plan.path, profile,
                    carName = CarLinkCore.config.accessoryName, panelDensityDpi = app.resources.displayMetrics.densityDpi, wifi = wifi,
                    picture = picture)
                values.problem?.let { Log.w(TAG, "Android Auto settings: $it") }
                AaSessionSettings.apply(app, values)
            }
        }
    }

    /** Automatic picture from this device's decoders ([PicturePolicy]); probed once, on the session worker. */
    private fun automaticPicture(app: Context, settings: UnifiedSettings): PictureChoice {
        if (settings.picture != com.carlink.core.settings.Picture.AUTOMATIC) return PicturePolicy.ENGINE_DEFAULTS
        val choice = runCatching { PicturePolicy.choose(DeviceCapabilities.read(app)) }.getOrDefault(PicturePolicy.ENGINE_DEFAULTS)
        com.carlink.core.diagnostics.EventLog.add("CarLink", "Automatic picture: ${choice.summary}")
        return choice
    }

    /** Each engine's own checks, in [Problem] terms. */
    private fun enginePreflight(app: Context, settings: UnifiedSettings, request: SessionRequest, path: WirelessPath?): List<Problem> {
        if (request.engine != Engine.CARPLAY) return emptyList()
        val plan = SessionPlan(request.engine, request.transport, path, request.phoneAddress, request.phoneName, request.usbAttach)
        return CarPlayPreflight.check(app, plan, settings).map { problem ->
            when (problem) {
                CarPlayProblem.AuthenticationMissing -> Problem.CARPLAY_AUTH_MISSING
                CarPlayProblem.VpnConsentNeeded -> Problem.VPN_CONSENT
                is CarPlayProblem.PermissionsMissing -> Problem.PERMISSIONS
                CarPlayProblem.BluetoothOff -> Problem.BLUETOOTH_OFF
                CarPlayProblem.NoIphoneSelected -> Problem.NO_IPHONE_SELECTED
                is CarPlayProblem.IphoneNotBonded -> Problem.PHONE_NOT_PAIRED
                CarPlayProblem.WifiOff -> Problem.WIFI_OFF
                is CarPlayProblem.SameWifiNameUnreadable ->
                    if (problem.needsLocationOn) Problem.LOCATION_OFF
                    else if (problem.needsLocationPermission) Problem.PERMISSIONS else Problem.SAME_WIFI_DETAILS
                CarPlayProblem.SameWifiDetailsMissing, CarPlayProblem.SameWifiNotConnected,
                CarPlayProblem.SameWifiMultipleNetworks, is CarPlayProblem.SameWifiDifferentNetwork -> Problem.SAME_WIFI_DETAILS
                CarPlayProblem.CarHotspotDetailsMissing -> Problem.CAR_HOTSPOT_DETAILS
                CarPlayProblem.CarHotspotOff -> Problem.CAR_HOTSPOT_OFF
            }
        }
    }
}
