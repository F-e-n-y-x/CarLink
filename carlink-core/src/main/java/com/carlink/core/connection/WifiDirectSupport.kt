package com.carlink.core.connection

import android.content.Context
import android.content.pm.PackageManager

/**
 * Whether this screen can run Wi-Fi Direct. Some stereos (Allwinner A133, #11) list Wi-Fi but
 * answer every `createGroup` with P2P_UNSUPPORTED; Automatic then skips Wi-Fi Direct
 * (WirelessPolicy) instead of retrying it forever. Cleared when Wi-Fi Direct connects.
 */
object WifiDirectSupport {
    /** The text DiPlay's failure carries for WifiP2pManager.P2P_UNSUPPORTED. */
    const val UNSUPPORTED_TEXT = "Wi-Fi P2P is unsupported"
    private const val KEY = "wifi_direct_unsupported"

    private fun prefs(context: Context) = context.getSharedPreferences("carlink_wifi_direct", Context.MODE_PRIVATE)

    fun supported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) && !prefs(context).getBoolean(KEY, false)

    /** Records an engine failure that says this screen has no Wi-Fi Direct; true when it did. */
    fun noteFailure(context: Context, detail: String?): Boolean {
        if (detail?.contains(UNSUPPORTED_TEXT, ignoreCase = true) != true) return false
        if (!prefs(context).getBoolean(KEY, false)) {
            prefs(context).edit().putBoolean(KEY, true).apply()
            com.carlink.core.diagnostics.EventLog.add("CarLink", "this screen has no Wi-Fi Direct; Automatic skips it from now on")
        }
        return true
    }

    fun connected(context: Context) = prefs(context).edit().remove(KEY).apply()
}
