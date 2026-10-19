// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0), connection/wifi/modes/nativeaa/blink/StockCarLink.kt
// (see docs/VENDOR_CHANGES.md).
package com.andrerinas.openheadunit.connection.blink

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Whether the stock `com.syu.carlink`, which reads the same `/dev/auto_serial`, is out of the way,
 * as far as an ordinary app can tell.
 *
 * `pm disable-user` force-stops a package and keeps it from starting again, so a disabled Car Link
 * is not reading the port, unless it is a persistent system app, which a force-stop does not stop.
 * An app cannot see another app's processes, so anything short of "disabled and not persistent"
 * goes to the root bridge, whose guard checks the process itself.
 *
 * On Android 11 and later the package is only visible through the manifest's `<queries>` entry.
 */
object StockCarLink {
    const val PACKAGE = "com.syu.carlink"

    enum class State {
        /** Installed and enabled: it may be reading the port. */
        ENABLED,

        /** Disabled and not persistent, so it cannot be running. */
        DISABLED,

        /** Disabled, but persistent, so it may still be running until the unit restarts. */
        DISABLED_PERSISTENT,

        /** Not installed, or not visible. The root guard refuses this too, so it is not trusted. */
        ABSENT,

        /** The package manager could not be asked. */
        UNKNOWN;

        /** Whether this app may open the port itself without the root guard. */
        val allowsDirectOpen: Boolean get() = this == DISABLED
    }

    /** What the package manager says about the package. */
    internal data class Info(val enabled: Boolean, val persistent: Boolean)

    @Suppress("DEPRECATION")
    fun state(packageManager: PackageManager): State = state {
        try {
            // GET_DISABLED_COMPONENTS so a disabled package is still found rather than read as absent.
            val info = packageManager.getApplicationInfo(PACKAGE, PackageManager.GET_DISABLED_COMPONENTS)
            val enabled = when (packageManager.getApplicationEnabledSetting(PACKAGE)) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> false
                // Re-enables itself on first use, so it counts as enabled.
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                else -> info.enabled
            }
            Info(enabled, (info.flags and ApplicationInfo.FLAG_PERSISTENT) != 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** @param read the package's state, or null if it is not installed */
    internal fun state(read: () -> Info?): State = try {
        val info = read()
        when {
            info == null -> State.ABSENT
            info.enabled -> State.ENABLED
            info.persistent -> State.DISABLED_PERSISTENT
            else -> State.DISABLED
        }
    } catch (_: Exception) {
        State.UNKNOWN
    }
}
