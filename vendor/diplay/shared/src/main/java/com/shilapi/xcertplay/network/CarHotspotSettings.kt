package com.shilapi.xcertplay.network

import android.content.Context
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

object CarHotspotSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay_car_hotspot", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("auto_enable", false)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean("auto_enable", enabled).apply()

    // Visibility is independent of the saved choice: ADB is only needed to grant permission.
    fun visible(bydAvailable: Boolean, access: LocalAdb.Access): Boolean =
        bydAvailable && (access == LocalAdb.Access.READY || access == LocalAdb.Access.NOT_APPROVED)

    fun shouldEnable(context: Context, wireless: Boolean, mode: WirelessHotspotMode): Boolean =
        enabled(context) && wireless && mode == WirelessHotspotMode.MANUAL

    /**
     * Set when the driver connects although Android reports the car hotspot off. Some firmware reports it off
     * while the car's own hotspot is on (Geely Galaxy E8, #604), and DiPlay then refused every connection.
     */
    fun offReportIgnored(context: Context): Boolean = prefs(context).getBoolean("ignore_off_report", false)

    fun setOffReportIgnored(context: Context, ignored: Boolean) =
        prefs(context).edit().putBoolean("ignore_off_report", ignored).apply()

    /**
     * Whether to treat the car hotspot as off, from Android's [state]. A report of "on" clears the driver's
     * choice, so a head unit whose report is right warns again the next time its hotspot really is off.
     */
    fun reportedOff(context: Context, state: Boolean?): Boolean {
        if (state == true) {
            if (offReportIgnored(context)) setOffReportIgnored(context, false)
            return false
        }
        return state == false && !offReportIgnored(context)
    }
}
