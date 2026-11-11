package com.carlink.core.detect

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorManager
import android.provider.Settings
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
    fun looksLikeCarDisplay(context: Context, trustCarMode: Boolean = true): Boolean {
        ScreenType.forcedHandheld?.let { return !it } // the driver's Screen type
        val uiModeType = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (trustCarMode && uiModeType == Configuration.UI_MODE_TYPE_CAR) return true
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return true
        val vendor = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT)
        if ("byd" in vendor) return true
        return !pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) && !hasBattery(context)
    }

    /**
     * A phone or tablet whose screen turns: an accelerometer, auto-rotate on, a phone radio or a battery,
     * and not Automotive. Read at each session start: auto-rotate can change. Unlike
     * [looksLikeCarDisplay] it ignores the car UI mode, which a phone enters for a while (seen
     * 2026-11-10 on a Galaxy S25 while it was plugged in) and leaves again.
     */
    fun handheld(context: Context): Boolean {
        ScreenType.forcedHandheld?.let { return it } // the driver's Screen type
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return false
        // Head units with a 4G modem or a reported battery passed the old test and lost their safe
        // area to the turning canvas (#65, #33). A screen that turns has a real accelerometer (the
        // feature XML is often copied from a tablet build) and auto-rotate on (car firmware pins it).
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null) return false
        if (Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) != 1) return false
        return pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) || hasBattery(context)
    }

    private fun hasBattery(context: Context): Boolean {
        val status = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return true
        return status.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
    }
}
