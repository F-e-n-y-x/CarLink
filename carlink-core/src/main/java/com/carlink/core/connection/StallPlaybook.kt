package com.carlink.core.connection

import com.carlink.core.core.Engine
import com.carlink.core.core.Transport

/**
 * How long each connection phase may take before [SessionManager] acts (pure; unit tested).
 *
 * A phase's deadline is [FACTOR] times this phone's median for that phase on that path, within
 * the phase's [Bounds]; with fewer than [MIN_SAMPLES] measurements it is the phase's default.
 * `stage == null` is the whole attempt (adapter start to the first frame).
 *
 * The defaults come from the 2026-10-09 tablet runs (docs/RELIABILITY_AUDIT_2026-10-09.md §1):
 * the slowest successful phases were a 6.7 s RFCOMM page (waiting), a 5.3 s Wi-Fi join and a
 * 14.7 s wait for the first AirPlay TCP (linking), a 10.3 s AirPlay handshake (picture) and 27.7 s
 * for a whole wireless CarPlay start. Floors keep a fast phone's deadlines above those.
 */
object AdaptiveDeadlines {
    const val FACTOR = 2.5
    const val MIN_SAMPLES = 3

    /** No phase stays silent longer than this: the driver sees why it waits (or a recovery) by then. */
    const val HINT_MILLIS = 10_000L

    data class Bounds(val default: Long, val min: Long, val max: Long)

    fun bounds(stage: ProgressStage?, transport: Transport): Bounds {
        val wireless = transport == Transport.WIRELESS
        return when (stage) {
            ProgressStage.WAITING_FOR_PHONE, ProgressStage.PHONE_NOT_ANSWERING ->
                if (wireless) Bounds(10_000, 8_000, 20_000) else Bounds(15_000, 8_000, 25_000)
            ProgressStage.HANDSHAKE -> Bounds(12_000, 6_000, 25_000)
            // Never under 15 s: StartSession → first TCP took 14.7 s once when the iPhone missed the
            // first DHCP offers, and that start then worked (VENDOR_CHANGES, IPv6-first experiment).
            ProgressStage.JOINING_WIFI -> Bounds(20_000, 15_000, 30_000)
            // The audit's "Picture without a frame" watchdog: 20 s; never under 15 s (C9 needed 10.3 s).
            ProgressStage.STARTING_PROJECTION -> Bounds(20_000, 15_000, 30_000)
            // A backstop for engines that loop between phases (each change restarts the phase
            // clocks); never earlier than the phase playbooks below can end on their own.
            null -> if (wireless) Bounds(90_000, 60_000, 120_000) else Bounds(75_000, 60_000, 120_000)
        }
    }

    fun deadline(samples: List<Long>, bounds: Bounds): Long {
        val median = median(samples) ?: return bounds.default
        if (samples.size < MIN_SAMPLES) return bounds.default
        return (median * FACTOR).toLong().coerceIn(bounds.min, bounds.max)
    }

    fun median(samples: List<Long>): Long? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}

/**
 * What to do when a phase outlives its deadline: the n-th expiry in a phase applies the n-th
 * action (pure; unit tested). Each action is in [RecoveryAction]; the list per phase is short and
 * ends in a failure with a reason, so no attempt can wait silently or loop forever.
 *
 * | Phase (stage)            | Wireless                                   | USB                          |
 * |--------------------------|--------------------------------------------|------------------------------|
 * | Waiting for the phone    | nudge, nudge, give up (did not answer)     | nudge ×3, ask for re-plug    |
 * | Handshake                | retry step, next path, give up             | Android Auto: nudge, re-switch USB, ask for re-plug; CarPlay: nudge, retry step, ask for re-plug |
 * | Linking (joining Wi-Fi)  | reset Wi-Fi Direct, next path, give up     | (none)                       |
 * | Picture (no first frame) | retry step, next path, give up             | retry step, ask for re-plug  |
 * | Whole attempt            | next path, give up                         | ask for re-plug              |
 *
 * "Next path" is skipped when there is none. The driver sees a reason from the first deadline (or
 * [AdaptiveDeadlines.HINT_MILLIS], whichever is first) on. Over USB a nudge only keeps the reason
 * on screen: the phone may be waiting for its owner (unlock, the Android Auto or CarPlay prompt),
 * so a USB phase is given more time before anything is torn down; a stale accessory is caught in
 * seconds by the Android Auto tracker itself.
 */
object StallPlaybook {
    fun actions(engine: Engine, transport: Transport, stage: ProgressStage?): List<RecoveryAction> {
        val usb = transport == Transport.USB
        return when (stage) {
            ProgressStage.WAITING_FOR_PHONE, ProgressStage.PHONE_NOT_ANSWERING ->
                if (usb) listOf(RecoveryAction.NUDGE, RecoveryAction.NUDGE, RecoveryAction.NUDGE, RecoveryAction.ASK_REPLUG)
                else listOf(RecoveryAction.NUDGE, RecoveryAction.NUDGE, RecoveryAction.GIVE_UP)
            ProgressStage.HANDSHAKE -> when {
                usb && engine == Engine.ANDROID_AUTO -> listOf(RecoveryAction.NUDGE, RecoveryAction.RESWITCH_USB, RecoveryAction.ASK_REPLUG)
                usb -> listOf(RecoveryAction.NUDGE, RecoveryAction.RETRY_STEP, RecoveryAction.ASK_REPLUG)
                else -> listOf(RecoveryAction.RETRY_STEP, RecoveryAction.NEXT_PATH, RecoveryAction.GIVE_UP)
            }
            ProgressStage.JOINING_WIFI ->
                if (usb) listOf(RecoveryAction.ASK_REPLUG)
                else listOf(RecoveryAction.RESET_WIFI_DIRECT, RecoveryAction.NEXT_PATH, RecoveryAction.GIVE_UP)
            ProgressStage.STARTING_PROJECTION ->
                if (usb) listOf(RecoveryAction.RETRY_STEP, RecoveryAction.ASK_REPLUG)
                else listOf(RecoveryAction.RETRY_STEP, RecoveryAction.NEXT_PATH, RecoveryAction.GIVE_UP)
            null -> if (usb) listOf(RecoveryAction.ASK_REPLUG) else listOf(RecoveryAction.NEXT_PATH, RecoveryAction.GIVE_UP)
        }
    }

    /**
     * The action for the [used]-th recovery in a phase (0-based), skipping [RecoveryAction.NEXT_PATH]
     * when [hasNextPath] is false. Returns the action and the new count.
     */
    fun next(actions: List<RecoveryAction>, used: Int, hasNextPath: Boolean): Pair<RecoveryAction, Int> {
        var i = used
        while (i < actions.size && actions[i] == RecoveryAction.NEXT_PATH && !hasNextPath) i++
        return (actions.getOrNull(i) ?: RecoveryAction.GIVE_UP) to (i + 1)
    }

    /** The failure a phase ends in when the playbook gives up. */
    fun failure(engine: Engine, transport: Transport, stage: ProgressStage?, waitedMillis: Long): Pair<FailureReason, String> {
        val s = waitedMillis / 1000
        val phone = if (engine == Engine.CARPLAY) "iPhone" else "phone"
        return when (stage) {
            ProgressStage.WAITING_FOR_PHONE, ProgressStage.PHONE_NOT_ANSWERING ->
                if (transport == Transport.USB) FailureReason.USB to "the $phone did not answer over USB in $s s: unlock it and accept the prompt, or unplug it and plug it in again"
                else FailureReason.PHONE_DID_NOT_ANSWER to "the $phone did not answer in $s s: unlock it and check that its Bluetooth is on"
            ProgressStage.HANDSHAKE ->
                if (transport == Transport.USB) FailureReason.USB to "the $phone stopped answering during the handshake ($s s)"
                else FailureReason.BLUETOOTH to "the $phone stopped answering during the handshake ($s s)"
            ProgressStage.JOINING_WIFI -> FailureReason.PHONE_NEVER_REACHED_CAR to "the $phone did not connect over the car's Wi-Fi in $s s"
            ProgressStage.STARTING_PROJECTION -> FailureReason.LINK_TOO_WEAK to "no picture $s s after the $phone connected"
            null -> (if (transport == Transport.USB) FailureReason.USB else FailureReason.PHONE_DID_NOT_ANSWER) to
                "no picture after $s s"
        }
    }

    /** Words for a phase in notices and logs. */
    fun phaseName(stage: ProgressStage?): String = when (stage) {
        ProgressStage.WAITING_FOR_PHONE, ProgressStage.PHONE_NOT_ANSWERING -> "Reaching the phone"
        ProgressStage.HANDSHAKE -> "The handshake"
        ProgressStage.JOINING_WIFI -> "Linking over Wi-Fi"
        ProgressStage.STARTING_PROJECTION -> "Starting the picture"
        null -> "Connecting"
    }

    /** What the driver is told while [action] runs. */
    fun actionText(action: RecoveryAction): String = when (action) {
        RecoveryAction.NUDGE -> "waking the phone again"
        RecoveryAction.RESWITCH_USB -> "restarting the phone's USB link"
        RecoveryAction.RETRY_STEP -> "trying again"
        RecoveryAction.RESET_WIFI_DIRECT -> "trying again with a fresh Wi-Fi Direct link"
        RecoveryAction.NEXT_PATH -> "trying another connection"
        RecoveryAction.ASK_REPLUG -> "unplug the phone and plug it in again"
        RecoveryAction.GIVE_UP -> "stopping"
    }
}
