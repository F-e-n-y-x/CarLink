package com.carlink.app.engine

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkProblem
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.Transport
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.CarPlayHostIntegration
import com.shilapi.xcertplay.DiPlaySessionService
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayStatusBus

/**
 * Main-process handle on the CarPlay engine. CarLink runs DiPlay's connect pre-flight itself
 * ([CarPlayPreflight]) and opens only the CarPlay picture ([CarPlayHostActivity]), which asks for
 * its own runtime permissions and VPN consent. DiPlay's home and settings screens never appear.
 */
internal class CarPlayEngine(context: Context, private val onState: (LinkState) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var transport: Transport? = null
    private var phoneName: String? = null

    /** A start waiting for the previous CarPlay session to finish closing. */
    private var pendingStart: Runnable? = null

    /**
     * Until this time (uptime), statuses come from a session CarLink asked to close. They are
     * shutdown noise: forwarding its ControlEnded or Failed would end or fail the attempt that
     * replaces it, and an active-looking one would re-adopt a session the driver just stopped.
     */
    private var closingUntil = 0L

    /** Fails an attempt whose CarPlay picture never reported anything (see [HOST_START_TIMEOUT_MILLIS]). */
    private val hostStartTimeout = Runnable {
        if (CarPlayStatusBus.current != null) return@Runnable
        Log.i(TAG, "CarPlay picture reported nothing within ${HOST_START_TIMEOUT_MILLIS / 1000} s")
        onState(LinkState(LinkStage.FAILED, Engine.CARPLAY, transport, phoneName, problem = LinkProblem.ENGINE))
    }

    private val listener: (CarPlayStatus?) -> Unit = { status -> main.post { onStatus(status) } }

    init {
        CarPlayStatusBus.addListener(listener)
    }

    private fun onStatus(status: CarPlayStatus?) {
        if (status != null) main.removeCallbacks(hostStartTimeout)
        if (status == null) {
            closingUntil = 0L
            val start = pendingStart
            if (start != null) {
                // The old session is closed: open the new one, which keeps its own WAITING state.
                pendingStart = null
                main.removeCallbacks(start)
                start.run()
                return
            }
        }
        if (pendingStart != null || SystemClock.uptimeMillis() < closingUntil) {
            Log.i(TAG, "ignoring status of the closing session: ${status?.javaClass?.simpleName}")
            return
        }
        onState(map(status))
    }

    fun start(transport: Transport, iphoneAddress: String?, phoneName: String?, usbAttach: Intent? = null) {
        this.transport = transport
        this.phoneName = phoneName
        CarPlayPreflight.check(app, transport, iphoneAddress)?.let { problem ->
            Log.i(TAG, "pre-flight: $problem")
            onState(LinkState(LinkStage.FAILED, Engine.CARPLAY, transport, phoneName, problem = problem))
            return
        }
        onState(LinkState(LinkStage.WAITING, Engine.CARPLAY, transport, phoneName, 1, STEPS))
        if (transport == Transport.WIRELESS && iphoneAddress != null) {
            CarPlayHostIntegration.selectPhone(app, iphoneAddress, phoneName ?: iphoneAddress)
        }
        AirPlayPersistence.saveWirelessEnabled(app, transport == Transport.WIRELESS)
        pendingStart?.let { main.removeCallbacks(it) }
        pendingStart = null
        val open = Runnable { openHost(usbAttach) }
        if (CarPlayStatusBus.current == null) {
            open.run()
        } else {
            // One CarPlay session at a time: close the running one, then open when it reports closed.
            Log.i(TAG, "closing the running CarPlay session before starting $transport")
            pendingStart = open
            requestStop()
            main.postDelayed({ if (pendingStart === open) { pendingStart = null; open.run() } }, STOP_TIMEOUT_MILLIS)
        }
    }

    /** DiPlay's notification Disconnect: ends the session and its foreground service. */
    fun stop() {
        pendingStart?.let { main.removeCallbacks(it) }
        pendingStart = null
        main.removeCallbacks(hostStartTimeout)
        if (CarPlayStatusBus.current != null) closingUntil = SystemClock.uptimeMillis() + STOP_TIMEOUT_MILLIS
        requestStop()
        onState(LinkState.Idle)
    }

    fun current(): LinkState = map(CarPlayStatusBus.current)

    private fun requestStop() {
        if (CarPlayStatusBus.current == null) return
        runCatching {
            app.startService(Intent(app, DiPlaySessionService::class.java).setAction(DiPlaySessionService.ACTION_STOP))
        }.onFailure { Log.w(TAG, "stop refused: ${it.javaClass.simpleName}") }
    }

    private fun openHost(usbAttach: Intent?) {
        closingUntil = 0L
        Log.i(TAG, "opening the CarPlay picture transport=$transport wiredAttach=${usbAttach != null}")
        main.removeCallbacks(hostStartTimeout)
        main.postDelayed(hostStartTimeout, HOST_START_TIMEOUT_MILLIS)
        val host = Intent(app, CarPlayHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        // The host recognises a wired iPhone from the attach action and device extra.
        usbAttach?.let { host.action = it.action; it.extras?.let(host::putExtras) }
        runCatching { app.startActivity(host) }.onFailure {
            main.removeCallbacks(hostStartTimeout)
            Log.w(TAG, "could not open CarPlay: ${it.javaClass.simpleName}")
            onState(LinkState(LinkStage.FAILED, Engine.CARPLAY, transport, phoneName,
                detail = it.message, problem = LinkProblem.ENGINE))
        }
    }

    private fun map(status: CarPlayStatus?): LinkState {
        val step = status?.let(::stepOf) ?: 0
        return when (status) {
            null, CarPlayStatus.ControlEnded -> LinkState.Idle
            is CarPlayStatus.Failed -> LinkState(LinkStage.FAILED, Engine.CARPLAY, transport, phoneName,
                detail = status.message, problem = LinkProblem.ENGINE)
            CarPlayStatus.RunningControl, CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback ->
                LinkState(LinkStage.CONNECTED, Engine.CARPLAY, transport, phoneName, STEPS, STEPS)
            CarPlayStatus.WaitingForIphone, CarPlayStatus.WaitingForPairedIphone, CarPlayStatus.WaitingForMfi ->
                LinkState(LinkStage.WAITING, Engine.CARPLAY, transport, phoneName, step, STEPS)
            else -> LinkState(LinkStage.CONNECTING, Engine.CARPLAY, transport, phoneName, step, STEPS)
        }
    }

    companion object {
        private const val TAG = "CarLink-CarPlay"
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * The picture asks for runtime permissions and VPN consent before its controller reports
         * a first status, so this is generous; a picture that crashed or closed at once would
         * otherwise leave the driver on "Waiting" for good. A late status still recovers.
         */
        private const val HOST_START_TIMEOUT_MILLIS = 60_000L

        /** Prepare, find the iPhone, link (USB/Wi-Fi), pair and authenticate, start CarPlay, projecting. */
        private const val STEPS = 6

        private fun stepOf(status: CarPlayStatus): Int = when (status) {
            CarPlayStatus.DiscoveringMfi, CarPlayStatus.WaitingForMfi,
            CarPlayStatus.RequestingMfiPermission, CarPlayStatus.MfiReady -> 1
            CarPlayStatus.DiscoveringIphone, CarPlayStatus.WaitingForIphone,
            CarPlayStatus.WaitingForPairedIphone, CarPlayStatus.RequestingIphonePermission -> 2
            CarPlayStatus.StartingHotspot, is CarPlayStatus.HotspotReady, CarPlayStatus.ConnectingBluetooth,
            CarPlayStatus.WaitingForReenumeration, CarPlayStatus.SelectingConfiguration,
            CarPlayStatus.OpeningDataPaths -> 3
            CarPlayStatus.Pairing, CarPlayStatus.RunningWireless, CarPlayStatus.AttachingNetwork -> 4
            CarPlayStatus.ConnectingControl -> 5
            CarPlayStatus.RunningControl, CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback -> 6
            CarPlayStatus.ControlEnded, is CarPlayStatus.Failed -> 0
        }
    }
}
