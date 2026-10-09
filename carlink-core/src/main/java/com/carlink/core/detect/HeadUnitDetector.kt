package com.carlink.core.detect

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import java.util.Locale

/** Whether this device is a car display (head unit) rather than a phone or tablet. */
object HeadUnitDetector {
    /**
     * A car display when Android says so (car UI mode, Automotive), when the hardware is BYD's,
     * or when the device has neither a battery nor a phone radio: head units are powered by the
     * car, while phones and tablets (Wi-Fi tablets included) have a battery. Decides the wireless
     * path order (`WirelessPolicy`) and CarLink's car-sized layout.
     */
    fun looksLikeCarDisplay(context: Context): Boolean {
        val uiModeType = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (uiModeType == Configuration.UI_MODE_TYPE_CAR) return true
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return true
        val vendor = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT)
        if ("byd" in vendor) return true
        return !pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) && !hasBattery(context)
    }

    private fun hasBattery(context: Context): Boolean {
        val status = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return true
        return status.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
    }
}
