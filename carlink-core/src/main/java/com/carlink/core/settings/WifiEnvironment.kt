package com.carlink.core.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.andrerinas.openheadunit.utils.HotspotConfigReader
import com.carlink.core.core.Engine
import com.shilapi.xcertplay.network.CarHotspotStatus

/** The wireless network a session will use, chosen by [com.carlink.core.connection.WirelessPolicy]. */
enum class WirelessPath { SAME_WIFI, CAR_HOTSPOT, WIFI_DIRECT }

/** What CarLink knows about the car's Wi-Fi right before a wireless session. */
data class WifiEnvironment(
    /** Wi-Fi networks the car is joined to as a client (not counting its own hotspot). */
    val joinedNetworks: Int,
    /** SSID of that network when exactly one and readable (needs location permission). */
    val joinedSsid: String?,
    /** Whether the car's own hotspot is on; null when the firmware does not say. */
    val carHotspotOn: Boolean?,
    /** The router's BSSID for [joinedSsid] (needs location permission), or null. */
    val joinedBssid: String? = null,
    /** The car's Wi-Fi client interface (usually wlan0), or null. */
    val joinedInterface: String? = null,
    /** Whether [joinedSsid] needs no password (open or OWE); null when Android does not say. */
    val joinedOpen: Boolean? = null,
    /** An Android Auto phone already refused [joinedSsid] with the saved password (see WirelessRefusals). */
    val androidAutoRefusedJoined: Boolean = false,
    /** An iPhone already failed to reach the car over [joinedSsid] with the saved password. */
    val carPlayRefusedJoined: Boolean = false,
    /** The car's own hotspot name and password, read from the system where the firmware allows it. */
    val systemHotspot: WifiDetails? = null,
)

/** SSID ≤32 UTF-8 bytes, password empty (open network) or 8–63 characters: what both engines accept. */
fun WifiDetails.isValid(): Boolean {
    val ssidBytes = ssid.toByteArray(Charsets.UTF_8).size
    return ssidBytes in 1..32 && (password.isEmpty() || password.length in 8..63)
}

/** Reads the car's current Wi-Fi state for [com.carlink.core.connection.WirelessPolicy] and pre-flight. */
object WifiEnvironmentReader {
    /**
     * Client Wi-Fi networks of the car (not VPN), the joined SSID when readable, and hotspot state.
     * [settings] is only needed to look up whether a phone already refused the saved network.
     */
    @Suppress("DEPRECATION") // connectionInfo is the API-25-compatible way to read the SSID.
    fun read(context: Context, settings: UnifiedSettings? = null): WifiEnvironment {
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
        val open = if (Build.VERSION.SDK_INT >= 31 && info != null) {
            when (info.currentSecurityType) {
                WifiInfo.SECURITY_TYPE_OPEN, WifiInfo.SECURITY_TYPE_OWE -> true
                WifiInfo.SECURITY_TYPE_UNKNOWN -> null
                else -> false
            }
        } else null
        val hotspotOn = runCatching { CarHotspotStatus.isEnabled(context) }.getOrNull()
        val joined = if (wifiNetworks == 1) ssid else null
        return WifiEnvironment(
            joinedNetworks = wifiNetworks,
            joinedSsid = joined,
            carHotspotOn = hotspotOn,
            joinedBssid = if (wifiNetworks == 1) bssid else null,
            joinedInterface = if (wifiNetworks == 1) iface else null,
            joinedOpen = if (wifiNetworks == 1) open else null,
            androidAutoRefusedJoined = joined != null && settings != null &&
                WirelessRefusals(context).isRefused(Engine.ANDROID_AUTO, joined, settings.homeWifi.password),
            carPlayRefusedJoined = joined != null && settings != null &&
                WirelessRefusals(context).isRefused(Engine.CARPLAY, joined, settings.homeWifi.password),
            systemHotspot = if (hotspotOn == true) systemHotspot(context) else null,
        )
    }

    /** The hotspot's own name and password; most phones and tablets refuse this, many head units allow it. */
    private fun systemHotspot(context: Context): WifiDetails? =
        runCatching { HotspotConfigReader.getSystemHotspotConfig(context) }.getOrNull()
            ?.let { (ssid, password) -> WifiDetails(ssid, password) }
            ?.takeIf { it.isValid() && it.password.isNotEmpty() }
}
