package com.carlink.core.connection

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.carlink.core.core.Engine
import com.carlink.core.core.PhoneKind
import com.carlink.core.core.Transport
import com.carlink.core.settings.WirelessPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/** One session the driver or a trigger asked for. */
data class SessionRequest(
    val engine: Engine,
    val transport: Transport,
    val phoneAddress: String?,
    val phoneName: String?,
    val usbAttach: Intent? = null,
)

/** Something that must be fixed before a session can start; each has a one-tap fix in the UI. */
enum class Problem {
    BLUETOOTH_OFF,
    PHONE_NOT_PAIRED,
    WIFI_OFF,
    LOCATION_OFF,
    PERMISSIONS,
    OVERLAY,
    VPN_CONSENT,
    CARPLAY_AUTH_MISSING,
    NO_IPHONE_SELECTED,
    /** Same Wi-Fi: no saved network, or no password for a secured one. */
    SAME_WIFI_DETAILS,
    /** Same Wi-Fi: this device is not on the saved network (another one, none, or two at once). */
    SAME_WIFI_NOT_JOINED,
    CAR_HOTSPOT_DETAILS,
    CAR_HOTSPOT_OFF,
    /**
     * Wireless only: this stereo's Bluetooth is an external module the app cannot reach
     * ([SessionManager.Environment.wirelessUnavailable] for the engine); USB works. No one-tap fix.
     */
    EXTERNAL_BLUETOOTH,
    /** Android 16+ lost this phone's Bluetooth key (`ACTION_KEY_MISSING`): pair again. See detect/PairingLoss. */
    PAIRING_LOST,
}


/** What CarLink shows. Exactly one session exists at a time. */
sealed interface SessionState {
    data object Idle : SessionState

    /** The request cannot start until the driver fixes [problems]. */
    data class Blocked(val request: SessionRequest, val problems: List<Problem>) : SessionState

    data class Active(
        val request: SessionRequest,
        val path: WirelessPath?,
        /** 1 for the first path, 2+ while trying the next path after a path-related failure. */
        val attempt: Int,
        val phase: Phase,
        /** Why the current phase takes long, or what the stall watchdog is doing about it; null while on time. */
        val notice: Notice? = null,
    ) : SessionState

    data class Failed(
        val request: SessionRequest,
        val path: WirelessPath?,
        val reason: FailureReason,
        val detail: String?,
    ) : SessionState

    sealed interface Phase {
        data object Starting : Phase
        data class Progress(val step: Int, val stepCount: Int, val stage: ProgressStage) : Phase
        /**
         * Retrying after [reason], on the [stage] it happened in (not back to the start), with
         * [detail] in the driver's words ("the iPhone did not answer over Bluetooth").
         */
        data class Retrying(
            val attempt: Int,
            val reason: FailureReason,
            val stage: ProgressStage = ProgressStage.WAITING_FOR_PHONE,
            val detail: String? = null,
        ) : Phase
        data object Connected : Phase
    }

    val engine: Engine?
        get() = when (this) {
            Idle -> null
            is Blocked -> request.engine
            is Active -> request.engine
            is Failed -> request.engine
        }

    /** A session is running or being set up (not idle, blocked or failed). */
    val isActive: Boolean get() = this is Active
}

/** A phase is late: what the driver is told, and the recovery under way ([action]). */
data class Notice(
    val kind: Kind,
    val stage: ProgressStage?,
    val message: String,
    val action: RecoveryAction? = null,
) {
    enum class Kind {
        /** Still waiting for the phone after [AdaptiveDeadlines.HINT_MILLIS] (or its deadline). */
        PHONE_NOT_ANSWERING,
        /** A later phase is past [AdaptiveDeadlines.HINT_MILLIS] but not yet past its deadline. */
        SLOW,
        /** A phase passed its deadline and the watchdog applied [Notice.action]. */
        RECOVERING,
    }
}

/**
 * The one place that runs sessions (design/REBUILD_V2.md §4). Main process, main thread.
 *
 * Rules, each from a failure seen on a real device or in the engine dissections:
 * - One engine at a time; a new session starts only after the previous one's stop completed
 *   (DP F14: starting early let the old teardown stop the new session's service).
 * - Wireless paths come from [WirelessPolicy], re-ordered by this phone's record
 *   ([ConnectionProfiles.rankPaths]); only a path-related failure moves to the next path, and the
 *   path that connected is remembered per phone.
 * - Automatic starts never interrupt a running session and respect the driver's pause.
 * - USB re-enumeration during a USB session is the engine's business, not a new session; a USB
 *   session ends when its phone stays unplugged past the engines' own re-enumeration.
 * - No silent waits (docs/RELIABILITY_AUDIT_2026-10-09.md K2, K4): each phase has a deadline
 *   learnt from this phone ([AdaptiveDeadlines]); the driver is told why it waits after at most
 *   [AdaptiveDeadlines.HINT_MILLIS], and a phase past its deadline gets the next recovery from
 *   [StallPlaybook] (nudge, USB re-switch, retry, fresh Wi-Fi Direct, next path, re-plug, fail).
 */
class SessionManager(
    private val adapters: Map<Engine, EngineAdapter>,
    private val environment: Environment,
    /** Writes the session's settings into the engine; may block (IPC to ":aa"). */
    private val applySettings: (SessionPlan) -> Unit,
    /** Runs [applySettings] off the main thread, in order. */
    private val worker: Executor,
    private val main: Handler = Handler(Looper.getMainLooper()),
    /** Per-phone history: path order, phase deadlines and [health]. */
    private val profiles: ConnectionProfiles = ConnectionProfiles(),
    /** Monotonic time for phase durations and deadlines. */
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    /** Everything [SessionManager] reads from the device and the stored preferences. */
    interface Environment {
        val autoConnectUsb: Boolean
        val autoConnectBluetooth: Boolean
        var autoConnectPaused: Boolean

        /**
         * True when wireless [engine] cannot start on this device: its Bluetooth is an external
         * module the app cannot reach (detect/ExternalBluetoothProbe), and for Android Auto the
         * driver did not opt into the module route (CarPlay has none). Wireless requests are then
         * [Problem.EXTERNAL_BLUETOOTH] before any engine starts, and automatic wireless starts are
         * skipped. USB is never affected.
         */
        fun wirelessUnavailable(engine: Engine): Boolean = false

        /** Problems that prevent [request] on [path] (pre-flight; does not open anything). */
        fun preflight(request: SessionRequest, path: WirelessPath?): List<Problem>
        /** Wireless paths to try for [request], in order (see [WirelessPolicy]). */
        fun wirelessPaths(request: SessionRequest): List<WirelessPath>
        /** Remembers that [path] connected for [phoneAddress]. */
        fun rememberWorkingPath(engine: Engine, phoneAddress: String, path: WirelessPath)
        fun rememberPhone(phoneAddress: String, kind: PhoneKind)
        fun usbPhonePresent(engine: Engine): Boolean
        /** The phone of a USB session was unplugged: bring CarLink's Home back in front. */
        fun returnHome()
        /** Called when an engine becomes engaged or none is (DiAuto's key receivers follow this). */
        fun onEngaged(engine: Engine?)
        fun log(message: String)

        /**
         * Work that gives [request] a head start while the previous engine stops (wireless
         * CarPlay: page the iPhone over Bluetooth, RELIABILITY_AUDIT K3). Calls [done] (any
         * thread) when finished; returns false when it started nothing. The engine starts only
         * after [done] or [WARM_UP_MAX_MILLIS], so the two never dial the phone at once.
         */
        fun warmUp(request: SessionRequest, done: () -> Unit): Boolean = false
    }

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _pendingSwitch = MutableStateFlow<SessionRequest?>(null)

    /** Bluetooth phones already offered during the current session (one offer each). */
    private val offeredThisSession = mutableSetOf<String>()
    /**
     * A second phone was plugged in while another session runs: the driver is asked
     * "Switch to <phone>?" (user decision, 2026-10-09) instead of it taking over by itself.
     */
    val pendingSwitch: StateFlow<SessionRequest?> = _pendingSwitch.asStateFlow()

    /** Each known phone's connection health (success rate, time to picture, last problems). */
    val health: StateFlow<List<PhoneHealth>> get() = profiles.health

    /** Increases with every new session or stop; events from older sessions are ignored. */
    private var token = 0
    private var running: Engine? = null
    private var stopping = 0
    private val afterStop = ArrayDeque<() -> Unit>()

    /** carlink.log tags each session's lines `#s<n>`. */
    private var sessionId = 0
    /** The engine attempt that currently reports; its events alone count. */
    private var attempt: Attempt? = null
    private var nextAttemptId = 0
    /** Recoveries used in this request, per (path index, phase); a null phase is the whole attempt. */
    private val recoveries = mutableMapOf<Pair<Int, ProgressStage?>, Int>()
    /** The current request has shown a picture at least once. */
    private var everConnected = false
    /** A [Environment.warmUp] is running; the engine starts after it. */
    private var warmingUp = false

    /** When the driver last stopped a session (uptime); see [onUsbPhone]. */
    private var driverStoppedAt = -REENUMERATION_WINDOW_MILLIS
    /** The engine of the USB session the driver last stopped; only its phone re-enumerates. */
    private var driverStoppedUsbEngine: Engine? = null

    /** One engine start on one path, with its phase clock. */
    private class Attempt(
        val request: SessionRequest,
        val plan: SessionPlan,
        val paths: List<WirelessPath?>,
        val index: Int,
        val id: Int,
        now: Long,
    ) {
        /** Adapter start (or the last loss of the picture): the whole-attempt deadline counts from here. */
        var startedAt = now
        var stage = ProgressStage.WAITING_FOR_PHONE
        var stageSince = now
        var step = 1
        var stepCount = 4
        /** Deadlines passed in the current phase. */
        var expiries = 0
        var hinted = false
        var totalFired = false
        var phoneSilent = false
        var connectedOnce = false
    }

    /**
     * Remembers a driver stop; only a USB session's stop makes its phone re-enumerate, and only
     * when the phone was still plugged in. A phone that was already unplugged (tablet 2026-10-20:
     * unplugged, Disconnect, plugged in again) is plugged in for real next time.
     */
    private fun markDriverStop(stopped: SessionState) {
        val active = stopped as? SessionState.Active
        driverStoppedUsbEngine = active?.request?.engine
            ?.takeIf { active.request.transport == Transport.USB && environment.usbPhonePresent(it) }
        driverStoppedAt = SystemClock.uptimeMillis()
    }

    private val watchdog = Runnable { onWatchdog() }

    private val warmUpCap = Runnable {
        if (!warmingUp) return@Runnable
        note("Bluetooth warm-up still running after $WARM_UP_MAX_MILLIS ms; starting the engine anyway")
        warmingUp = false
        drainAfterStop()
    }

    private val usbGoneCheck = Runnable {
        val current = _state.value as? SessionState.Active ?: return@Runnable
        if (current.request.transport != Transport.USB) return@Runnable
        if (environment.usbPhonePresent(current.request.engine)) return@Runnable
        note("USB phone for ${current.request.engine} unplugged; ending the session")
        // Unplugging is the driver stopping: no automatic start right after (it started the
        // last wireless phone instead on the tablet, 2026-10-09).
        environment.autoConnectPaused = true
        stop()
        environment.returnHome()
    }

    init {
        // A session nobody shows (e.g. Android Auto left running in ":aa" after this process
        // restarted) is stopped: the driver must always see what runs (DA§10.6.1).
        adapters.values.filter { it.hasSession() }.forEach { adapter ->
            note("stopping an orphaned ${adapter.engine} session")
            stopping++
            adapter.stop { stopping--; drainAfterStop() }
        }
    }

    // --- Triggers ------------------------------------------------------------------------------

    /** A phone was plugged in. USB is a deliberate act, so it may replace a wireless session. */
    fun onUsbPhone(kind: PhoneKind, phoneName: String?, attach: Intent?) {
        val engine = kind.engine ?: return
        if (!environment.autoConnectUsb) return note("USB $engine phone: automatic USB start is off")
        val current = _state.value
        // Both engines re-enumerate the phone on purpose while connecting (CarPlay's
        // configuration switch, Android Auto's accessory switch); each is a new attach.
        if (current is SessionState.Active && current.request.engine == engine && current.request.transport == Transport.USB) {
            main.removeCallbacks(usbGoneCheck)
            return note("USB re-attach during the $engine session; left to the engine")
        }
        // Stopping a USB session re-enumerates the phone too; that is not the driver plugging in.
        // A real re-plug after stopping a wireless session (or the other phone) is honoured at once.
        if (driverStoppedUsbEngine == engine && SystemClock.uptimeMillis() - driverStoppedAt < REENUMERATION_WINDOW_MILLIS) {
            return note("USB $engine attach right after the driver stopped; not restarting")
        }
        val request = SessionRequest(engine, Transport.USB, null, phoneName, attach)
        if (current is SessionState.Active) {
            // Plugging in the phone a wireless attempt of the same engine is still reaching is the
            // driver choosing the cable: no question (RELIABILITY_AUDIT K14: 2.5-3 s and a tap).
            if (current.request.engine == engine && current.request.transport == Transport.WIRELESS &&
                current.phase != SessionState.Phase.Connected) {
                note("USB $engine phone while the wireless $engine attempt is still connecting; switching to USB")
                _pendingSwitch.value = null
                return start(request)
            }
            note("USB $engine phone while a ${current.request.engine} session runs; asking the driver")
            _pendingSwitch.value = request
            return
        }
        start(request)
    }

    /** The driver answered "Switch" to [pendingSwitch]. */
    fun acceptSwitch() {
        val request = _pendingSwitch.value ?: return
        _pendingSwitch.value = null
        environment.autoConnectPaused = false
        start(request)
    }

    /** The driver answered "Not now": the running session stays; the phone waits until picked. */
    fun declineSwitch() {
        _pendingSwitch.value?.let { note("driver kept the ${_state.value.engine} session") }
        _pendingSwitch.value = null
    }

    /** A USB device left. A USB session whose phone does not come back within the grace ends. */
    fun onUsbDetached() {
        _pendingSwitch.value?.let { pending ->
            main.postDelayed({
                if (_pendingSwitch.value === pending && !environment.usbPhonePresent(pending.engine)) _pendingSwitch.value = null
            }, USB_GONE_GRACE_MILLIS)
        }
        // A USB request that was blocked or failed belongs to the phone that left: once it stays
        // unplugged, Home shows its normal screen again instead of the old problem.
        val shown = _state.value
        val usbRequest = (shown as? SessionState.Blocked)?.request ?: (shown as? SessionState.Failed)?.request
        if (usbRequest?.transport == Transport.USB) {
            main.postDelayed({
                if (_state.value === shown && !environment.usbPhonePresent(usbRequest.engine)) {
                    note("USB phone for ${usbRequest.engine} unplugged; clearing its ${shown.javaClass.simpleName.lowercase()} state")
                    stop()
                }
            }, USB_GONE_GRACE_MILLIS)
            return
        }
        val current = shown as? SessionState.Active ?: return
        if (current.request.transport != Transport.USB) return
        main.removeCallbacks(usbGoneCheck)
        // While connecting, the engines re-enumerate the phone on purpose; once projecting they do
        // not, so an unplug then returns to Home almost at once (user request, 2026-10-09). A
        // session that showed a picture and is now retrying is past its re-enumeration too (K15).
        val grace = if (current.phase == SessionState.Phase.Connected || everConnected) USB_GONE_CONNECTED_GRACE_MILLIS
            else USB_GONE_GRACE_MILLIS
        main.postDelayed(usbGoneCheck, grace)
    }

    /** A paired phone's Bluetooth link came up. Never interrupts a running session. */
    fun onBluetoothPhone(address: String, kind: PhoneKind, phoneName: String?) {
        val engine = kind.engine ?: return
        when {
            !environment.autoConnectBluetooth -> note("Bluetooth $engine phone: automatic start is off")
            // Nothing wireless can start here; neither a start nor a switch offer would help.
            environment.wirelessUnavailable(engine) -> note("Bluetooth $engine phone: wireless is not available on this stereo")
            environment.autoConnectPaused -> note("Bluetooth $engine phone: paused by the driver")
            _state.value.isActive -> {
                // Another phone reached the car while one is projecting: offer it, never take over
                // (user request, 2026-10-09: "show that a device is available to switch").
                val current = (_state.value as SessionState.Active).request
                val samePhone = current.phoneAddress?.equals(address, ignoreCase = true) == true
                if (!samePhone && _pendingSwitch.value?.phoneAddress?.equals(address, ignoreCase = true) != true &&
                    address !in offeredThisSession) {
                    offeredThisSession += address
                    note("Bluetooth $engine phone while a ${current.engine} session runs; offering a switch")
                    _pendingSwitch.value = SessionRequest(engine, Transport.WIRELESS, address, phoneName)
                }
            }
            else -> start(SessionRequest(engine, Transport.WIRELESS, address, phoneName))
        }
    }

    /**
     * This screen lost [address]'s Bluetooth pairing (detect/PairingLoss). Shown on Home as a
     * blocked request; an attempt still connecting that phone stops, since it cannot succeed.
     */
    fun onPairingLost(address: String, kind: PhoneKind, phoneName: String?) {
        val engine = kind.engine ?: return
        val current = _state.value
        if (current is SessionState.Active) {
            val samePhone = current.request.phoneAddress?.equals(address, ignoreCase = true) == true
            if (!samePhone || current.phase == SessionState.Phase.Connected) return note("Bluetooth pairing lost for another or a connected phone")
            stop()
        }
        note("$engine blocked: this screen's Bluetooth pairing with the phone was lost")
        _state.value = SessionState.Blocked(SessionRequest(engine, Transport.WIRELESS, address, phoneName), listOf(Problem.PAIRING_LOST))
    }

    /**
     * Home came to the front (app opened, screen woke): start the last-used phone wirelessly, as a
     * car head unit does on power-up, so the driver does not wait for a tap. A tablet is not a
     * Bluetooth car kit, so the phone never connects to it by itself (DA§11.3). Same rules as a
     * Bluetooth wake, plus: only from idle, at most once per [HOME_AUTO_START_COOLDOWN_MILLIS].
     */
    fun onHomeShown(address: String?, kind: PhoneKind?, phoneName: String?) {
        val engine = kind?.engine ?: return
        if (address == null) return
        val now = SystemClock.uptimeMillis()
        when {
            !environment.autoConnectBluetooth || environment.autoConnectPaused -> return
            _state.value !is SessionState.Idle -> return
            // No Bluetooth attempt and no Wi-Fi Direct churn on a stereo where wireless cannot start.
            environment.wirelessUnavailable(engine) -> return note("Home shown: wireless is not available on this stereo; not connecting")
            now - lastHomeAutoStart < HOME_AUTO_START_COOLDOWN_MILLIS -> return
            // Home coming back because a session just ended is not "the car started".
            now - lastSessionEndedAt < HOME_AUTO_START_COOLDOWN_MILLIS -> return
            else -> {
                lastHomeAutoStart = now
                note("Home shown: connecting the last phone ($engine)")
                start(SessionRequest(engine, Transport.WIRELESS, address, phoneName))
            }
        }
    }

    private var lastHomeAutoStart = -HOME_AUTO_START_COOLDOWN_MILLIS
    private var lastSessionEndedAt = -HOME_AUTO_START_COOLDOWN_MILLIS

    /** The driver asked for this phone. Clears the pause and replaces any running session. */
    fun connect(address: String?, kind: PhoneKind, phoneName: String?, transport: Transport = Transport.WIRELESS) {
        val engine = kind.engine ?: return
        environment.autoConnectPaused = false
        address?.let { environment.rememberPhone(it, kind) }
        start(SessionRequest(engine, transport, address, phoneName))
    }

    /**
     * Try the last request again (Home's "Try again", or after the driver fixed a problem). A
     * running attempt restarts, so a setting that applies at the next connection takes effect.
     */
    fun retry() {
        val request = when (val s = _state.value) {
            is SessionState.Blocked -> s.request
            is SessionState.Failed -> s.request
            is SessionState.Active -> s.request
            else -> return
        }
        environment.autoConnectPaused = false
        start(request)
    }

    /**
     * The driver changed a setting (a Wi-Fi password, the connection type): a blocked request
     * checks again at once, so the fix shows without a Try again.
     */
    fun onSettingsChanged() {
        if (_state.value !is SessionState.Blocked) return
        note("settings changed; checking the blocked request again")
        retry()
    }

    /** The driver cancelled or disconnected: stop, and pause automatic starts until they connect. */
    fun disconnect() {
        note("driver disconnect")
        environment.autoConnectPaused = true
        val wasActive = _state.value.isActive
        markDriverStop(_state.value)
        stop()
        // Leave the driver on CarLink's Home, not the system launcher the projection returns to.
        if (wasActive) environment.returnHome()
    }

    /** Stops whatever runs and goes idle. */
    fun stop() {
        if (_state.value.isActive) lastSessionEndedAt = SystemClock.uptimeMillis()
        offeredThisSession.clear()
        token++
        endAttempt()
        main.removeCallbacks(usbGoneCheck)
        if (warmingUp) {
            warmingUp = false
            main.removeCallbacks(warmUpCap)
        }
        val engine = running
        _state.value = SessionState.Idle
        if (engine != null) {
            running = null
            environment.onEngaged(null)
            stopEngine(engine)
        }
        drainAfterStop()
    }

    // --- Sessions ------------------------------------------------------------------------------

    private fun start(request: SessionRequest) {
        sessionId++
        note("start ${request.engine} ${request.transport} known=${request.phoneAddress != null}")
        stop()
        recoveries.clear()
        everConnected = false
        // Before any engine, warm-up or path: a wireless start cannot work on this stereo.
        if (request.transport == Transport.WIRELESS && environment.wirelessUnavailable(request.engine)) {
            note("${request.engine} blocked: wireless is not available on this stereo (external Bluetooth module)")
            _state.value = SessionState.Blocked(request, listOf(Problem.EXTERNAL_BLUETOOTH))
            return
        }
        if (stopping > 0) warmUp(request)
        val paths = if (request.transport == Transport.WIRELESS) {
            val policy = environment.wirelessPaths(request)
            profiles.rankPaths(request, policy).also {
                if (it != policy) note("wireless paths $policy re-ordered by this phone's record: $it")
            }
        } else listOf(null)
        begin(request, paths, index = 0, token)
    }

    /** Pages an iPhone over Bluetooth while the previous engine stops (RELIABILITY_AUDIT plan item 7). */
    private fun warmUp(request: SessionRequest) {
        if (request.engine != Engine.CARPLAY || request.transport != Transport.WIRELESS || request.phoneAddress == null) return
        val sessionToken = token
        val started = clock()
        warmingUp = true
        val began = environment.warmUp(request) {
            main.post {
                if (sessionToken != token || !warmingUp) return@post
                warmingUp = false
                main.removeCallbacks(warmUpCap)
                note("Bluetooth warm-up finished in ${clock() - started} ms")
                drainAfterStop()
            }
        }
        if (!began) {
            warmingUp = false
            return
        }
        note("paging the iPhone over Bluetooth while the previous engine stops")
        main.postDelayed(warmUpCap, WARM_UP_MAX_MILLIS)
    }

    private fun begin(
        request: SessionRequest,
        paths: List<WirelessPath?>,
        index: Int,
        sessionToken: Int,
        phase: SessionState.Phase = SessionState.Phase.Starting,
        notice: Notice? = null,
    ) {
        val path = paths[index]
        val problems = environment.preflight(request, path)
        if (problems.isNotEmpty()) {
            note("${request.engine} blocked on ${path ?: "USB"}: $problems")
            _state.value = SessionState.Blocked(request, problems)
            return
        }
        val plan = SessionPlan(request.engine, request.transport, path, request.phoneAddress, request.phoneName,
            request.usbAttach, attempt = index + 1)
        _state.value = SessionState.Active(request, path, index + 1, phase, notice)
        running = request.engine
        environment.onEngaged(request.engine)
        main.removeCallbacks(usbGoneCheck)
        whenEnginesStopped {
            if (sessionToken != token) return@whenEnginesStopped
            worker.execute {
                runCatching { applySettings(plan) }.onFailure { Log.w(TAG, "could not apply settings", it) }
                main.post {
                    if (sessionToken != token) return@post
                    val a = Attempt(request, plan, paths, index, ++nextAttemptId, clock())
                    attempt = a
                    note("${plan.engine} starting on ${path ?: "USB"} (attempt ${plan.attempt})")
                    adapters.getValue(plan.engine).start(plan) { event -> main.post { onEvent(a, event) } }
                    armWatchdog()
                }
            }
        }
    }

    private fun onEvent(a: Attempt, event: AdapterEvent) {
        if (attempt !== a) return
        val current = _state.value as? SessionState.Active ?: return
        val request = a.request
        val plan = a.plan
        when (event) {
            is AdapterEvent.Progress -> onProgress(a, current, event)
            is AdapterEvent.Retrying -> {
                val now = clock()
                if (current.phase == SessionState.Phase.Connected) {
                    // The picture was lost and the engine reconnects: a new wait, with fresh deadlines.
                    a.startedAt = now
                    a.totalFired = false
                    enterStage(a, ProgressStage.WAITING_FOR_PHONE, now)
                }
                val detail = event.detail ?: event.reason.retryText(plan.engine)
                note("${plan.engine} retrying at ${a.stage} (attempt ${event.attempt}, ${event.reason}: $detail)")
                _state.value = current.copy(phase = SessionState.Phase.Retrying(event.attempt, event.reason, a.stage, detail))
                armWatchdog()
            }
            AdapterEvent.Connected -> {
                val now = clock()
                main.removeCallbacks(watchdog)
                val took = now - a.startedAt
                if (!a.connectedOnce) {
                    a.connectedOnce = true
                    profiles.recordStage(request, plan.path, a.stage, now - a.stageSince)
                    profiles.recordSuccess(request, plan.path, took)
                }
                everConnected = true
                note("${plan.engine} connected on ${plan.path ?: "USB"} in $took ms")
                if (plan.path != null && request.phoneAddress != null) {
                    environment.rememberWorkingPath(plan.engine, request.phoneAddress, plan.path)
                }
                request.phoneAddress?.let {
                    environment.rememberPhone(it, if (plan.engine == Engine.CARPLAY) PhoneKind.IPHONE else PhoneKind.ANDROID)
                }
                _state.value = current.copy(phase = SessionState.Phase.Connected, notice = null)
            }
            is AdapterEvent.Ended -> {
                note("${plan.engine} session ended${if (event.byUser) " by the driver" else ""}")
                if (!event.byUser && request.transport == Transport.USB && !environment.usbPhonePresent(plan.engine)) {
                    environment.returnHome()
                }
                if (event.byUser) {
                    environment.autoConnectPaused = true
                    markDriverStop(current)
                }
                stop()
            }
            is AdapterEvent.Failed -> {
                profiles.recordFailure(request, plan.path, event.reason.name, a.stage, event.detail)
                val next = a.index + 1
                if (event.reason.pathRelated && next < a.paths.size) {
                    note("${plan.engine} on ${plan.path} failed (${event.reason}${event.detail?.let { ": $it" } ?: ""}); trying ${a.paths[next]}")
                    // Same session for the driver: keep showing progress, not a failure.
                    replaceAttempt(a, next, SessionState.Phase.Retrying(next + 1, event.reason, a.stage,
                        event.detail ?: event.reason.retryText(plan.engine)), notice = null)
                } else {
                    failSession(a, event.reason, event.detail)
                }
            }
        }
    }

    private fun onProgress(a: Attempt, current: SessionState.Active, event: AdapterEvent.Progress) {
        val now = clock()
        val stage = ConnectionProfiles.normalize(event.stage)
        a.step = event.step
        a.stepCount = event.stepCount
        var notice = current.notice
        if (stage != a.stage) {
            if (order(stage) > order(a.stage)) {
                // Real progress: the phase that ended is a sample for this phone's deadlines, and
                // whatever the driver was told about it no longer applies.
                if (!a.connectedOnce) profiles.recordStage(a.request, a.plan.path, a.stage, now - a.stageSince)
                notice = null
                a.phoneSilent = false
            }
            enterStage(a, stage, now)
            armWatchdog()
        }
        val shown = if (a.phoneSilent && stage == ProgressStage.WAITING_FOR_PHONE) ProgressStage.PHONE_NOT_ANSWERING else event.stage
        _state.value = current.copy(phase = SessionState.Phase.Progress(event.step, event.stepCount, shown), notice = notice)
    }

    private fun enterStage(a: Attempt, stage: ProgressStage, now: Long) {
        a.stage = stage
        a.stageSince = now
        a.expiries = 0
        a.hinted = false
    }

    // --- Stall watchdog ------------------------------------------------------------------------

    private fun stageDeadline(a: Attempt) = profiles.deadline(a.request, a.plan.path, a.stage)
    private fun totalDeadline(a: Attempt) = profiles.deadline(a.request, a.plan.path, null)

    private fun armWatchdog() {
        main.removeCallbacks(watchdog)
        val a = attempt ?: return
        if ((_state.value as? SessionState.Active)?.phase == SessionState.Phase.Connected) return
        val deadline = stageDeadline(a)
        var due = a.stageSince + deadline * (a.expiries + 1)
        if (!a.hinted) due = minOf(due, a.stageSince + minOf(AdaptiveDeadlines.HINT_MILLIS, deadline))
        if (!a.totalFired) due = minOf(due, a.startedAt + totalDeadline(a))
        main.postDelayed(watchdog, (due - clock()).coerceAtLeast(0))
    }

    private fun onWatchdog() {
        val a = attempt ?: return
        val current = _state.value as? SessionState.Active ?: return
        if (current.phase == SessionState.Phase.Connected) return
        val now = clock()
        val deadline = stageDeadline(a)
        val inStage = now - a.stageSince
        if (!a.hinted && inStage >= minOf(AdaptiveDeadlines.HINT_MILLIS, deadline)) {
            a.hinted = true
            hint(a, inStage)
        }
        if (inStage >= deadline * (a.expiries + 1)) {
            a.expiries++
            recover(a, a.stage, inStage, deadline)
        }
        if (attempt === a && !a.totalFired && now - a.startedAt >= totalDeadline(a)) {
            a.totalFired = true
            recover(a, null, now - a.startedAt, totalDeadline(a))
        }
        if (attempt === a) armWatchdog()
    }

    /** Tells the driver why it waits (the audit's "never a silent wait over ~10 s"). */
    private fun hint(a: Attempt, waited: Long) {
        val current = _state.value as? SessionState.Active ?: return
        if (a.stage == ProgressStage.WAITING_FOR_PHONE) {
            a.phoneSilent = true
            note("${a.plan.engine}: the phone has not answered for ${waited / 1000} s")
            val phase = (current.phase as? SessionState.Phase.Progress)
                ?.takeIf { it.stage == ProgressStage.WAITING_FOR_PHONE }?.copy(stage = ProgressStage.PHONE_NOT_ANSWERING)
                ?: current.phase
            _state.value = current.copy(phase = phase, notice = Notice(Notice.Kind.PHONE_NOT_ANSWERING, a.stage,
                "The phone has not answered for ${waited / 1000} s"))
        } else {
            val usual = profiles.usualMillis(a.request, a.plan.path, a.stage)
            val message = "${StallPlaybook.phaseName(a.stage)} is taking longer than usual" +
                (usual?.let { " (usually ${seconds(it)})" } ?: "")
            note("${a.plan.engine}: ${message.replaceFirstChar { it.lowercase() }}, ${waited / 1000} s so far")
            _state.value = current.copy(notice = Notice(Notice.Kind.SLOW, a.stage, message))
        }
    }

    /** Applies the playbook's next action for [stage] (null: the whole attempt). */
    private fun recover(a: Attempt, stage: ProgressStage?, waited: Long, deadline: Long) {
        val plan = a.plan
        val request = a.request
        val key = a.index to stage
        val (action, used) = StallPlaybook.next(StallPlaybook.actions(plan.engine, plan.transport, stage),
            recoveries[key] ?: 0, a.index + 1 < a.paths.size)
        recoveries[key] = used
        val usual = profiles.usualMillis(request, plan.path, stage)
        val phaseName = StallPlaybook.phaseName(stage)
        note("stall: ${plan.engine} ${phaseName.lowercase()} on ${plan.path ?: "USB"} has taken $waited ms " +
            "(deadline $deadline ms${usual?.let { ", usual $it ms" } ?: ""}); $action")
        val kind = AttemptOutcome.stallKind(stage)
        val what = "$phaseName took ${waited / 1000} s"
        val message = "$what: ${StallPlaybook.actionText(action)}"
        when (action) {
            RecoveryAction.NUDGE, RecoveryAction.RESWITCH_USB -> {
                val applied = adapters.getValue(plan.engine).recover(action)
                profiles.recordProblem(request, plan.path, stage, kind, if (applied) message else "$what; still waiting")
                val current = _state.value as? SessionState.Active ?: return
                // Waiting for the phone: Home keeps saying what to check on it.
                if (current.notice?.kind != Notice.Kind.PHONE_NOT_ANSWERING) {
                    _state.value = current.copy(notice = Notice(Notice.Kind.RECOVERING, stage,
                        if (applied) message else "$what; still waiting", action))
                }
            }
            RecoveryAction.RETRY_STEP, RecoveryAction.RESET_WIFI_DIRECT, RecoveryAction.NEXT_PATH -> {
                profiles.recordFailure(request, plan.path, kind, stage, message)
                val (reason, _) = StallPlaybook.failure(plan.engine, plan.transport, stage, waited)
                val index = if (action == RecoveryAction.NEXT_PATH) a.index + 1 else a.index
                replaceAttempt(a, index, SessionState.Phase.Retrying(index + 1, reason, a.stage, message),
                    Notice(Notice.Kind.RECOVERING, stage, message, action))
            }
            RecoveryAction.ASK_REPLUG -> {
                profiles.recordFailure(request, plan.path, kind, stage, message)
                failSession(a, FailureReason.USB, "unplug the phone and plug it in again ($what)")
            }
            RecoveryAction.GIVE_UP -> {
                profiles.recordFailure(request, plan.path, kind, stage, message)
                val (reason, detail) = StallPlaybook.failure(plan.engine, plan.transport, stage, waited)
                failSession(a, reason, detail)
            }
        }
    }

    /** Stops [a]'s engine and starts path [index] (the same one, or the next) once it has stopped. */
    private fun replaceAttempt(a: Attempt, index: Int, phase: SessionState.Phase, notice: Notice?) {
        endAttempt()
        val current = _state.value as? SessionState.Active ?: return
        _state.value = current.copy(phase = phase, notice = notice)
        running = null
        stopEngine(a.plan.engine)
        begin(a.request, a.paths, index, token, phase, notice)
    }

    private fun failSession(a: Attempt, reason: FailureReason, detail: String?) {
        note("${a.plan.engine} failed: $reason${detail?.let { " ($it)" } ?: ""}")
        token++
        endAttempt()
        running = null
        environment.onEngaged(null)
        stopEngine(a.plan.engine)
        _state.value = SessionState.Failed(a.request, a.plan.path, reason, detail)
    }

    private fun endAttempt() {
        attempt = null
        main.removeCallbacks(watchdog)
    }

    // --- Engine stops --------------------------------------------------------------------------

    private fun stopEngine(engine: Engine) {
        stopping++
        val started = clock()
        adapters.getValue(engine).stop {
            main.post {
                stopping--
                note("$engine stopped in ${clock() - started} ms")
                drainAfterStop()
            }
        }
    }

    /** Runs [action] once no engine is stopping and no warm-up runs. */
    private fun whenEnginesStopped(action: () -> Unit) {
        if (stopping == 0 && !warmingUp) action() else afterStop.addLast(action)
    }

    private fun drainAfterStop() {
        while (stopping == 0 && !warmingUp && afterStop.isNotEmpty()) afterStop.removeFirst().invoke()
    }

    private fun note(message: String) {
        val line = if (sessionId > 0) "$message #s$sessionId" else message
        Log.i(TAG, line)
        environment.log(line)
    }

    companion object {
        private const val TAG = "CarLink-Session"

        /** Longer than CarPlay's configuration switch and Android Auto's accessory switch. */
        const val USB_GONE_GRACE_MILLIS = 3_500L

        /** How long a phone may stay silent before Home says it is not answering (USB too). */
        const val NOT_ANSWERING_MILLIS = AdaptiveDeadlines.HINT_MILLIS

        /** A failed or cancelled automatic start is not retried every time Home reappears. */
        const val HOME_AUTO_START_COOLDOWN_MILLIS = 60_000L

        /** After the picture is up: long enough for a cable wiggle, short enough to feel instant. */
        const val USB_GONE_CONNECTED_GRACE_MILLIS = 1_200L

        /** How long USB attaches after the driver's Disconnect count as re-enumeration. */
        const val REENUMERATION_WINDOW_MILLIS = 10_000L

        /** The longest the engine start waits for [Environment.warmUp] (one Bluetooth page window plus SDP). */
        const val WARM_UP_MAX_MILLIS = 6_000L

        private fun order(stage: ProgressStage) = when (stage) {
            ProgressStage.WAITING_FOR_PHONE, ProgressStage.PHONE_NOT_ANSWERING -> 0
            ProgressStage.HANDSHAKE -> 1
            ProgressStage.JOINING_WIFI -> 2
            ProgressStage.STARTING_PROJECTION -> 3
        }

        private fun seconds(millis: Long) = if (millis < 10_000) "%.1f s".format(millis / 1000.0) else "${millis / 1000} s"
    }
}

/** What the engine is retrying after, in the driver's words (for [SessionState.Phase.Retrying.detail]). */
fun FailureReason.retryText(engine: Engine): String {
    val phone = if (engine == Engine.CARPLAY) "iPhone" else "phone"
    return when (this) {
        FailureReason.PHONE_REFUSED_WIFI -> "the $phone refused the car's Wi-Fi"
        FailureReason.PHONE_NEVER_REACHED_CAR -> "the $phone did not reach the car over Wi-Fi"
        FailureReason.LINK_TOO_WEAK -> "the connection dropped"
        FailureReason.WIFI_SETUP_FAILED -> "the car's Wi-Fi link could not be set up"
        FailureReason.BLUETOOTH -> "the $phone did not answer over Bluetooth"
        FailureReason.EXTERNAL_BLUETOOTH -> "this stereo's Bluetooth cannot be used for wireless"
        FailureReason.EXTERNAL_MODULE -> "the stereo's Bluetooth module could not be reached"
        FailureReason.PHONE_DID_NOT_ANSWER -> "the $phone did not answer"
        FailureReason.USB -> "the USB link restarted"
        FailureReason.BLOCKED -> "a permission or switch is missing"
        FailureReason.AUTHENTICATION -> "authentication failed"
        FailureReason.ENGINE -> "the engine restarted"
    }
}
