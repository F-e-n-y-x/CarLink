package com.carlink.app.connection

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.core.Transport
import com.carlink.app.settings.VehicleProfile
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.CarPlayHostIntegration
import com.shilapi.xcertplay.CarPlayHostIntegration.HostState
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayStatusBus
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives DiPlay for [SessionManager] (design/REBUILD_V2.md §4). A CarPlay session lives inside
 * `CarPlayHostActivity` (DIPLAY.md §0.1, §1.3): this opens it with the session's transport and
 * iPhone, follows DiPlay's statuses and host states through [CarPlaySessionTracker], and ends it
 * through `CarPlayHostIntegration.stopSession`, which completes only after DiPlay's teardown.
 *
 * It never reads `CarPlayStatusBus.current == null` as "no session" (F13): [hasSession] and the
 * end of a session come from the host hooks.
 *
 * After [AdapterEvent.Failed] or [AdapterEvent.Ended] the host may still be open (DiPlay keeps
 * retrying a hard failure, or shows its exhausted state): [SessionManager] calls [stop] next.
 */
class CarPlayAdapter internal constructor(
    private val hooks: CarPlayEngineHooks,
) : EngineAdapter {

    constructor(context: Context) : this(DiPlayEngineHooks(context.applicationContext))

    override val engine: Engine = Engine.CARPLAY

    private val main = Handler(Looper.getMainLooper())
    private var listener: ((AdapterEvent) -> Unit)? = null
    private var tracker: CarPlaySessionTracker? = null
    private var plan: SessionPlan? = null

    /** Why the last session failed or retried, for path decisions and diagnostics. */
    val lastFailure: CarPlayFailure? get() = tracker?.lastFailure

    private val statusListener: (CarPlayStatus?) -> Unit = { status -> main.post { onStatus(status) } }
    private val hostStateListener: (HostState) -> Unit = { state -> main.post { onHostState(state) } }

    override fun start(plan: SessionPlan, listener: (AdapterEvent) -> Unit) {
        require(plan.engine == Engine.CARPLAY) { "CarPlayAdapter cannot start ${plan.engine}" }
        if (hooks.hasSession()) {
            // Contract: start only after the previous stop completed. Never open over a closing
            // session: its late stopService would end the new one's foreground service (F14).
            Log.w(TAG, "start while a CarPlay session still runs; stopping it first")
            hooks.stopSession { start(plan, listener) }
            return
        }
        detach()
        val tracker = CarPlaySessionTracker(plan.transport)
        this.tracker = tracker
        this.plan = plan
        this.listener = listener
        hooks.addHostStateListener(hostStateListener)
        hooks.addStatusListener(statusListener)
        Log.i(TAG, "start transport=${plan.transport} path=${plan.path} attempt=${plan.attempt} " +
            "phone=${plan.phoneAddress != null} usb=${plan.usbAttach != null}")
        // DiPlay reads the transport and the iPhone from its preferences when the host opens (§6).
        if (plan.transport == Transport.WIRELESS && plan.phoneAddress != null) {
            hooks.selectPhone(plan.phoneAddress, plan.phoneName ?: plan.phoneAddress)
        }
        hooks.setWirelessEnabled(plan.transport == Transport.WIRELESS)
        deliver(listOf(tracker.started()))
        hooks.openHost(plan.usbAttach)?.let { error ->
            Log.w(TAG, "could not open CarPlay: ${error.javaClass.simpleName}")
            deliver(listOf(AdapterEvent.Failed(FailureReason.ENGINE, error.message)))
            this.listener = null
            detach()
        }
    }

    override fun stop(onStopped: () -> Unit) {
        // The caller asked: no more events for this session, only the completion.
        listener = null
        Log.i(TAG, "stop requested hasSession=${hooks.hasSession()}")
        hooks.stopSession {
            main.post {
                Log.i(TAG, "stopped")
                if (listener == null) detach()
                onStopped()
            }
        }
    }

    override fun hasSession(): Boolean = hooks.hasSession()

    private fun onStatus(status: CarPlayStatus?) {
        val tracker = tracker ?: return
        if (listener == null) return
        deliver(tracker.onStatus(status))
    }

    private fun onHostState(state: HostState) {
        val tracker = tracker ?: return
        if (listener == null) return
        Log.i(TAG, "host state $state")
        deliver(tracker.onHostState(state))
    }

    private fun deliver(events: List<AdapterEvent>) {
        for (event in events) {
            Log.i(TAG, "event $event")
            listener?.invoke(event)
        }
        if (tracker?.finished == true) {
            // Terminal: keep the tracker for lastFailure, stop listening.
            listener = null
            hooks.removeStatusListener(statusListener)
            hooks.removeHostStateListener(hostStateListener)
        }
    }

    private fun detach() {
        hooks.removeStatusListener(statusListener)
        hooks.removeHostStateListener(hostStateListener)
    }

    companion object {
        private const val TAG = "CarLink-CarPlay"

        /**
         * Installs CarLink's DiPlay hooks; call once from `CarLinkApplication.onCreate` in the main
         * process, before any CarPlay session: the car's name for the iPhone (iAP2 and Bonjour,
         * DIPLAY.md §3.5, §12), the vehicle profile's BYD gate (§12), the debug-log setting kept
         * across bootstraps (F6), CarLink's waiting view (F2) and the host-state fan-out.
         */
        fun install(context: Context, profile: VehicleProfile) {
            val app = context.applicationContext
            CarPlayHostIntegration.accessoryName = app.getString(R.string.app_name)
            CarPlayHostIntegration.resetDebugLogsOnBootstrap = false
            CarPlayHostIntegration.vehicleIntegrations = profile == VehicleProfile.BYD_DILINK
            CarPlayHostIntegration.onHostState = HostStates::dispatch
            CarPlayWaitingView.install()
            // A BYD restores a journaled cluster/HUD output at app start, as DiPlay's own home does
            // (DiPlayActivity.kt:318; §12 "CarLink never calls onAppOpened").
            if (profile == VehicleProfile.BYD_DILINK) runCatching { BydNavigationOutputs.onAppOpened(app) }
        }
    }
}

/** DiPlay as [CarPlayAdapter] uses it; [DiPlayEngineHooks] in production, fakes in tests. */
interface CarPlayEngineHooks {
    fun hasSession(): Boolean
    fun stopSession(completion: () -> Unit)
    fun addStatusListener(listener: (CarPlayStatus?) -> Unit)
    fun removeStatusListener(listener: (CarPlayStatus?) -> Unit)
    fun addHostStateListener(listener: (HostState) -> Unit)
    fun removeHostStateListener(listener: (HostState) -> Unit)
    fun selectPhone(address: String, name: String)
    fun setWirelessEnabled(enabled: Boolean)

    /** Opens the projection host; returns why it could not, or null. */
    fun openHost(usbAttach: Intent?): Throwable?
}

/** Fans DiPlay's single host-state hook out to every listener (adapter, waiting view). */
object HostStates {
    private val listeners = CopyOnWriteArrayList<(HostState) -> Unit>()

    fun add(listener: (HostState) -> Unit) { listeners.addIfAbsent(listener) }
    fun remove(listener: (HostState) -> Unit) { listeners.remove(listener) }

    internal fun dispatch(state: HostState) = listeners.forEach { it(state) }
}

internal class DiPlayEngineHooks(private val app: Context) : CarPlayEngineHooks {
    override fun hasSession(): Boolean = CarPlayHostIntegration.hasSession()

    override fun stopSession(completion: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        // CarPlayHostIntegration.stopSession works on the main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) CarPlayHostIntegration.stopSession(completion)
        else main.post { CarPlayHostIntegration.stopSession(completion) }
    }

    override fun addStatusListener(listener: (CarPlayStatus?) -> Unit) = CarPlayStatusBus.addListener(listener)
    override fun removeStatusListener(listener: (CarPlayStatus?) -> Unit) = CarPlayStatusBus.removeListener(listener)
    override fun addHostStateListener(listener: (HostState) -> Unit) = HostStates.add(listener)
    override fun removeHostStateListener(listener: (HostState) -> Unit) = HostStates.remove(listener)

    override fun selectPhone(address: String, name: String) = CarPlayHostIntegration.selectPhone(app, address, name)

    override fun setWirelessEnabled(enabled: Boolean) = AirPlayPersistence.saveWirelessEnabled(app, enabled)

    override fun openHost(usbAttach: Intent?): Throwable? {
        val host = Intent(app, CarPlayHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        // The host recognises a wired iPhone from the attach action and device extra and forces
        // wired (CarPlayHostActivity.isIphoneUsbAttachment; DIPLAY.md §2.4).
        usbAttach?.let { host.action = it.action; it.extras?.let(host::putExtras) }
        return runCatching { app.startActivity(host) }.exceptionOrNull()
    }
}
