// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0), connection/wifi/modes/nativeaa/blink/BlinkRefusal.kt
// (see docs/VENDOR_CHANGES.md).
package com.andrerinas.openheadunit.connection.blink

/**
 * Why the module carrier is not listening on `/dev/auto_serial`, in terms the user can act on.
 *
 * The log alone is not enough: while the carrier is refused the connection pill would otherwise
 * keep saying the phone is being woken, and nothing on screen would name the cause.
 */
enum class BlinkRefusal(val message: String) { // CarLink: English text, not upstream's string resources
    /** The stock Car Link app is installed and enabled, and reads the same port. */
    STOCK_CLIENT_ENABLED("the stereo's own Car Link app (com.syu.carlink) is enabled and reads the same port; disable it first"),

    /** Car Link is disabled but still has a running process. */
    STOCK_CLIENT_RUNNING("the stereo's own Car Link app (com.syu.carlink) is disabled but still running; restart the stereo"),

    /** The root shell ran but could not read Car Link's state, so the port stays closed. */
    OWNERSHIP_UNVERIFIED("could not check whether the stereo's own Car Link app is stopped"),

    /** `su` was denied or is missing: the bridge never ran. */
    ROOT_DENIED("/dev/auto_serial cannot be opened by an app and root is not available"),

    /** The root bridge ended before it opened the port. The log has the bridge's own reason. */
    BRIDGE_FAILED("the bridge to /dev/auto_serial ended before it opened the port");
}

/**
 * How long to wait before asking again while refused.
 *
 * Each pass runs `su`, which under Magisk's defaults is a "Superuser granted" toast on the car's
 * screen. A refusal rarely clears by itself, so the waits grow and then hold.
 */
object BlinkRefusalBackoff {
    const val FIRST_MS = 5_000L
    const val SECOND_MS = 30_000L
    const val CAP_MS = 60_000L

    /** @param consecutive refusals in a row before this one, 0 for the first */
    fun delayMs(consecutive: Int): Long = when {
        consecutive <= 0 -> FIRST_MS
        consecutive == 1 -> SECOND_MS
        else -> CAP_MS
    }
}
