// CarLink: new file (DiAuto issue #77, see docs/VENDOR_CHANGES.md).
package com.andrerinas.openheadunit.connection

/**
 * When to reopen a Native AA RFCOMM listener whose server socket failed while the manager runs
 * (the phone unpaired from the head unit, or the Bluetooth stack restarted). Upstream only logged
 * the error and left `isActive()` true, so no re-arm path ran and nothing listened until a
 * force-stop (DiAuto issue #77).
 *
 * Backoff 1, 2, 4, 8, 16 s; after [MAX_REOPENS] failures in a row the listener stays closed and is
 * reported as such, so the existing re-arm paths (poke, Bluetooth auto-start) reopen it. A listener
 * that stayed up for [STABLE_MILLIS] starts a fresh count.
 */
object ListenerReopenPolicy {
    const val MAX_REOPENS = 5
    const val STABLE_MILLIS = 30_000L

    /** The failures in a row after one more, given the previous count and how long the listener stayed up. */
    fun failuresInRow(previous: Int, livedMillis: Long): Int = if (livedMillis >= STABLE_MILLIS) 1 else previous + 1

    /** How long to wait before reopening after the [failures]-th failure in a row; null to give up. */
    fun delayMillis(failures: Int): Long? = if (failures in 1..MAX_REOPENS) 1_000L shl (failures - 1) else null
}
