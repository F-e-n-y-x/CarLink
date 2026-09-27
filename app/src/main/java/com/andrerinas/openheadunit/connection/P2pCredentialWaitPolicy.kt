package com.andrerinas.openheadunit.connection

/** A plausible cached/device MAC does not prove that the current group is ready. */
internal object P2pCredentialWaitPolicy {
    const val INTERFACE_GRACE_MILLIS = 3_000L

    fun ready(ipReady: Boolean, owner: Boolean, staticOverride: Boolean,
        fromCurrentInterface: Boolean, maskedBssid: Boolean, elapsedMillis: Long): Boolean {
        if (!ipReady) return false
        if (staticOverride || !owner) return true
        if (maskedBssid) return false
        return fromCurrentInterface || elapsedMillis >= INTERFACE_GRACE_MILLIS
    }
}
