package com.carlink.app.connection

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.carlink.app.connection.aa.AaCommandReceiver
import com.carlink.app.connection.aa.AaProtocol
import com.carlink.app.connection.aa.AaSessionTracker
import com.carlink.app.core.Engine
import com.carlink.app.core.Transport
import com.carlink.app.settings.VehicleProfile
import java.util.concurrent.atomic.AtomicLong

/**
 * Android Auto (DiAuto in the ":aa" process) behind [EngineAdapter]. Main process, main thread.
 *
 * Commands go to [AaCommandReceiver]; CommManager states, engine events and stop acknowledgements
 * come back as package broadcasts ([AaProtocol]). [AaSessionTracker] turns them into
 * [AdapterEvent]s. The session's settings are written beforehand by [SessionManager] through
 * [com.carlink.app.connection.aa.AaSessionSettings].
 *
 * [hasSession] is true from [start] until ":aa" acknowledges the stop: AapService gone and Android
 * Auto's Wi-Fi Direct group released. When ":aa" dies under a session the session fails and stays
 * open until [stop] runs the same cleanup in a fresh ":aa" (the group can outlive its process, and
 * CarPlay's Wi-Fi Direct would find the radio taken). A main process started while ":aa" still
 * runs an engine reports [hasSession] true until a state report says AapService is not running.
 */
class AndroidAutoAdapter internal constructor(
    context: Context,
    private val vehicleProfile: () -> VehicleProfile,
    sendCommand: ((Intent) -> Unit)?,
    processAlive: (() -> Boolean)?,
    private val clock: () -> Long,
    private val limits: AaSessionTracker.Limits,
    private val stopTimeoutMillis: Long,
) : EngineAdapter {

    /** [vehicleProfile] gates BYD-only engine behaviour per session (DA§12). */
    constructor(context: Context, vehicleProfile: () -> VehicleProfile = { VehicleProfile.GENERIC }) :
        this(context, vehicleProfile, null, null, SystemClock::elapsedRealtime, AaSessionTracker.Limits(), STOP_TIMEOUT_MILLIS)

    override val engine = Engine.ANDROID_AUTO

    private val app = context.applicationContext
    private val sendCommand: (Intent) -> Unit = sendCommand ?: app::sendBroadcast
    private val processAlive: () -> Boolean = processAlive ?: ::isEngineProcessAlive
    private val handler = Handler(Looper.getMainLooper())

    private class Session(val plan: SessionPlan, val listener: (AdapterEvent) -> Unit, val tracker: AaSessionTracker, val startedAt: Long)

    private var session: Session? = null
    private var sessionOpen = false
    /** A main process that found ":aa" alive at start-up: open until a report says the service is not running. */
    private var orphanCheck = false
    private var stopToken: Long? = null
    private val stopCallbacks = mutableListOf<() -> Unit>()
    private var deadPolls = 0

    private val stateReceiver = receiver { intent ->
        val connection = intent.getStringExtra(AaProtocol.EXTRA_CONNECTION) ?: return@receiver
        val running = intent.getBooleanExtra(AaProtocol.EXTRA_SERVICE_RUNNING, false)
        if (orphanCheck && session == null && stopToken == null) {
            orphanCheck = false
            if (!running) sessionOpen = false
            Log.i(TAG, "engine found at start-up: service running=$running connection=$connection")
        }
        val s = session ?: return@receiver
        deliver(s, s.tracker.onState(connection, intent.getStringExtra(AaProtocol.EXTRA_MESSAGE), running,
            intent.getBooleanExtra(AaProtocol.EXTRA_FIRST_FRAME, false), clock()))
    }

    private val eventReceiver = receiver { intent ->
        val kind = intent.getStringExtra(AaProtocol.EXTRA_KIND) ?: return@receiver
        val detail = intent.getStringExtra(AaProtocol.EXTRA_DETAIL)
        Log.i(TAG, "engine event $kind${detail?.let { " ($it)" } ?: ""}")
        val s = session ?: return@receiver
        deliver(s, s.tracker.onEvent(kind, detail, clock()))
    }

    private val stoppedReceiver = receiver { intent ->
        val token = intent.getLongExtra(AaProtocol.EXTRA_TOKEN, -1)
        if (token != stopToken) return@receiver
        val group = if (intent.hasExtra(AaProtocol.EXTRA_GROUP_RELEASED)) intent.getBooleanExtra(AaProtocol.EXTRA_GROUP_RELEASED, false) else null
        val service = intent.getBooleanExtra(AaProtocol.EXTRA_SERVICE_STOPPED, false)
        if (!service || group == false) Log.w(TAG, "stop finished with serviceStopped=$service groupReleased=$group")
        finishStop("acknowledged (serviceStopped=$service groupReleased=$group)")
    }

    private val tick = object : Runnable {
        override fun run() {
            val s = session ?: return
            if (s.tracker.terminal) return
            val now = clock()
            deliver(s, s.tracker.onTick(now))
            if (s.tracker.terminal) return
            if (s.tracker.shouldRepoke(now)) s.plan.phoneAddress?.let { send(AaProtocol.COMMAND_POKE) { putExtra(AaProtocol.EXTRA_ADDRESS, it) } }
            // The receiver starts ":aa" a moment after start(); after that, two misses in a row mean it died.
            if (now - s.startedAt >= PROCESS_GRACE_MILLIS && !processAlive()) {
                if (++deadPolls >= DEAD_POLLS) {
                    Log.w(TAG, "the Android Auto process died under the session")
                    deliver(s, s.tracker.onProcessDied())
                    return
                }
            } else {
                deadPolls = 0
            }
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    private val stopTimeout = Runnable {
        Log.w(TAG, "no stop acknowledgement from the Android Auto process in ${stopTimeoutMillis}ms; releasing anyway")
        finishStop("timed out")
    }

    private val orphanTimeout = Runnable {
        if (orphanCheck && session == null && stopToken == null) {
            Log.i(TAG, "no report from the Android Auto process at start-up; nothing runs there")
            orphanCheck = false
            sessionOpen = false
        }
    }

    init {
        listOf(stateReceiver to AaProtocol.ACTION_STATE, eventReceiver to AaProtocol.ACTION_EVENT,
            stoppedReceiver to AaProtocol.ACTION_STOPPED).forEach { (r, action) ->
            ContextCompat.registerReceiver(app, r, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        if (this.processAlive()) {
            // An engine may have outlived the main process; ask before claiming nothing runs.
            sessionOpen = true
            orphanCheck = true
            send(AaProtocol.COMMAND_REPORT)
            handler.postDelayed(orphanTimeout, ORPHAN_REPORT_MILLIS)
        }
    }

    override fun hasSession(): Boolean = sessionOpen

    override fun start(plan: SessionPlan, listener: (AdapterEvent) -> Unit) {
        check(!sessionOpen) { "Android Auto start while the previous session has not stopped" }
        check(plan.engine == Engine.ANDROID_AUTO) { "not an Android Auto plan: ${plan.engine}" }
        val now = clock()
        val tracker = AaSessionTracker(plan.transport, limits)
        val s = Session(plan, listener, tracker, now)
        session = s
        sessionOpen = true
        orphanCheck = false
        deadPolls = 0
        Log.i(TAG, "start transport=${plan.transport} path=${plan.path} phone=${plan.phoneAddress != null} attempt=${plan.attempt}")
        deliver(s, tracker.begin(now))
        val profile = vehicleProfile().name
        when (plan.transport) {
            Transport.WIRELESS -> send(AaProtocol.COMMAND_START_WIRELESS) {
                putExtra(AaProtocol.EXTRA_ADDRESS, plan.phoneAddress)
                putExtra(AaProtocol.EXTRA_VEHICLE_PROFILE, profile)
            }
            Transport.USB -> send(AaProtocol.COMMAND_START_USB) {
                putExtra(AaProtocol.EXTRA_ADDRESS, plan.phoneAddress)
                putExtra(AaProtocol.EXTRA_VEHICLE_PROFILE, profile)
                plan.usbAttach?.let(::usbDevice)?.let { putExtra(AaProtocol.EXTRA_USB_DEVICE, it) }
            }
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MILLIS)
    }

    override fun stop(onStopped: () -> Unit) {
        if (!sessionOpen) {
            handler.post { onStopped() }
            return
        }
        session?.tracker?.close()
        handler.removeCallbacks(tick)
        stopCallbacks += onStopped
        if (stopToken != null) return // a stop is already on its way
        val token = nextToken.incrementAndGet()
        stopToken = token
        orphanCheck = false
        Log.i(TAG, "stop requested")
        send(AaProtocol.COMMAND_STOP) { putExtra(AaProtocol.EXTRA_TOKEN, token) }
        handler.postDelayed(stopTimeout, stopTimeoutMillis)
    }

    /** Unregisters the receivers (tests, or an adapter that is replaced). */
    fun release() {
        handler.removeCallbacksAndMessages(null)
        listOf(stateReceiver, eventReceiver, stoppedReceiver).forEach { runCatching { app.unregisterReceiver(it) } }
    }

    private fun finishStop(how: String) {
        handler.removeCallbacks(stopTimeout)
        handler.removeCallbacks(tick)
        stopToken = null
        session = null
        sessionOpen = false
        Log.i(TAG, "stopped: $how")
        val callbacks = stopCallbacks.toList()
        stopCallbacks.clear()
        callbacks.forEach { it() }
    }

    private fun deliver(s: Session, events: List<AdapterEvent>) {
        if (session !== s || stopToken != null) return
        events.forEach { event ->
            Log.i(TAG, "-> $event")
            s.listener(event)
        }
    }

    private fun send(command: String, extras: Intent.() -> Unit = {}) {
        sendCommand(Intent(AaProtocol.ACTION_COMMAND)
            .setClass(app, AaCommandReceiver::class.java)
            .putExtra(AaProtocol.EXTRA_COMMAND, command)
            .apply(extras))
    }

    private fun usbDevice(attach: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        attach.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION") attach.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private fun isEngineProcessAlive(): Boolean {
        val name = app.packageName + AaProtocol.ENGINE_PROCESS_SUFFIX
        return app.getSystemService(ActivityManager::class.java)?.runningAppProcesses
            ?.any { it.processName == name } == true
    }

    private fun receiver(onReceive: (Intent) -> Unit) = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onReceive(intent)
    }

    internal companion object {
        private const val TAG = "CarLink-AAAdapter"
        const val TICK_MILLIS = 1_000L
        /** Covers AapService's stop (6 s), the group release wait (4 s) and the removal in ":aa". */
        const val STOP_TIMEOUT_MILLIS = 20_000L
        const val PROCESS_GRACE_MILLIS = 5_000L
        const val DEAD_POLLS = 3
        const val ORPHAN_REPORT_MILLIS = 5_000L
        private val nextToken = AtomicLong(System.nanoTime())
    }
}
