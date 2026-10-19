package com.carlink.core.connection

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.location.LocationManagerCompat
import com.carlink.core.core.Transport
import com.carlink.core.settings.UnifiedSettings
import com.carlink.core.settings.WifiEnvironment
import com.carlink.core.settings.WirelessPath
import com.carlink.core.settings.isValid

/**
 * The checks both engines need before a session, done by CarLink so the driver gets a one-tap fix
 * on Home instead of an engine failing later or a system dialog over the projection
 * (DP F3/F16, DA§10.8, DA§11.2). Engine-specific checks (CarPlay authentication, VPN consent)
 * live with each engine. Reads only; never opens anything.
 */
object DevicePreflight {
    /** What the checks read, so they can be tested without a device. */
    data class Facts(
        val bluetoothOn: Boolean,
        /** Null when unknown (no permission to read bonds). */
        val phoneBonded: Boolean?,
        val wifiOn: Boolean,
        val locationOn: Boolean,
        val missingPermissions: List<String>,
        val canDrawOverlays: Boolean,
    )

    fun check(request: SessionRequest, path: WirelessPath?, facts: Facts, settings: UnifiedSettings, wifi: WifiEnvironment): List<Problem> =
        buildList {
            if (facts.missingPermissions.isNotEmpty()) add(Problem.PERMISSIONS)
            // No overlay check: a session always starts with CarLink in front (Connect, Home, the
            // USB router), and without the permission only the floating switcher degrades to its
            // in-app prompt. Blocking here stopped phones whose OEM hides the setting (issue #2).
            if (request.transport != Transport.WIRELESS) return@buildList
            if (!facts.bluetoothOn) add(Problem.BLUETOOTH_OFF)
            else if (request.phoneAddress != null && facts.phoneBonded == false) add(Problem.PHONE_NOT_PAIRED)
            // Wi-Fi Direct and every hotspot need the radio; DiAuto aborts its P2P path silently
            // when it is off (DA§10.8).
            if (!facts.wifiOn) add(Problem.WIFI_OFF)
            // Android hides SSIDs/BSSIDs and P2P details with the Location switch off (DA§10.8).
            if (!facts.locationOn) add(Problem.LOCATION_OFF)
            when (path) {
                WirelessPath.SAME_WIFI -> {
                    val saved = settings.homeWifi
                    val passwordKnown = saved.password.isNotEmpty() || wifi.joinedOpen == true
                    if (!saved.isValid() || !passwordKnown || wifi.joinedSsid != saved.ssid) add(Problem.SAME_WIFI_DETAILS)
                }
                WirelessPath.CAR_HOTSPOT -> when {
                    wifi.carHotspotOn == false -> add(Problem.CAR_HOTSPOT_OFF)
                    !WirelessPolicy.hotspotUsable(settings, wifi) -> add(Problem.CAR_HOTSPOT_DETAILS)
                }
                WirelessPath.WIFI_DIRECT, null -> Unit
            }
        }

    @SuppressLint("MissingPermission") // bondedDevices is only read with BLUETOOTH_CONNECT granted.
    fun facts(context: Context, phoneAddress: String?): Facts {
        val app = context.applicationContext
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        val canReadBonds = Build.VERSION.SDK_INT < 31 ||
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val bonded = if (phoneAddress == null || adapter == null || !canReadBonds) null else runCatching {
            adapter.bondedDevices.any { it.address.equals(phoneAddress, ignoreCase = true) }
        }.getOrNull()
        val location = app.getSystemService(LocationManager::class.java)
        return Facts(
            bluetoothOn = adapter?.isEnabled == true,
            phoneBonded = bonded,
            wifiOn = app.getSystemService(WifiManager::class.java)?.isWifiEnabled == true,
            locationOn = location != null && LocationManagerCompat.isLocationEnabled(location),
            missingPermissions = requiredPermissions().filter { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED },
            canDrawOverlays = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(app),
        )
    }

    /** Runtime permissions a session needs; the onboarding flow asks for all of them at once. */
    fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.RECORD_AUDIO)
    }
}
