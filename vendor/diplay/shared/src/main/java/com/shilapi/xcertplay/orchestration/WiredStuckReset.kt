package com.shilapi.xcertplay.orchestration

// CarLink: new file (see docs/VENDOR_CHANGES.md). Idea from f-io/LIVI (livi-cp manager.rs: STUCK_AFTER, reset_once).

/**
 * Whether a wired start that has no CarPlay session [STUCK_AFTER_MILLIS] after the USB data paths opened gets a
 * USB reset of the iPhone (USBDEVFS_RESET, through [CarPlayController.resetWiredUsb]) and a new start.
 *
 * A working start reaches the AirPlay session about 2 s after the data paths (tablet: 3.4 s plug to picture), and
 * the iPhone opens its AirPlay TCP right after StartSession. An iPhone that still holds an old CarPlay session NAKs
 * the NCM link instead, and DiPlay gives up only after [WiredStartWatchdog.TIMEOUT_MILLIS] (#889); a USB reset ends
 * that session as an unplug does. No reset once the iPhone opened a TCP connection (it is on its way and the
 * watchdog still covers it), nor on a start that had to pair (the iPhone may be asking its owner to trust the car
 * or to allow CarPlay). At most one reset per [ONCE_PER_MILLIS] until a session starts, so a phone a reset does not
 * help falls through to the existing watchdog and retries instead of being reset in a loop.
 */
internal object WiredStuckReset {
    const val STUCK_AFTER_MILLIS = 8_000L
    const val ONCE_PER_MILLIS = 120_000L

    private var lastResetAtNanos: Long? = null

    /** True when the caller should reset now; it then counts as the one reset. */
    @Synchronized fun shouldReset(
        nowNanos: Long,
        sessionActive: Boolean,
        iphoneOpenedTcp: Boolean,
        newPairing: Boolean,
    ): Boolean {
        val last = lastResetAtNanos
        if (sessionActive || iphoneOpenedTcp || newPairing) return false
        if (last != null && nowNanos - last < ONCE_PER_MILLIS * 1_000_000L) return false
        lastResetAtNanos = nowNanos
        return true
    }

    /** A session started: the next stuck start may be reset again. */
    @Synchronized fun sessionReached() {
        lastResetAtNanos = null
    }
}
