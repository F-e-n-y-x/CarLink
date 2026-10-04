package com.shilapi.xcertplay.network

import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WifiManager

/**
 * Reads whether the head unit's own Wi-Fi hotspot is on, for the "Car hotspot" link.
 *
 * The user can turn it on in car settings or opt into [CarHotspotTethering] after granting permission.
 */
object CarHotspotStatus {
    private const val WIFI_AP_STATE_ENABLED = 13
    private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val EXTRA_WIFI_AP_STATE = "wifi_state"

    /**
     * True/false from the Wi-Fi AP state, or null when the firmware hides it (then callers must
     * not block the connection). Interface flags are not used: BYD keeps wlan1 up with an address
     * while tethering is off.
     */
    fun isEnabled(context: Context): Boolean? {
        val app = context.applicationContext
        val wifi = app.getSystemService(WifiManager::class.java) ?: return null
        return runCatching {
            WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as Int == WIFI_AP_STATE_ENABLED
        }.recoverCatching {
            WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wifi) as Boolean
        }.recoverCatching {
            val sticky = app.registerReceiver(null, IntentFilter(ACTION_WIFI_AP_STATE_CHANGED))
            val state = sticky?.getIntExtra(EXTRA_WIFI_AP_STATE, -1) ?: -1
            if (state in 10..14) state == WIFI_AP_STATE_ENABLED else null
        }.getOrNull()
    }
}
