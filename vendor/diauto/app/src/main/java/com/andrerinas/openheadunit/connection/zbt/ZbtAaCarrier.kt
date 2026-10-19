// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0), connection/wifi/modes/nativeaa/zbt/ZbtAaCarrier.kt
// (see docs/VENDOR_CHANGES.md).
package com.andrerinas.openheadunit.connection.zbt

import com.andrerinas.openheadunit.aap.WirelessTimeline
import com.andrerinas.openheadunit.connection.ExternalBtTransportPolicy
import com.andrerinas.openheadunit.connection.ExternalModuleCarrier
import com.andrerinas.openheadunit.connection.HandshakeLink
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs the Android Auto handshake over the head unit's external Bluetooth module.
 *
 * Replaces the accept loop, on units where there is nothing to accept. Our own RFCOMM listener is
 * what normally says a phone has arrived; here the vendor daemon owns the Android Auto server on
 * the module and accepts the phone itself, so this instead opens a channel to the daemon, watches
 * the link state it reports, and starts a handshake when [ZbtAttemptPolicy] says a phone is there.
 *
 * The handshake above it is the same one, unchanged, reached through [HandshakeLink].
 *
 * **One pump, and it changes hands.** [ZbtByteChannel] is driven by whoever consumes it. While this
 * class is watching, that is the loop in [run]; once a handshake starts, it is the handshake's own
 * reader coroutine reading [ZbtByteChannel.input]. They never run at once: [run] stops pumping for
 * exactly as long as [serve] is running. The control-frame callback still fires throughout — on
 * whichever thread happens to be pumping — so it only logs and sets volatile fields, and never
 * writes to the channel.
 *
 * @param serve runs the handshake and closes the link when it ends. `NativeAaHandshakeManager`'s
 *   `handleHandshake`, whose `finally` does the closing.
 */
class ZbtAaCarrier(
    // CarLink: one hooks object instead of upstream's separate lambdas; no module-address seeding
    // and no join-refusal widening (neither exists in this DiAuto).
    private val hooks: ExternalBtTransportPolicy.Hooks,
    private val openChannel: (
        onControl: (Int, ByteArray) -> Unit,
        onRfcomm: (ByteArray) -> Unit
    ) -> ZbtByteChannel = { onControl, onRfcomm ->
        ZbtByteChannel.open(
            enableType = ZbtMessages.ENABLE_TYPE_ANDROID_AUTO,
            onControlFrame = onControl,
            onRfcommData = onRfcomm,
            // The handshake reads these bytes through the link, so they must be kept.
            bufferRfcommData = true
        )
    },
    private val now: () -> Long = { System.currentTimeMillis() }
) : ExternalModuleCarrier {

    companion object {
        /** How often to repeat "there is still nothing listening" at a level a default log keeps. */
        const val QUIET_REMINDER_MS = 5 * 60_000L

        /** How often to summarise while waiting for a phone. Matches the probe, and exists for the
         *  same reason: a log exported early must still contain the answer. */
        const val SUMMARY_EVERY_MS = 15_000L

        /** How often to ask the module to re-state the link, so a missed edge is picked up and the
         *  log carries proof the channel is still alive. */
        const val LINK_POLL_EVERY_MS = 30_000L
    }

    /** The live channel, for [close] to reach from another thread. */
    @Volatile
    private var channel: ZbtByteChannel? = null

    /** What the module last said about the phone. Written from the pump thread, read anywhere. */
    @Volatile
    private var presence: ZbtAttemptPolicy.Presence? = null

    /** Whether this channel has ever named a usable phone, which decides if we may speak blind. */
    @Volatile
    private var everSawPresence = false

    @Volatile
    private var moduleMac: String? = null

    @Volatile
    private var lastWakeAt = 0L

    /** When a wake was asked for and not yet sent, or 0. */
    private val wakeRequestedAt = AtomicLong(0L)

    @Volatile
    private var stopped = false

    /** The coroutine [run] is on, so the non-suspending [keepRunning] can see cancellation — it is
     *  called from inside [ZbtByteChannel.pumpOnce]'s plain callback, where suspending is not an
     *  option. */
    @Volatile
    private var carrierJob: Job? = null

    private var controlFrames = 0
    private var rfcommFrames = 0
    private var lastAttemptEndedAt = 0L
    private var attempts = 0

    /**
     * Open, watch, and hand over to a handshake whenever a phone appears. Returns when the manager
     * stops or a handoff completes.
     */
    override suspend fun run() {
        carrierJob = currentCoroutineContext()[Job]
        var refusals = 0
        var firstRefusalAt = 0L
        var warnedNoDaemon = false
        var lastQuietReminder = 0L
        var reportedUnavailable = false
        while (keepRunning()) {
            val opened = try {
                openChannel(::onControl, ::onRfcomm).also { channel = it }
            } catch (e: IOException) {
                // The one live test of whether this unit is really on this route. Detection says the
                // Bluetooth is an external module; it cannot say the module is reachable this way,
                // and one whole vendor family in that class is not.
                if (refusals == 0) firstRefusalAt = now()
                refusals++
                val wait = ZbtReopenPolicy.delayAfterRefusalMs(refusals)
                if (!warnedNoDaemon && ZbtReopenPolicy.warnsNoDaemon(now() - firstRefusalAt)) {
                    warnedNoDaemon = true
                    lastQuietReminder = now()
                    AppLog.w(
                        "NativeAA: [ZBT] nothing is listening on ${ZbtByteChannel.HOST}:${ZbtByteChannel.PORT}. " +
                            "This unit carries the external-Bluetooth markers but has no vendor daemon to " +
                            "carry Android Auto, so the module transport can do nothing here. " +
                            "Wireless will not connect over Bluetooth on this unit; use USB, or a WiFi mode " +
                            "that needs no Bluetooth handshake. (${e.message})"
                    )
                    // CarLink: the host fails the session and tells the driver to use USB.
                    if (!reportedUnavailable) {
                        reportedUnavailable = true
                        ExternalBtTransportPolicy.reportUnavailable(ExternalBtTransportPolicy.Route.ZBT,
                            "nothing answers on ${ZbtByteChannel.HOST}:${ZbtByteChannel.PORT} (${e.message})")
                    }
                } else if (warnedNoDaemon && now() - lastQuietReminder >= QUIET_REMINDER_MS) {
                    lastQuietReminder = now()
                    AppLog.i("NativeAA: [ZBT] still nothing listening on port ${ZbtByteChannel.PORT}.")
                }
                AppLog.i("NativeAA: [ZBT] daemon refused, retrying in ${wait / 1000}s (${e.message})")
                delay(wait)
                continue
            }

            refusals = 0
            warnedNoDaemon = false
            ExternalBtTransportPolicy.step(WirelessTimeline.Phase.MODULE_REACHABLE,
                "zbt ${ZbtByteChannel.HOST}:${ZbtByteChannel.PORT} RequestInit enable_type=${ZbtMessages.ENABLE_TYPE_ANDROID_AUTO} sent")
            AppLog.i(
                "NativeAA: [ZBT] channel open to the Bluetooth module daemon on " +
                    "${ZbtByteChannel.HOST}:${ZbtByteChannel.PORT} — asking it to carry Android Auto"
            )
            try {
                watchAndServe(opened)
            } finally {
                val reason = opened.closeReason
                opened.close()
                channel = null
                AppLog.i("NativeAA: [ZBT] channel ended: ${reason ?: "closed"}")
            }
        }
        AppLog.i(
            "NativeAA: [ZBT] carrier stopped after $attempts handshake attempt(s), " +
                "$controlFrames control message(s), $rfcommFrames message(s) from the phone."
        )
    }

    /** Pump this channel until a phone appears, then hand it to the handshake. */
    private suspend fun watchAndServe(open: ZbtByteChannel) {
        var nextSummary = now() + SUMMARY_EVERY_MS
        var nextPoll = now() + LINK_POLL_EVERY_MS
        val openedAt = now()
        everSawPresence = false
        var answered = false

        while (keepRunning() && !open.isFinished) {
            // A quiet pump returns within the read timeout, so a request waits a few seconds at most.
            sendPendingWake(open)
            val wake = minOf(nextSummary, nextPoll)
            val pumped = open.pumpOnce(wake) { keepRunning() }
            if (pumped == ZbtByteChannel.Pump.ENDED) return

            // A connect proves something listens; only a frame proves it will carry Android Auto,
            // and a daemon busy with another client accepts the socket and answers nothing.
            if (pumped == ZbtByteChannel.Pump.FRAME && !answered) {
                answered = true // CarLink: the daemon took RequestInit and answered
                ExternalBtTransportPolicy.step(WirelessTimeline.Phase.MODULE_READY, "zbt daemon answered")
            }

            open.moduleMac?.let { moduleMac = it }

            if (shouldAttemptNow(openedAt)) {
                serveOnce(open, openForMs = now() - openedAt)
                // The handshake owns the channel's lifetime: its finally closes the link, which
                // closes the channel. Reopening is the outer loop's job.
                return
            }

            if (now() >= nextPoll) {
                nextPoll = now() + LINK_POLL_EVERY_MS
                runCatching { open.requestLinkInfo() }
                    .onFailure { AppLog.d("NativeAA: [ZBT] link poll failed: ${it.message}") }
            }
            if (now() >= nextSummary) {
                nextSummary = now() + SUMMARY_EVERY_MS
                logSummary(openedAt)
            }
        }
    }

    private suspend fun serveOnce(open: ZbtByteChannel, openForMs: Long) {
        val seen = presence
        attempts++
        // Which of the two reasons fired is only ever visible in a reporter's exported log, and it
        // is the whole point of the blind path.
        if (seen?.usable == true) {
            AppLog.i(
                "NativeAA: [ZBT] the module reports an Android Auto phone connected" +
                    (seen.phoneName?.let { " ($it)" } ?: "") +
                    " — starting handshake attempt #$attempts over the module."
            )
        } else {
            AppLog.i(
                "NativeAA: [ZBT] the module has said nothing about a phone in ${openForMs / 1000}s " +
                    "— speaking anyway (blind attempt #$attempts). The head unit speaks first, so " +
                    "this costs nothing if no phone is there."
            )
        }
        val link = ZbtLink(
            channel = open,
            peerName = seen?.phoneName,
            // The module reports it without separators, and everything downstream compares it
            // with android.bluetooth's colon form, so a raw one could never match.
            peerAddress = canonicalMac(seen?.phoneMac),
            moduleMac = moduleMac,
        )
        try {
            hooks.serve(link)
        } catch (e: CancellationException) {
            // Cancellation is the manager stopping us, not a failed attempt. Swallowing it here
            // would leave this coroutine running inside a scope that has already been cancelled.
            throw e
        } catch (e: Exception) {
            AppLog.w("NativeAA: [ZBT] handshake attempt ended: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            lastAttemptEndedAt = now()
        }
    }

    private fun shouldAttemptNow(openedAt: Long): Boolean = ZbtAttemptPolicy.shouldAttempt(
        phonePresent = presence?.usable == true,
        attemptInFlight = hooks.isHandshakeInFlight(),
        sessionConnected = hooks.isSessionConnected(),
        sinceLastAttemptMs = lastAttemptEndedAt.takeIf { it != 0L }?.let { now() - it },
        channelOpenForMs = now() - openedAt,
        everSawPresence = everSawPresence
    ) && hooks.mayServeHandshake()

    /**
     * Ask the module to bring the phone's link up — the module-side wake, replacing the HFP poke
     * that cannot reach this phone. Only records the request: the carrier's own thread sends it,
     * because a caller may be the main thread and the channel is a socket. Safe from any thread.
     *
     * CarLink: every request takes upstream's "user asked" path: it is recorded, and
     * [sendPendingWake] sends it when [ZbtWakePolicy.pending] allows, so the pacing stays.
     */
    override fun requestWake() {
        wakeRequestedAt.set(now())
        if (channel?.isFinished != false) {
            AppLog.i("NativeAA: [ZBT] wake requested before the module channel is open; it goes out once it is.")
        }
    }

    /** Sends a recorded wake from the carrier's own thread, when [ZbtWakePolicy.pending] allows. */
    private fun sendPendingWake(open: ZbtByteChannel) {
        val requestedAt = wakeRequestedAt.get()
        if (requestedAt == 0L) return
        val verdict = ZbtWakePolicy.pending(
            requestedAtMs = requestedAt,
            lastSentAtMs = lastWakeAt,
            nowMs = now(),
            settling = hooks.isSettling(),
            handshakeInFlight = hooks.isHandshakeInFlight(),
            sessionConnected = hooks.isSessionConnected()
        )
        if (verdict == ZbtWakePolicy.Pending.HOLD) return
        // A newer request arriving meanwhile keeps its own stamp and is judged on the next pass.
        if (!wakeRequestedAt.compareAndSet(requestedAt, 0L)) return
        when (verdict) {
            ZbtWakePolicy.Pending.SESSION_UP -> {
                AppLog.i("NativeAA: [ZBT] a session came up, so the pending wake is not sent.")
                return
            }
            ZbtWakePolicy.Pending.EXPIRED -> {
                AppLog.i("NativeAA: [ZBT] a wake could not go out within ${ZbtWakePolicy.PENDING_MAX_MS / 1000}s, so it is dropped.")
                return
            }
            else -> Unit
        }
        try {
            open.requestReconnect(ZbtMessages.ENABLE_TYPE_ANDROID_AUTO)
            lastWakeAt = now()
            ExternalBtTransportPolicy.step(WirelessTimeline.Phase.MODULE_WAKE, "zbt RequestReconn sent")
        } catch (e: Exception) {
            AppLog.w("NativeAA: [ZBT] could not ask the module to connect: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Ends the carrier and unblocks whatever is reading. Safe from any thread. */
    override fun close() {
        stopped = true
        runCatching { channel?.close() }
    }

    private fun keepRunning(): Boolean =
        !stopped && hooks.isRunning() && !hooks.isFinishedForSession() && carrierJob?.isActive != false

    // -----------------------------------------------------------------------------------------
    // Frames in
    // -----------------------------------------------------------------------------------------

    /** Wired into the channel. Runs on whichever thread is pumping — logs and volatiles only. */
    private fun onControl(msgId: Int, body: ByteArray) {
        controlFrames++
        AppLog.i(
            "NativeAA: [ZBT] [RX] id=0x${Integer.toHexString(msgId)} ${ZbtMessages.describe(msgId, body)}"
        )
        val read = ZbtAttemptPolicy.readPresence(msgId, ZbtMessages.parse(body)) ?: return
        val previous = presence
        presence = read
        if (read.usable) everSawPresence = true
        if (ZbtAttemptPolicy.isArrival(previous, read)) {
            AppLog.i(
                "NativeAA: [ZBT] the phone" + (read.phoneName?.let { " ($it)" } ?: "") +
                    " arrived on the module."
            )
            // A phone that has just connected is a fresh chance, not a continuation of whatever
            // failed before it. Clearing the backoff here is what stops a run of empty attempts
            // from making the one attempt that could have worked never happen.
            hooks.onPhoneEvidence()
        }
    }

    /** Wired into the channel. Only counts: the bytes themselves belong to the handshake. */
    private fun onRfcomm(bytes: ByteArray) {
        rfcommFrames++
        if (rfcommFrames == 1) {
            ExternalBtTransportPolicy.step(WirelessTimeline.Phase.PHONE_FIRST_BYTES,
                "zbt ${bytes.size} bytes — the byte channel is live in this direction")
            hooks.onPhoneEvidence()
        }
    }

    private fun logSummary(openedAt: Long) {
        val seconds = (now() - openedAt) / 1000
        val seen = presence
        AppLog.i(
            "NativeAA: [ZBT] ${seconds}s on the module channel — " +
                "control $controlFrames, from phone $rfcommFrames, attempts $attempts. " +
                "Phone: " + when {
                    seen == null -> "the module has not said yet"
                    seen.usable -> "connected${seen.phoneName?.let { " ($it)" } ?: ""}, Android Auto"
                    seen.connected -> "connected${seen.phoneName?.let { " ($it)" } ?: ""}, " +
                        "but not as an Android Auto device"
                    else -> "not connected"
                } + ". Module MAC ${moduleMac ?: "unknown"}."
        )
    }
}

/** A [ZbtByteChannel] as something the handshake can run over. */
class ZbtLink(
    private val channel: ZbtByteChannel,
    override val peerName: String?,
    override val peerAddress: String?,
    moduleMac: String?,
) : HandshakeLink {

    override val input: InputStream get() = channel.input
    override val output: OutputStream get() = channel.output

    /** The phone is bonded to the module, not to anything android.bluetooth can dial. */
    override val persistPeerForAutoStart: Boolean = false

    /** No accept happens on this route, so a first message can be dropped unseen. */
    override val retransmitsWhileSilent: Boolean = true

    override fun close() {
        channel.close()
    }
}

/** CarLink: the module's separator-less address as `AA:BB:CC:DD:EE:FF`, or null (upstream's BluetoothHelper.normalizeMacAddress). */
internal fun canonicalMac(raw: String?): String? {
    val hex = raw?.filter { it.isLetterOrDigit() }?.uppercase() ?: return null
    if (hex.length != 12 || hex.any { Character.digit(it, 16) < 0 }) return null
    return hex.chunked(2).joinToString(":")
}
