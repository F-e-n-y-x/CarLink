package com.carlink.app.engine

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.Transport
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.DiPlaySessionService
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayStatusBus

/**
 * Main-process handle on the CarPlay engine. Connecting goes through DiPlayActivity so DiPlay's
 * own checks run (authentication assets, car hotspot setup, iPhone choice, notification permission).
 */
internal class CarPlayEngine(context: Context, private val onState: (LinkState) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var transport: Transport? = null
    private var phoneName: String? = null

    private val listener: (CarPlayStatus?) -> Unit = { status -> main.post { onState(map(status)) } }

    init {
        CarPlayStatusBus.addListener(listener)
    }

    fun start(transport: Transport, phoneName: String?, usbAttach: Intent? = null) {
        this.transport = transport
        this.phoneName = phoneName
        onState(LinkState(LinkStage.WAITING, Engine.CARPLAY, transport, phoneName, 1, STEPS))
        val connect = Intent(app, DiPlayActivity::class.java)
            .putExtra(DiPlayActivity.EXTRA_CARLINK_CONNECT, if (transport == Transport.WIRELESS) "wireless" else "wired")
            // A page keeps DiPlay's "connect when opened" from starting a second connection.
            .putExtra("page", "home")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        usbAttach?.let { connect.putExtras(it) }
        app.startActivity(connect)
    }

    /** DiPlay's notification Disconnect: ends the session and its foreground service. */
    fun stop() {
        if (CarPlayStatusBus.current != null) {
            app.startService(Intent(app, DiPlaySessionService::class.java).setAction(DiPlaySessionService.ACTION_STOP))
        }
        onState(LinkState.Idle)
    }

    fun current(): LinkState = map(CarPlayStatusBus.current)

    private fun map(status: CarPlayStatus?): LinkState {
        val step = status?.let(::stepOf) ?: 0
        return when (status) {
            null, CarPlayStatus.ControlEnded -> LinkState.Idle
            is CarPlayStatus.Failed -> LinkState(LinkStage.FAILED, Engine.CARPLAY, transport, phoneName, detail = status.message)
            CarPlayStatus.RunningControl, CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback ->
                LinkState(LinkStage.CONNECTED, Engine.CARPLAY, transport, phoneName, STEPS, STEPS)
            CarPlayStatus.WaitingForIphone, CarPlayStatus.WaitingForPairedIphone, CarPlayStatus.WaitingForMfi ->
                LinkState(LinkStage.WAITING, Engine.CARPLAY, transport, phoneName, step, STEPS)
            else -> LinkState(LinkStage.CONNECTING, Engine.CARPLAY, transport, phoneName, step, STEPS)
        }
    }

    companion object {
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
