package com.carlink.app.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.settings.WifiEnvironmentReader
import com.carlink.app.settings.UnifiedSettings
import com.carlink.app.settings.UnifiedSettingsStore
import com.carlink.app.settings.VehicleProfile
import com.carlink.app.settings.WifiEnvironment
import com.carlink.app.settings.WirelessPath
import com.carlink.app.settings.isValid
import com.carlink.app.connection.aa.AaSessionSettings
import java.util.concurrent.Executors

/**
 * CarLink's single [SessionManager] with the real engines (main process). Everything that starts,
 * stops or watches a session goes through [get].
 */
object Connection {
    private const val TAG = "CarLink-Connection"
    private const val PREFS = "carlink_vehicle"
    private const val KEY_PROFILE = "vehicle_profile"

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
        Thread({
            val started = android.os.SystemClock.uptimeMillis()
            runCatching { com.carlink.app.engine.AndroidAutoSettings.read(app, listOf("wifi-connection-mode")) }
            val took = android.os.SystemClock.uptimeMillis() - started
            if (took > 300) com.carlink.app.diagnostics.EventLog.add("CarLink", "Android Auto engine process warmed in $took ms")
        }, "carlink-prewarm").start()
    }

    /** Home is in front: start the last-used phone wirelessly when the rules allow (see [SessionManager.onHomeShown]). */
    @android.annotation.SuppressLint("MissingPermission") // The name is read only when the permission allows.
    fun onHomeShown(context: Context) {
        val app = context.applicationContext
        val address = DeviceEnvironment.lastPhoneAddress(app) ?: return
        val kind = com.carlink.app.detect.KnownPhones(app).kindOf(address)
        val name = runCatching {
            app.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.firstOrNull { it.address.equals(address, ignoreCase = true) }?.name
        }.getOrNull()
        get(app).onHomeShown(address, kind, name)
    }

    /** The driver's car brand choice, or what this device looks like when they never chose. */
    fun vehicleProfile(context: Context): VehicleProfile {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROFILE, null)
        return stored?.let { runCatching { VehicleProfile.valueOf(it) }.getOrNull() } ?: VehicleProfile.detect(context)
    }

    fun setVehicleProfile(context: Context, profile: VehicleProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PROFILE, profile.name).apply()
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
            com.carlink.app.diagnostics.EventLog.add("CarLink",
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
                    brandName = app.getString(R.string.app_name), picture = picture))
            }
            Engine.ANDROID_AUTO -> {
                val values = AaSessionSettings.values(settings, plan.transport, plan.path, profile,
                    carName = app.getString(R.string.app_name), panelDensityDpi = app.resources.displayMetrics.densityDpi, wifi = wifi,
                    picture = picture)
                values.problem?.let { Log.w(TAG, "Android Auto settings: $it") }
                AaSessionSettings.apply(app, values)
            }
        }
    }

    /** Automatic picture from this device's decoders ([PicturePolicy]); probed once, on the session worker. */
    private fun automaticPicture(app: Context, settings: UnifiedSettings): PictureChoice {
        if (settings.picture != com.carlink.app.settings.Picture.AUTOMATIC) return PicturePolicy.ENGINE_DEFAULTS
        val choice = runCatching { PicturePolicy.choose(DeviceCapabilities.read(app)) }.getOrDefault(PicturePolicy.ENGINE_DEFAULTS)
        com.carlink.app.diagnostics.EventLog.add("CarLink", "Automatic picture: ${choice.summary}")
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
