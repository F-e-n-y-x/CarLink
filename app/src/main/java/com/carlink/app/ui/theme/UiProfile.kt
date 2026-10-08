package com.carlink.app.ui.theme

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import java.util.Locale

/**
 * Which sizing CarLink uses. The same layouts serve both; only the tokens change.
 *
 * - [CAR]: head units and car displays. The spec's car-grade sizes (76dp targets, 24sp floor)
 *   on a canvas scaled from the screen's pixels, because head-unit DPI reports are unreliable.
 * - [HANDHELD]: phones and tablets used as the car display. Material 3 sizes on the device's
 *   own density, so CarLink looks native there.
 */
enum class UiProfile { CAR, HANDHELD }

/** The driver's choice in Settings > Display > Layout. */
enum class UiProfileMode { AUTO, CAR, HANDHELD }

object UiProfileDetector {
    /**
     * A car display when Android says so (car UI mode, Automotive), when the hardware is BYD's,
     * or when the device has neither a battery nor a phone radio: head units are powered by the
     * car, while phones and tablets (Wi-Fi tablets included) have a battery.
     */
    fun detect(context: Context, mode: UiProfileMode): UiProfile = when (mode) {
        UiProfileMode.CAR -> UiProfile.CAR
        UiProfileMode.HANDHELD -> UiProfile.HANDHELD
        UiProfileMode.AUTO -> if (looksLikeCarDisplay(context)) UiProfile.CAR else UiProfile.HANDHELD
    }

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
