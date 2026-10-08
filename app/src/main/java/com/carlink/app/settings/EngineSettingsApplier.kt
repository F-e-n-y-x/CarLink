package com.carlink.app.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import com.carlink.app.core.Engine
import com.carlink.app.engine.AndroidAutoSettings
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayNightMode
import com.shilapi.xcertplay.CarPlayNightSchedule
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * Writes [UnifiedSettings] into both engines. Called after every change (without a wireless
 * path) and right before each session (with the path chosen for that session), so the engines
 * always start from the driver's settings. Blocking IPC: call off the main thread.
 */
object EngineSettingsApplier {
    private const val TAG = "CarLink-Settings"

    /** Applies everything that does not depend on the session's wireless path. */
    fun applyAll(context: Context, settings: UnifiedSettings) {
        applyCarPlay(context, EngineMappings.carPlay(settings, path = null))
        applyAndroidAuto(context, settings, path = null)
    }

    /** Applies the settings plus the chosen wireless path for a session about to start; returns the path. */
    fun applyForSession(context: Context, engine: Engine, settings: UnifiedSettings): WirelessPath {
        val wifi = wifiEnvironment(context)
        val path = WirelessPathSelector.select(engine, settings, wifi)
        Log.i(TAG, "session engine=$engine path=$path method=${settings.connectionMethod} " +
            "wifiNetworks=${wifi.joinedNetworks} ssidReadable=${wifi.joinedSsid != null} " +
            "sameWifiSaved=${settings.homeWifi.isValid()} hotspotOn=${wifi.carHotspotOn}")
        if (engine == Engine.CARPLAY && settings.homeWifi.isValid() && wifi.joinedNetworks == 1 && wifi.joinedSsid == null) {
            // Android hides the joined network's name without location access (and with location
            // switched off), so Same Wi-Fi cannot be confirmed and the next path is used instead.
            val location = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "joined Wi-Fi name unreadable (location permission granted=$location); Same Wi-Fi not confirmed")
        }
        when (engine) {
            Engine.CARPLAY -> applyCarPlay(context, EngineMappings.carPlay(settings, path))
            Engine.ANDROID_AUTO -> applyAndroidAuto(context, settings, path, wifi)
        }
        return path
    }

    private fun applyCarPlay(context: Context, values: CarPlayValues) {
        values.wirelessMode?.let { AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.valueOf(it)) }
        values.displayScalePercent?.let { AirPlayPersistence.saveDisplayScalePercent(context, it) }
        values.fps?.let { AirPlayPersistence.saveFps(context, it) }
        values.widthPhysicalMm?.let { AirPlayPersistence.saveWidthPhysicalMm(context, it) }
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.fromKey(values.nightModeKey))
        runCatching {
            AirPlayPersistence.saveCarPlayNightSchedule(context, CarPlayNightSchedule(values.nightStartMinute, values.nightEndMinute))
        }
        AirPlayPersistence.saveRightHandDrive(context, values.rightHandDrive)
        AirPlayPersistence.saveCarBluetoothAudio(context, values.carBluetoothAudio)
        AirPlayPersistence.saveLocationReportingEnabled(context, values.locationReporting)
        BydOutputSettings.setEnabled(context, values.bydDirections)
        if (values.carHotspot.isValid()) {
            AirPlayPersistence.saveManualHotspotSsid(context, values.carHotspot.ssid)
            AirPlayPersistence.saveManualHotspotPassphrase(context, values.carHotspot.password)
            AirPlayPersistence.saveManualHotspotSecurity(context, ManualHotspotValidation.securityFor(values.carHotspot.password))
            AirPlayPersistence.saveManualHotspotBand(context, ManualHotspotBand.AUTO)
        }
        if (values.homeWifi.isValid()) {
            AirPlayPersistence.saveExistingWifiCredentials(context, values.homeWifi.ssid, values.homeWifi.password)
        }
    }

    private fun applyAndroidAuto(context: Context, settings: UnifiedSettings, path: WirelessPath?,
                                 wifi: WifiEnvironment? = null) {
        val density = context.resources.displayMetrics.densityDpi
        val bundle = Bundle()
        EngineMappings.androidAuto(settings, path, density, wifi).forEach { (key, value) ->
            when (value) {
                is Int -> bundle.putInt(key, value)
                is Boolean -> bundle.putBoolean(key, value)
                is String -> bundle.putString(key, value)
            }
        }
        AndroidAutoSettings.write(context, bundle)
    }

    /** Client Wi-Fi networks of the car (not VPN), the joined SSID when readable, and hotspot state. */
    @Suppress("DEPRECATION") // connectionInfo is the API-25-compatible way to read the SSID.
    fun wifiEnvironment(context: Context): WifiEnvironment {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val wifiNetworks = connectivity?.allNetworks.orEmpty().count { network ->
            connectivity!!.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }
        val info = runCatching { context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo }.getOrNull()
        val ssid = info?.ssid?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID && it != "<unknown ssid>" }
        // 02:00:00:00:00:00 is what Android returns when the BSSID is hidden from the app.
        val bssid = info?.bssid?.uppercase()?.takeIf { it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) && it != "02:00:00:00:00:00" }
        val wifiNetwork = connectivity?.allNetworks.orEmpty().firstOrNull { network ->
            connectivity!!.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }
        val iface = wifiNetwork?.let { runCatching { connectivity!!.getLinkProperties(it)?.interfaceName }.getOrNull() }
        return WifiEnvironment(
            joinedNetworks = wifiNetworks,
            joinedSsid = if (wifiNetworks == 1) ssid else null,
            carHotspotOn = runCatching { CarHotspotStatus.isEnabled(context) }.getOrNull(),
            joinedBssid = if (wifiNetworks == 1) bssid else null,
            joinedInterface = if (wifiNetworks == 1) iface else null,
        )
    }
}
