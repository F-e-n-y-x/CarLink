package com.carlink.app.diagnostics

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * System settings screens a check's Fix opens. Head-unit firmware often strips Settings pages,
 * so each one falls back to CarLink's app details page, which every Android build has.
 */
object SystemScreens {
    fun openOverlaySettings(context: Context) = open(context,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))

    fun openBatterySettings(context: Context) = open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    fun openAppDetails(context: Context) = open(context, appDetails(context))

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    private fun open(context: Context, intent: Intent) {
        val launched = try {
            context.startActivity(intent.withTaskFlag(context))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
        if (!launched && intent.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
            runCatching { context.startActivity(appDetails(context).withTaskFlag(context)) }
        }
    }

    private fun Intent.withTaskFlag(context: Context): Intent =
        if (context is android.app.Activity) this else addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
