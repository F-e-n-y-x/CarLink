package com.carlink.app.connection

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.carlink.app.core.Engine
import com.carlink.app.core.PhoneKind
import com.carlink.app.core.Transport
import com.carlink.app.settings.WirelessPath
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
    SAME_WIFI_DETAILS,
    CAR_HOTSPOT_DETAILS,
    CAR_HOTSPOT_OFF,
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
        data class Retrying(val attempt: Int, val reason: FailureReason) : Phase
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

/**
 * The one place that runs sessions (design/REBUILD_V2.md §4). Main process, main thread.
 *
 * Rules, each from a failure seen on a real device or in the engine dissections:
 * - One engine at a time; a new session starts only after the previous one's stop completed
 *   (DP F14: starting early let the old teardown stop the new session's service).
 * - Wireless paths come from [WirelessPolicy]; only a path-related failure moves to the next
 *   path, and the path that connected is remembered per phone.
 * - Automatic starts never interrupt a running session and respect the driver's pause.
 * - USB re-enumeration during a USB session is the engine's business, not a new session; a USB
 *   session ends when its phone stays unplugged past the engines' own re-enumeration.
 */
class SessionManager(
    private val adapters: Map<Engine, EngineAdapter>,
    private val environment: Environment,
    /** Writes the session's settings into the engine; may block (IPC to ":aa"). */
    private val applySettings: (SessionPlan) -> Unit,
    /** Runs [applySettings] off the main thread, in order. */
    private val worker: Executor,
    private val main: Handler = Handler(Looper.getMainLooper()),
) {
    /** Everything [SessionManager] reads from the device and the stored preferences. */
    interface Environment {
        val autoConnectUsb: Boolean
        val autoConnectBluetooth: Boolean
        var autoConnectPaused: Boolean
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
    }

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _pendingSwitch = MutableStateFlow<SessionRequest?>(null)
    /**
     * A second phone was plugged in while another session runs: the driver is asked
     * "Switch to <phone>?" (user decision, 2026-10-09) instead of it taking over by itself.
     */
    val pendingSwitch: StateFlow<SessionRequest?> = _pendingSwitch.asStateFlow()

    /** Increases with every new session or stop; events from older sessions are ignored. */
    private var token = 0
    private var running: Engine? = null
    private var stopping = 0
    private val afterStop = ArrayDeque<() -> Unit>()

    /** When the driver last stopped a session (uptime); see [onUsbPhone]. */
    private var driverStoppedAt = -REENUMERATION_WINDOW_MILLIS
    /** The engine of the USB session the driver last stopped; only its phone re-enumerates. */
    private var driverStoppedUsbEngine: Engine? = null

    /** Remembers a driver stop; only a USB session's stop makes its phone re-enumerate. */
    private fun markDriverStop(stopped: SessionState) {
        val active = stopped as? SessionState.Active
        driverStoppedUsbEngine = active?.request?.engine?.takeIf { active.request.transport == Transport.USB }
        driverStoppedAt = SystemClock.uptimeMillis()
    }

    private val notAnswering = Runnable {
        val current = _state.value as? SessionState.Active ?: return@Runnable
        val phase = current.phase as? SessionState.Phase.Progress ?: return@Runnable
        if (phase.stage != ProgressStage.WAITING_FOR_PHONE) return@Runnable
        note("${current.request.engine}: the phone has not answered for ${NOT_ANSWERING_MILLIS / 1000} s")
        _state.value = current.copy(phase = phase.copy(stage = ProgressStage.PHONE_NOT_ANSWERING))
    }

    private val usbGoneCheck = Runnable {
        val current = _state.value as? SessionState.Active ?: return@Runnable
        if (current.request.transport != Transport.USB) return@Runnable
        if (environment.usbPhonePresent(current.request.engine)) return@Runnable
        note("USB phone for ${current.request.engine} unplugged; ending the session")
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
        val current = _state.value as? SessionState.Active ?: return
        if (current.request.transport != Transport.USB) return
        main.removeCallbacks(usbGoneCheck)
        // While connecting, the engines re-enumerate the phone on purpose; once projecting they do
        // not, so an unplug then returns to Home almost at once (user request, 2026-10-09).
        val grace = if (current.phase == SessionState.Phase.Connected) USB_GONE_CONNECTED_GRACE_MILLIS else USB_GONE_GRACE_MILLIS
        main.postDelayed(usbGoneCheck, grace)
    }

    /** A paired phone's Bluetooth link came up. Never interrupts a running session. */
    fun onBluetoothPhone(address: String, kind: PhoneKind, phoneName: String?) {
        val engine = kind.engine ?: return
        when {
            !environment.autoConnectBluetooth -> note("Bluetooth $engine phone: automatic start is off")
            environment.autoConnectPaused -> note("Bluetooth $engine phone: paused by the driver")
            _state.value.isActive -> note("Bluetooth $engine phone: a ${_state.value.engine} session is running")
            else -> start(SessionRequest(engine, Transport.WIRELESS, address, phoneName))
        }
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
            now - lastHomeAutoStart < HOME_AUTO_START_COOLDOWN_MILLIS -> return
            else -> {
                lastHomeAutoStart = now
                note("Home shown: connecting the last phone ($engine)")
                start(SessionRequest(engine, Transport.WIRELESS, address, phoneName))
            }
        }
    }

    private var lastHomeAutoStart = -HOME_AUTO_START_COOLDOWN_MILLIS

    /** The driver asked for this phone. Clears the pause and replaces any running session. */
    fun connect(address: String?, kind: PhoneKind, phoneName: String?, transport: Transport = Transport.WIRELESS) {
        val engine = kind.engine ?: return
        environment.autoConnectPaused = false
        address?.let { environment.rememberPhone(it, kind) }
        start(SessionRequest(engine, transport, address, phoneName))
    }

    /** Try the last request again (Home's "Try again", or after the driver fixed a problem). */
    fun retry() {
        val request = when (val s = _state.value) {
            is SessionState.Blocked -> s.request
            is SessionState.Failed -> s.request
            else -> return
        }
        environment.autoConnectPaused = false
        start(request)
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
        token++
        main.removeCallbacks(notAnswering)
        main.removeCallbacks(usbGoneCheck)
        val engine = running
        _state.value = SessionState.Idle
        if (engine != null) {
            running = null
            environment.onEngaged(null)
            stopEngine(engine)
        }
    }

    // --- Sessions ------------------------------------------------------------------------------

    private fun start(request: SessionRequest) {
        note("start ${request.engine} ${request.transport} known=${request.phoneAddress != null}")
        stop()
        val paths = if (request.transport == Transport.WIRELESS) environment.wirelessPaths(request) else listOf(null)
        begin(request, paths, index = 0, token)
    }

    private fun begin(request: SessionRequest, paths: List<WirelessPath?>, index: Int, sessionToken: Int) {
        val path = paths[index]
        val problems = environment.preflight(request, path)
        if (problems.isNotEmpty()) {
            note("${request.engine} blocked on ${path ?: "USB"}: $problems")
            _state.value = SessionState.Blocked(request, problems)
            return
        }
        val plan = SessionPlan(request.engine, request.transport, path, request.phoneAddress, request.phoneName,
            request.usbAttach, attempt = index + 1)
        _state.value = SessionState.Active(request, path, index + 1, SessionState.Phase.Starting)
        running = request.engine
        environment.onEngaged(request.engine)
        main.removeCallbacks(usbGoneCheck)
        whenEnginesStopped {
            if (sessionToken != token) return@whenEnginesStopped
            worker.execute {
                runCatching { applySettings(plan) }.onFailure { Log.w(TAG, "could not apply settings", it) }
                main.post {
                    if (sessionToken != token) return@post
                    note("${plan.engine} starting on ${path ?: "USB"} (attempt ${plan.attempt})")
                    adapters.getValue(plan.engine).start(plan) { event -> main.post { onEvent(plan, paths, index, sessionToken, event) } }
                }
            }
        }
    }

    private fun onEvent(plan: SessionPlan, paths: List<WirelessPath?>, index: Int, sessionToken: Int, event: AdapterEvent) {
        if (sessionToken != token) return
        val current = _state.value as? SessionState.Active ?: return
        val request = current.request
        when (event) {
            is AdapterEvent.Progress -> {
                _state.value = current.copy(phase = SessionState.Phase.Progress(event.step, event.stepCount, event.stage))
                // A wireless phone that never answers: say so instead of a bar that looks busy.
                main.removeCallbacks(notAnswering)
                if (event.stage == ProgressStage.WAITING_FOR_PHONE && request.transport == Transport.WIRELESS) {
                    main.postDelayed(notAnswering, NOT_ANSWERING_MILLIS)
                }
            }
            is AdapterEvent.Retrying ->
                _state.value = current.copy(phase = SessionState.Phase.Retrying(event.attempt, event.reason))
            AdapterEvent.Connected -> {
                note("${plan.engine} connected on ${plan.path ?: "USB"}")
                if (plan.path != null && request.phoneAddress != null) {
                    environment.rememberWorkingPath(plan.engine, request.phoneAddress, plan.path)
                }
                request.phoneAddress?.let {
                    environment.rememberPhone(it, if (plan.engine == Engine.CARPLAY) PhoneKind.IPHONE else PhoneKind.ANDROID)
                }
                _state.value = current.copy(phase = SessionState.Phase.Connected)
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
                val next = index + 1
                if (event.reason.pathRelated && next < paths.size) {
                    note("${plan.engine} on ${plan.path} failed (${event.reason}${event.detail?.let { ": $it" } ?: ""}); trying ${paths[next]}")
                    // Same session for the driver: keep showing progress, not a failure.
                    _state.value = current.copy(phase = SessionState.Phase.Retrying(next + 1, event.reason))
                    val engine = plan.engine
                    running = null
                    stopEngine(engine)
                    begin(request, paths, next, sessionToken)
                } else {
                    note("${plan.engine} failed: ${event.reason}${event.detail?.let { " ($it)" } ?: ""}")
                    token++
                    running = null
                    environment.onEngaged(null)
                    stopEngine(plan.engine)
                    _state.value = SessionState.Failed(request, plan.path, event.reason, event.detail)
                }
            }
        }
    }

    private fun stopEngine(engine: Engine) {
        stopping++
        adapters.getValue(engine).stop {
            main.post {
                stopping--
                drainAfterStop()
            }
        }
    }

    /** Runs [action] once no engine is stopping. */
    private fun whenEnginesStopped(action: () -> Unit) {
        if (stopping == 0) action() else afterStop.addLast(action)
    }

    private fun drainAfterStop() {
        while (stopping == 0 && afterStop.isNotEmpty()) afterStop.removeFirst().invoke()
    }

    private fun note(message: String) {
        Log.i(TAG, message)
        environment.log(message)
    }

    companion object {
        private const val TAG = "CarLink-Session"

        /** Longer than CarPlay's configuration switch and Android Auto's accessory switch. */
        const val USB_GONE_GRACE_MILLIS = 3_500L

        /** How long a wireless phone may stay silent before Home says it is not answering. */
        const val NOT_ANSWERING_MILLIS = 10_000L

        /** A failed or cancelled automatic start is not retried every time Home reappears. */
        const val HOME_AUTO_START_COOLDOWN_MILLIS = 60_000L

        /** After the picture is up: long enough for a cable wiggle, short enough to feel instant. */
        const val USB_GONE_CONNECTED_GRACE_MILLIS = 1_200L

        /** How long USB attaches after the driver's Disconnect count as re-enumeration. */
        const val REENUMERATION_WINDOW_MILLIS = 10_000L
    }
}
