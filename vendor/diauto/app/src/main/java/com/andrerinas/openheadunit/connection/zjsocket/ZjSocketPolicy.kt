// CarLink: ported from open-headunit 754e6c01 (PR #1168, AGPL-3.0), connection/wifi/modes/nativeaa/
// zjsocket/ZjSocketPolicy.kt (see docs/VENDOR_CHANGES.md). The connect cache and the ZBT-window
// hooks (Verdict, cachedAnswer, keptAtArming, probe, probesDuringZbtWait, endsZbtWait) belong to
// upstream's daemon measurement, which CarLink left out: its own probe (detect/ExternalBluetoothProbe)
// asks once. Without JoinRefusalPolicy here, a served attempt waits BASE_RETRY_MS.
package com.andrerinas.openheadunit.connection.zjsocket

/**
 * What a connect to the ZJ daemon's Unix socket proves, and how the carrier paces its dials.
 *
 * A file that exists proves nothing about a daemon that accepts, so only a connect counts.
 */
object ZjSocketPolicy {

    /** Where a ZJ daemon carries the phone's raw RFCOMM stream (Unisoc UIS7862 units without 3152). */
    const val SOCKET_PATH = "/dev/socket/zj_bt_socket"

    enum class Outcome { CONNECTS, REFUSED, DENIED, TIMED_OUT, MISSING }

    /** A connect that never returns is not an answer; the connector closes the socket after this. */
    const val CONNECT_DEADLINE_MS = 2_000L

    /** Gap between dials when nothing is wrong; refused connects double from it. */
    const val BASE_RETRY_MS = 3_000L

    /** Ceiling of the gap after refused connects. */
    const val CONNECT_BACKOFF_CAP_MS = 30_000L

    enum class Action { DIAL, WAIT, STOP }

    fun connects(outcome: Outcome): Boolean = outcome == Outcome.CONNECTS

    /** Android words a denied connect as "Permission denied" and any other failure as a refusal. */
    fun failureOutcome(message: String?): Outcome =
        if (message?.contains("Permission denied", ignoreCase = true) == true) Outcome.DENIED
        else Outcome.REFUSED

    /**
     * The carrier's next step. STOP comes only from the manager stopping: a session that closes
     * the Android listeners must not end the carrier, or the next drive finds nobody dialling.
     */
    fun decide(
        running: Boolean,
        sessionUp: Boolean,
        handshakeInFlight: Boolean,
        settling: Boolean,
        mayServe: Boolean
    ): Action = when {
        !running -> Action.STOP
        sessionUp || handshakeInFlight || settling || !mayServe -> Action.WAIT
        else -> Action.DIAL
    }

    /** A cancelled handshake ends the carrier only when the carrier itself was stopped or cancelled. */
    fun cancellationEndsCarrier(stopped: Boolean, carrierActive: Boolean): Boolean =
        stopped || !carrierActive

    /** The gap before the next dial; [count] is the refused connects in a row. */
    fun retryDelayMs(afterServed: Boolean, count: Int): Long =
        if (afterServed) BASE_RETRY_MS
        else minOf(BASE_RETRY_MS shl (count - 1).coerceIn(0, 6), CONNECT_BACKOFF_CAP_MS)
}
