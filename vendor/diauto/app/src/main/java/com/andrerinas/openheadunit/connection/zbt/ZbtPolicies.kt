// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0), connection/wifi/modes/nativeaa/zbt/
// ZbtAttemptPolicy.kt, ZbtWakePolicy.kt, ZbtReopenPolicy.kt and ZbtRetransmitPolicy.kt, in one file
// (see docs/VENDOR_CHANGES.md).
package com.andrerinas.openheadunit.connection.zbt

import com.andrerinas.openheadunit.aap.NativeHandoffPolicy

/**
 * When to try the Android Auto handshake over the external Bluetooth module.
 *
 * There is no "the phone connected" event on this route. Our own RFCOMM listener is what normally
 * says a phone has arrived, and here there is none: the daemon owns the Android Auto RFCOMM server
 * on the module, accepts the phone itself, and tells us nothing about it directly. The head unit is
 * also the side that speaks first in this handshake, so waiting for a byte from the phone would
 * wait forever.
 *
 * The vendor's own client solves this the same way, and that is where these rules come from. Its
 * log shows it gating on one thing — a link-state message naming a connected phone the module has
 * classified as Android Auto — and then sending `WifiVersionRequest` optimistically and retrying,
 * with no connection event of any kind in between. So: treat that message as the arrival signal,
 * attempt, and let the attempt's own timeouts recycle it.
 *
 * `PhoneLinkState` (`0x11f`) is deliberately **not** used, even though it looks like the ideal
 * signal. In the vendor's code the call that emits it is gated on a link-mode mask that
 * Android-Auto-only units do not carry, so on exactly the hardware this serves it is never sent.
 */
object ZbtAttemptPolicy {

    /**
     * The module's view of the phone, as one link-state message describes it.
     *
     * @param connected the module reports a Bluetooth connection to the phone
     * @param androidAuto the module has classified that phone as an Android Auto device
     */
    data class Presence(
        val connected: Boolean,
        val androidAuto: Boolean,
        val phoneName: String? = null,
        val phoneMac: String? = null
    ) {
        /** Worth attempting a handshake against. */
        val usable: Boolean get() = connected && androidAuto
    }

    /** The value of `phone_type` the module uses for a phone it will do Android Auto with. */
    const val PHONE_TYPE_ANDROID_AUTO = 1

    /**
     * What [msgId] says about the phone, or null if it says nothing.
     *
     * Field numbers differ between the two link-state messages and are the kind of detail that is
     * wrong silently, so they are read here and pinned by tests against real captured bodies rather
     * than open-coded at the call site.
     */
    fun readPresence(msgId: Int, fields: ZbtMessages.Record?): Presence? {
        if (fields == null) return null
        return when (msgId) {
            // { 1 id, 2 phone_type, 3 is_connect, 4 is_pair, 5 local_mac_address }
            ZbtMessages.LINK_INFO -> Presence(
                connected = fields.int(3) == 1,
                androidAuto = fields.int(2) == PHONE_TYPE_ANDROID_AUTO
            )
            // { …, 2 phone_type, 3 is_connect, …, 7 phone_bt_mac_addr, 8 phone_bt_name }
            ZbtMessages.LINK_INFO2 -> Presence(
                connected = fields.int(3) == 1,
                androidAuto = fields.int(2) == PHONE_TYPE_ANDROID_AUTO,
                phoneName = fields.string(8)?.takeIf { it.isNotEmpty() },
                phoneMac = fields.string(7)?.takeIf { it.isNotEmpty() }
            )
            else -> null
        }
    }

    /**
     * Whether [current] is the phone *arriving*, rather than a repeat of what we already knew.
     *
     * The module re-sends link state on a timer — roughly every twelve seconds on the unit this was
     * measured on — so acting on every message would start an attempt every twelve seconds forever.
     * Only the edge is interesting.
     */
    fun isArrival(previous: Presence?, current: Presence): Boolean =
        current.usable && previous?.usable != true

    /**
     * Whether an attempt may start now.
     *
     * @param phonePresent the module currently reports a usable phone
     * @param attemptInFlight one is already running
     * @param sessionConnected a projection session is already up, so there is nothing to start
     * @param sinceLastAttemptMs how long ago the last attempt ended, or null if none has
     * @param minIntervalMs the gap to leave between attempts, which a caller widens when the phone
     *   keeps refusing the network rather than failing to answer
     * @param channelOpenForMs how long this channel has been open, or null when the caller does not
     *   track it
     * @param everSawPresence any usable presence has been reported on this channel
     */
    fun shouldAttempt(
        phonePresent: Boolean,
        attemptInFlight: Boolean,
        sessionConnected: Boolean,
        sinceLastAttemptMs: Long?,
        minIntervalMs: Long = MIN_ATTEMPT_INTERVAL_MS,
        channelOpenForMs: Long? = null,
        everSawPresence: Boolean = false
    ): Boolean {
        if (attemptInFlight || sessionConnected) return false
        if (!phonePresent && !mayAttemptBlind(channelOpenForMs, everSawPresence)) return false
        // A failed attempt costs the phone nothing, but back-to-back attempts would spend the whole
        // window in setup rather than waiting for a phone that is simply not ready yet.
        return sinceLastAttemptMs == null || sinceLastAttemptMs >= minIntervalMs
    }

    /**
     * Whether to speak without the module ever having said a phone is there.
     *
     * A module that never reports presence, or reports it with a `phone_type` this does not
     * recognise, would otherwise be pumped forever in silence. The head unit speaks first here, so
     * an attempt against an empty car costs a few seconds of quiet and nothing else.
     *
     * [everSawPresence] means a *usable* presence: a phone the module reported but did not classify
     * as Android Auto leaves it false, and is spoken to.
     */
    fun mayAttemptBlind(channelOpenForMs: Long?, everSawPresence: Boolean): Boolean =
        !everSawPresence && channelOpenForMs != null && channelOpenForMs >= BLIND_ATTEMPT_AFTER_MS

    /**
     * How long a channel must be open in silence before we speak anyway.
     *
     * Upstream's ZbtProbe budgets eight seconds for the daemon's opening state burst, so by
     * ten the burst has been and gone and nothing in it named a phone.
     */
    const val BLIND_ATTEMPT_AFTER_MS = 10_000L

    /**
     * How long to leave between attempts.
     *
     * The vendor retries its opening message three times about 2.1 s apart and then gives up on the
     * session entirely. Ours is a whole handshake rather than one message, and it fails only after
     * its own stage timeouts have run, so the gap between attempts is longer and the cost of an
     * attempt against an absent phone is a few seconds of quiet rather than a wasted session.
     */
    const val MIN_ATTEMPT_INTERVAL_MS = 5_000L
}


/**
 * When to ask the external Bluetooth module to bring the phone's link up.
 *
 * The wake poke on the ordinary route dials the phone's hands-free profile over `android.bluetooth`
 * to make Android Auto notice us. On a unit whose Bluetooth is an external module that does
 * nothing at all — it goes out over the radio the phone is not paired to. The module-side
 * equivalent is `RequestReconn` (`0x114`), which the daemon does register a handler for.
 *
 * It is a heavier thing to send than a poke, so it is paced more conservatively. It asks the module
 * to change state while the vendor's own client is also connected to the same daemon, and its
 * effect on that client has been observed exactly once.
 */
object ZbtWakePolicy {

    /**
     * Shortest gap between two `RequestReconn` messages.
     *
     * Matched to the poke cadence it replaces rather than derived from anything the module says.
     * It also has to stay clear of the module's own link-state heartbeat, about twelve seconds on
     * the unit this was measured on, so that a wake and a heartbeat are still distinguishable in a
     * log read after the fact.
     */
    const val MIN_INTERVAL_MS = 15_000L

    /**
     * Whether to send one now.
     *
     * The three state flags are the ordinary poke's, so this asks
     * [NativeHandoffPolicy.shouldPoke] rather than restating them. What is this policy's own is the
     * minimum interval: the module has no accept event to pace against.
     *
     * @param lastSentAtMs when the last one went out, or 0 if none has
     * @param settling a handoff is still settling
     * @param handshakeInFlight an exchange is running
     * @param sessionConnected a projection session is already up
     */
    fun shouldSend(
        lastSentAtMs: Long,
        nowMs: Long,
        settling: Boolean,
        handshakeInFlight: Boolean,
        sessionConnected: Boolean
    ): Boolean {
        if (!NativeHandoffPolicy.shouldPoke(settling, handshakeInFlight, sessionConnected)) return false
        if (lastSentAtMs == 0L) return true
        val elapsed = nowMs - lastSentAtMs
        // A negative elapsed means the clock moved backwards under us. Treat it as "too soon"
        // rather than "long enough ago": waiting one more interval is harmless, and the alternative
        // is a burst of state-changing messages at a moment we cannot reason about.
        return elapsed >= MIN_INTERVAL_MS
    }

    /** How long a wake asked for before it could go out is still worth sending. */
    const val PENDING_MAX_MS = 60_000L

    enum class Pending { SEND, HOLD, SESSION_UP, EXPIRED }

    /**
     * What to do with a wake that was asked for and not yet sent. A cold bring-up asks before the
     * module channel exists, so the request waits for the carrier's own thread to send it.
     */
    fun pending(
        requestedAtMs: Long,
        lastSentAtMs: Long,
        nowMs: Long,
        settling: Boolean,
        handshakeInFlight: Boolean,
        sessionConnected: Boolean
    ): Pending = when {
        sessionConnected -> Pending.SESSION_UP
        nowMs < requestedAtMs || nowMs - requestedAtMs >= PENDING_MAX_MS -> Pending.EXPIRED
        shouldSend(lastSentAtMs, nowMs, settling, handshakeInFlight, sessionConnected) -> Pending.SEND
        else -> Pending.HOLD
    }
}

/** How soon [ZbtAaCarrier] dials a daemon again after it refused, and when to say it is gone. */
object ZbtReopenPolicy {

    /** The longest wait between dials. */
    const val CEILING_MS = 30_000L

    private val DELAYS_MS = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L)

    /** A daemon back within a second should not cost half a minute. [consecutiveRefusals] counts from 1. */
    fun delayAfterRefusalMs(consecutiveRefusals: Int): Long =
        DELAYS_MS.getOrElse(maxOf(consecutiveRefusals, 1) - 1) { CEILING_MS }

    /** Whether the daemon has stayed away long enough to tell the user the route is dead. */
    fun warnsNoDaemon(sinceFirstRefusalMs: Long): Boolean =
        sinceFirstRefusalMs >= REFUSAL_WINDOW_MS

    /** CarLink: upstream's ZbtReachabilityPolicy.REFUSAL_WINDOW_MS (that file is not ported). */
    const val REFUSAL_WINDOW_MS = 30_000L
}

/**
 * Whether to repeat the handshake's opening message while the phone has said nothing.
 *
 * On this unit's own radio an accept proves the phone is there, so a message that goes out is a
 * message that arrived. On the module there is no accept: the daemon owns the Android Auto server,
 * and our first message can go out before the phone's channel exists on the far side, where it is
 * dropped with nobody told. Repeating is the only cover for that, and it is why the vendor's own
 * client repeats too.
 *
 * One answer from the phone retires it: from then on the exchange has its own stage deadlines.
 */
object ZbtRetransmitPolicy {

    /**
     * Gap between repeats. The vendor's client retries about 2.1 s apart, and this stays well
     * inside the phone's own wait for a first message.
     */
    const val INTERVAL_MS = 2_500L

    /**
     * @param enabled the transport has no accept to prove the phone is there
     * @param phoneHasAnswered anything at all has come back
     * @param lastSentAtMs when the opening message last went out, or 0 if it has not yet
     * @param nowMs the current elapsed-time reading
     */
    fun shouldResend(
        enabled: Boolean,
        phoneHasAnswered: Boolean,
        lastSentAtMs: Long,
        nowMs: Long
    ): Boolean {
        if (!enabled || phoneHasAnswered) return false
        // The first copy is the handshake's own send. Nothing to repeat until it has happened,
        // which is also what keeps this inert when the version exchange is switched off.
        if (lastSentAtMs == 0L) return false
        // A clock that moved backwards reads as too soon, rather than firing every pass.
        return nowMs - lastSentAtMs >= INTERVAL_MS
    }
}
