package com.carlink.core.connection.aa

import com.andrerinas.openheadunit.HostIntegration.EngineEvent
import com.carlink.core.connection.AdapterEvent
import com.carlink.core.connection.FailureReason
import com.carlink.core.connection.ProgressStage
import com.carlink.core.core.Transport

/**
 * One Android Auto session as the main process sees it: DiAuto's CommManager states and engine
 * events (DA§2.1, DA§11.5.2) in, [AdapterEvent]s out. Pure (time is passed in) so every rule is
 * unit tested; [com.carlink.core.connection.AndroidAutoAdapter] does the I/O.
 *
 * The engine never gives up on its own: it re-offers a refused network, re-pokes and re-arms after a
 * drop forever (DA§2.6, §3.3.2). The adapter therefore turns repeats into a terminal
 * [AdapterEvent.Failed] after the limits below, and [SessionManager] decides what comes next.
 */
internal class AaSessionTracker(
    private val transport: Transport,
    private val limits: Limits = Limits(),
) {
    data class Limits(
        /** Phone refusals of the handed network before failing. v1's measured rule: one can be a radio hiccup, a wrong password repeats within seconds. */
        val maxRefusals: Int = 2,
        /** Settle windows (45-90 s each, DA§2.7) that end with no TCP session. */
        val maxSettleTimeouts: Int = 2,
        /** Handshakes aborted for an unreadable BSSID while Location is on. */
        val maxBssidUnresolved: Int = 2,
        /** "Answers pokes, never calls back" warnings (one per 3 pokes, DA§2.7). */
        val maxPokeNoAccept: Int = 2,
        /** Projection sessions lost in one CarLink session before failing. */
        val maxLosses: Int = 3,
        /** No Android Auto channel from the phone by now: say so (DA§11.2 step 4: 45 s). */
        val acceptHintMillis: Long = 45_000,
        /** No Android Auto channel from the phone by now: fail. */
        val acceptTimeoutMillis: Long = 120_000,
        /** USB: no AAP connection by now (phone locked, Android Auto prompt not accepted). */
        val usbTimeoutMillis: Long = 60_000,
        /** Engine state reports may say "not running" this long after a start (the receiver is still starting it). */
        val startGraceMillis: Long = 15_000,
        /** How long a Disconnected state waits for SESSION_ENDED to say whether it was the user. */
        val endDebounceMillis: Long = 2_000,
        /** Wake the phone again this long after a loss (after the engine's own 1.5 s re-arm, DA§2.6). */
        val repokeAfterLossMillis: Long = 3_000,
        /**
         * USB: a session that ended before any frame (a stale accessory's failed handshake) is
         * re-switched once; with no new session by this long after, the phone needs a re-plug
         * (RELIABILITY_AUDIT K2: it used to wait 60 s, then blamed a locked phone).
         */
        val staleReplugMillis: Long = 5_000,
        /** USB, no frame yet: SESSION_ENDED only follows a session that connected, so wait less for it. */
        val noVideoEndDebounceMillis: Long = 500,
    )

    private val wireless = transport == Transport.WIRELESS
    private val stepCount = if (wireless) 4 else 3

    var terminal = false
        private set
    private var step = 0
    private var startedAt = 0L
    private var waitingSince = 0L
    private var accepted = false
    private var hinted = false
    private var sawServiceRunning = false
    /** A CommManager session (Connecting or later) is under way. */
    private var sessionLive = false
    private var connected = false
    private var pendingEndSince: Long? = null
    private var phoneJoined = false
    private var readTimeout = false
    private var refusals = 0
    private var settleTimeouts = 0
    private var bssidUnresolved = 0
    private var pokeNoAccept = 0
    private var losses = 0
    private var nextRepokeAt: Long? = null
    private var lastError: String? = null
    private var dfsChannel: String? = null
    private var staleReswitches = 0
    private var reswitchRequested = false
    private var reswitchDeadline: Long? = null

    fun begin(now: Long): List<AdapterEvent> {
        startedAt = now
        backToWaiting(now)
        nextRepokeAt = if (wireless) now + limits.acceptHintMillis else null
        return progress(1, ProgressStage.WAITING_FOR_PHONE)
    }

    /** A CommManager state report from ":aa". */
    fun onState(connection: String, message: String?, serviceRunning: Boolean, firstFrame: Boolean, now: Long): List<AdapterEvent> {
        if (terminal) return emptyList()
        if (serviceRunning) sawServiceRunning = true
        if (message != null) lastError = message
        val out = mutableListOf<AdapterEvent>()
        when (connection) {
            AaProtocol.CONNECTION_CONNECTING, AaProtocol.CONNECTION_CONNECTED,
            AaProtocol.CONNECTION_STARTING_TRANSPORT -> {
                enterSession()
                out += if (wireless) progress(4, ProgressStage.STARTING_PROJECTION) else progress(2, ProgressStage.HANDSHAKE)
            }
            AaProtocol.CONNECTION_HANDSHAKE_COMPLETE, AaProtocol.CONNECTION_TRANSPORT_STARTED -> {
                enterSession()
                out += if (wireless) progress(4, ProgressStage.STARTING_PROJECTION) else progress(3, ProgressStage.STARTING_PROJECTION)
                if (firstFrame) out += connect()
            }
            AaProtocol.CONNECTION_DISCONNECTED, AaProtocol.CONNECTION_ERROR -> {
                // A failed USB handshake can go Connecting → Error → Disconnected faster than the
                // state reports; the error message alone still means a session started and ended.
                if (!wireless && !sessionLive && message?.contains("Handshake failed", ignoreCase = true) == true) {
                    sessionLive = true
                }
                if (sessionLive && pendingEndSince == null) pendingEndSince = now
            }
        }
        if (!serviceRunning && !sessionLive && (sawServiceRunning || now - startedAt >= limits.startGraceMillis)) {
            out += fail(FailureReason.ENGINE, "the Android Auto service stopped")
        }
        return out
    }

    /** An engine event ([EngineEvent] name) or one of [AaProtocol]'s receiver kinds. */
    fun onEvent(kind: String, detail: String?, now: Long): List<AdapterEvent> {
        if (terminal) return emptyList()
        val d = AaProtocol.parseDetail(detail)
        val wirelessOnly = kind in WIRELESS_ONLY
        if (wirelessOnly && !wireless) return emptyList() // the engine also arms wireless during USB sessions (DA§2.3)
        if (kind == EngineEvent.USB_PERMISSION_DENIED.name && wireless) return emptyList()
        return when (kind) {
            EngineEvent.WIFI_OFF.name -> fail(FailureReason.BLOCKED, "Wi-Fi is off")
            EngineEvent.P2P_OWNED_BY_OTHER.name -> fail(FailureReason.WIFI_SETUP_FAILED, "Wi-Fi Direct is in use by another app")
            EngineEvent.DFS_CHANNEL.name -> { dfsChannel = d["frequency"]; emptyList() }
            EngineEvent.BSSID_UNRESOLVED.name ->
                if (d["location"] == "off") fail(FailureReason.BLOCKED, "Location is off, so the Wi-Fi Direct address is hidden")
                else repeatOrFail(++bssidUnresolved, limits.maxBssidUnresolved, FailureReason.WIFI_SETUP_FAILED,
                    "the Wi-Fi Direct address could not be read", now)
            EngineEvent.BLUETOOTH_UNAVAILABLE.name ->
                fail(FailureReason.BLOCKED, if (d["cause"] == "permission") "Bluetooth permission missing" else "Bluetooth is off")
            EngineEvent.EXTERNAL_BLUETOOTH.name ->
                fail(FailureReason.BLUETOOTH, "this unit's Bluetooth is an external module the app cannot reach")
            EngineEvent.BT_CHANNEL_ACCEPTED.name -> {
                accepted = true
                nextRepokeAt = null
                progress(2, ProgressStage.HANDSHAKE)
            }
            EngineEvent.CREDENTIALS_SENT.name -> {
                accepted = true
                progress(3, ProgressStage.JOINING_WIFI)
            }
            EngineEvent.POKE_ANSWERED_NO_ACCEPT.name -> repeatOrFail(++pokeNoAccept, limits.maxPokeNoAccept,
                FailureReason.PHONE_DID_NOT_ANSWER, "the phone answers Bluetooth but never starts Android Auto", now, waiting = false)
            EngineEvent.HANDSHAKE_BACKOFF.name ->
                fail(FailureReason.BLUETOOTH, "the phone's Bluetooth handshake stayed silent (${d["failures"] ?: "several"} attempts)")
            EngineEvent.PHONE_WIFI_STATUS.name -> onWifiStatus(d, now)
            EngineEvent.SETTLE_TIMEOUT.name -> {
                // Joined but never dialled TCP: the link (isolation, routing) failed; never joined:
                // the network was refused without a status (DA§3.2, §3.3.4).
                val reason = if (phoneJoined) FailureReason.PHONE_NEVER_REACHED_CAR else FailureReason.PHONE_REFUSED_WIFI
                repeatOrFail(++settleTimeouts, limits.maxSettleTimeouts, reason,
                    "no connection ${d["seconds"] ?: ""}s after the phone got the network details", now)
            }
            EngineEvent.STARVATION_ADVICE.name ->
                fail(FailureReason.LINK_TOO_WEAK, "${d["sessions"] ?: "several"} sessions in a row carried no video")
            EngineEvent.LINK_READ_TIMEOUT.name -> { readTimeout = true; emptyList() }
            EngineEvent.BOOT_LOOP_PAUSE.name -> fail(FailureReason.ENGINE, "wireless is paused by the engine's boot-loop guard")
            EngineEvent.USB_PERMISSION_DENIED.name -> fail(FailureReason.USB, "USB access was not allowed")
            EngineEvent.FIRST_VIDEO_FRAME.name -> if (sessionLive) connect() else emptyList()
            EngineEvent.SESSION_ENDED.name -> endSession(d["userExit"] == "true", d["clean"] == "true", now)
            AaProtocol.KIND_START_REFUSED -> fail(FailureReason.ENGINE, "Android did not let the Android Auto service start")
            AaProtocol.KIND_AOA_SWITCH_FAILED -> fail(FailureReason.USB, "the phone did not switch to Android Auto over USB")
            AaProtocol.KIND_AOA_STALE -> fail(FailureReason.USB, "unplug the phone and plug it in again")
            AaProtocol.KIND_USB_DEVICE_MISSING -> fail(FailureReason.USB, "the phone is no longer on USB")
            else -> emptyList()
        }
    }

    /** Timers; call every couple of seconds while the session lives. */
    fun onTick(now: Long): List<AdapterEvent> {
        if (terminal) return emptyList()
        pendingEndSince?.let { since ->
            val debounce = if (!wireless && !connected) limits.noVideoEndDebounceMillis else limits.endDebounceMillis
            if (now - since >= debounce) return endSession(userExit = false, clean = false, now = now)
        }
        reswitchDeadline?.let { deadline ->
            if (!sessionLive && now >= deadline) return fail(FailureReason.USB, STALE_REPLUG)
        }
        if (sessionLive || accepted) return emptyList()
        val waited = now - waitingSince
        if (!wireless) {
            return if (waited >= limits.usbTimeoutMillis)
                fail(FailureReason.PHONE_DID_NOT_ANSWER, "the phone did not start Android Auto over USB (unlock it and accept the prompt)")
            else emptyList()
        }
        if (waited >= limits.acceptTimeoutMillis) {
            return fail(FailureReason.PHONE_DID_NOT_ANSWER, "the phone did not answer over Bluetooth")
        }
        if (waited >= limits.acceptHintMillis && !hinted) {
            hinted = true
            return listOf(AdapterEvent.Retrying(1, FailureReason.PHONE_DID_NOT_ANSWER))
        }
        return emptyList()
    }

    /** True when the phone should be woken again now (an armed engine's manual poke is a single 20 s hold). */
    fun shouldRepoke(now: Long): Boolean {
        val at = nextRepokeAt ?: return false
        if (terminal || !wireless || sessionLive || accepted || now < at) return false
        nextRepokeAt = now + limits.acceptHintMillis
        return true
    }

    /** True once after a stale USB accessory was found: the adapter sends the accessory switch again. */
    fun takeReswitchRequest(): Boolean = reswitchRequested.also { reswitchRequested = false }

    /** ":aa" died under the session (crash or low-memory kill; the service is not sticky). */
    fun onProcessDied(): List<AdapterEvent> =
        if (terminal) emptyList() else fail(FailureReason.ENGINE, "the Android Auto process stopped")

    /** The adapter is stopping the session; nothing more is reported. */
    fun close() {
        terminal = true
    }

    private fun onWifiStatus(d: Map<String, String>, now: Long): List<AdapterEvent> {
        val status = d["status"]?.toIntOrNull() ?: return emptyList()
        val type = d["type"]?.toIntOrNull()
        if (status == 0) {
            if (type == CONNECT_STATUS) phoneJoined = true
            return emptyList()
        }
        // Any non-zero status is a refusal; the codes' meanings are not known (WppHandshakeSession):
        // -3 missing BSSID, -11 network unavailable are the measured ones (DA§3.2, §9.2).
        return repeatOrFail(++refusals, limits.maxRefusals, FailureReason.PHONE_REFUSED_WIFI,
            "the phone refused the Wi-Fi network (type $type, status $status)", now)
    }

    private fun enterSession() {
        accepted = true
        sessionLive = true
        pendingEndSince = null
        nextRepokeAt = null
        reswitchDeadline = null
    }

    private fun connect(): List<AdapterEvent> {
        if (connected) return emptyList()
        connected = true
        return listOf(AdapterEvent.Connected)
    }

    private fun endSession(userExit: Boolean, clean: Boolean, now: Long): List<AdapterEvent> {
        if (!sessionLive && pendingEndSince == null) return emptyList()
        val hadVideo = connected
        val timedOut = readTimeout
        sessionLive = false
        connected = false
        pendingEndSince = null
        readTimeout = false
        phoneJoined = false
        return when {
            userExit -> ended(byUser = true)
            clean -> ended(byUser = false) // the phone sent ByeBye
            // USB, not one frame: the phone did not take the session (stale accessory, K2). Re-switch
            // once, at once; a second time means the phone needs a re-plug.
            !wireless && !hadVideo -> if (staleReswitches++ == 0) {
                backToWaiting(now)
                reswitchRequested = true
                reswitchDeadline = now + limits.staleReplugMillis
                listOf(AdapterEvent.Retrying(1, FailureReason.USB,
                    "the phone was still in an earlier Android Auto session; restarting its USB link"))
            } else fail(FailureReason.USB, STALE_REPLUG)
            else -> {
                losses++
                // Starved (no frame on an established link) or idle for the read timeout: the link
                // is too weak (DA§3.3.2, §3.3.4). Otherwise an unexplained drop.
                val reason = when {
                    !wireless -> FailureReason.USB
                    timedOut || !hadVideo -> FailureReason.LINK_TOO_WEAK
                    else -> FailureReason.ENGINE
                }
                if (losses >= limits.maxLosses) {
                    fail(reason, "the connection dropped $losses times" + (lastError?.let { " ($it)" } ?: ""))
                } else {
                    backToWaiting(now)
                    if (wireless) nextRepokeAt = now + limits.repokeAfterLossMillis
                    listOf(AdapterEvent.Retrying(losses, reason))
                }
            }
        }
    }

    private fun repeatOrFail(count: Int, max: Int, reason: FailureReason, detail: String, now: Long,
                             waiting: Boolean = true): List<AdapterEvent> =
        if (count >= max) fail(reason, detail)
        else {
            if (waiting) {
                backToWaiting(now)
                nextRepokeAt = now + limits.acceptHintMillis
            }
            listOf(AdapterEvent.Retrying(count, reason))
        }

    private fun backToWaiting(now: Long) {
        waitingSince = now
        accepted = false
        hinted = false
        step = 0
    }

    private fun progress(newStep: Int, stage: ProgressStage): List<AdapterEvent> {
        if (newStep <= step) return emptyList()
        step = newStep
        return listOf(AdapterEvent.Progress(newStep, stepCount, stage))
    }

    private fun ended(byUser: Boolean): List<AdapterEvent> {
        terminal = true
        return listOf(AdapterEvent.Ended(byUser))
    }

    private fun fail(reason: FailureReason, detail: String): List<AdapterEvent> {
        terminal = true
        val hint = dfsChannel?.let { "; the Wi-Fi Direct group was on $it MHz, a channel most phones cannot join" } ?: ""
        return listOf(AdapterEvent.Failed(reason, detail + hint))
    }

    private companion object {
        const val CONNECT_STATUS = 6
        const val STALE_REPLUG = "unplug the phone and plug it in again (it did not start a new Android Auto session over USB)"
        val WIRELESS_ONLY = setOf(
            EngineEvent.WIFI_OFF.name, EngineEvent.P2P_OWNED_BY_OTHER.name, EngineEvent.DFS_CHANNEL.name,
            EngineEvent.BSSID_UNRESOLVED.name, EngineEvent.BLUETOOTH_UNAVAILABLE.name,
            EngineEvent.EXTERNAL_BLUETOOTH.name, EngineEvent.BT_CHANNEL_ACCEPTED.name,
            EngineEvent.CREDENTIALS_SENT.name, EngineEvent.POKE_ANSWERED_NO_ACCEPT.name,
            EngineEvent.HANDSHAKE_BACKOFF.name, EngineEvent.PHONE_WIFI_STATUS.name,
            EngineEvent.SETTLE_TIMEOUT.name, EngineEvent.LINK_READ_TIMEOUT.name,
            EngineEvent.BOOT_LOOP_PAUSE.name,
        )
    }
}
