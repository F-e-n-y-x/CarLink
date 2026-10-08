package com.carlink.app.engine

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.Transport
import com.carlink.app.engine.AndroidAutoProtocol as P

/**
 * Main-process handle on the Android Auto engine in the ":aa" process. Commands go to
 * [AndroidAutoCommandReceiver]; state comes back from [AndroidAutoStateReporter].
 */
internal class AndroidAutoEngine(context: Context, private val onState: (LinkState) -> Unit) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var transport: Transport? = null
    private var phoneName: String? = null
    private var engaged = false
    private var lastServiceRunning = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connection = intent.getStringExtra(P.EXTRA_CONNECTION) ?: return
            lastServiceRunning = intent.getBooleanExtra(P.EXTRA_SERVICE_RUNNING, false)
            publish(connection, intent.getStringExtra(P.EXTRA_MESSAGE))
        }
    }

    // AapService start/stop does not change the connection state, so poll while engaged.
    private val poll = object : Runnable {
        override fun run() {
            if (!engaged) return
            send(P.COMMAND_REPORT)
            handler.postDelayed(this, POLL_MILLIS)
        }
    }

    init {
        ContextCompat.registerReceiver(app, stateReceiver, IntentFilter(P.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun start(transport: Transport, address: String?, phoneName: String?, usbDevice: UsbDevice? = null) {
        this.transport = transport
        this.phoneName = phoneName
        engage()
        onState(LinkState(LinkStage.WAITING, Engine.ANDROID_AUTO, transport, phoneName, 1, STEPS))
        when (transport) {
            Transport.USB -> send(P.COMMAND_START_USB) { usbDevice?.let { putExtra(P.EXTRA_USB_DEVICE, it) } }
            Transport.WIRELESS -> send(P.COMMAND_START_WIRELESS) { putExtra(P.EXTRA_ADDRESS, address) }
        }
    }

    /** Ends the projection but keeps DiAuto's listeners armed, as its own Disconnect does. */
    fun disconnect() {
        if (isProcessAlive()) send(P.COMMAND_DISCONNECT)
    }

    /** Stops AapService completely: car mode off, wireless stack and MediaSession released. */
    fun stop() {
        disengage()
        if (isProcessAlive()) send(P.COMMAND_STOP)
        onState(LinkState.Idle)
    }

    /** Re-syncs with an engine that may have been started before this process. */
    fun refresh() {
        if (isProcessAlive()) {
            engage()
            send(P.COMMAND_REPORT)
        }
    }

    private fun engage() {
        if (engaged) return
        engaged = true
        handler.postDelayed(poll, POLL_MILLIS)
    }

    private fun disengage() {
        engaged = false
        handler.removeCallbacks(poll)
    }

    private fun publish(connection: String, message: String?) {
        if (!engaged) return
        val step = CONNECTION_STEPS.indexOf(connection)
        val state = when {
            connection == P.CONNECTION_TRANSPORT_STARTED ->
                LinkState(LinkStage.CONNECTED, Engine.ANDROID_AUTO, transport, phoneName, STEPS, STEPS)
            connection == P.CONNECTION_ERROR ->
                LinkState(LinkStage.FAILED, Engine.ANDROID_AUTO, transport, phoneName, detail = message,
                    problem = com.carlink.app.core.LinkProblem.ENGINE)
            step > 0 -> LinkState(LinkStage.CONNECTING, Engine.ANDROID_AUTO, transport, phoneName, step + 1, STEPS)
            lastServiceRunning -> LinkState(LinkStage.WAITING, Engine.ANDROID_AUTO, transport, phoneName, 1, STEPS)
            else -> {
                disengage()
                LinkState.Idle
            }
        }
        onState(state)
    }

    private fun send(command: String, extras: Intent.() -> Unit = {}) {
        app.sendBroadcast(Intent(P.ACTION_COMMAND)
            .setClass(app, AndroidAutoCommandReceiver::class.java)
            .putExtra(P.EXTRA_COMMAND, command)
            .apply(extras))
    }

    private fun isProcessAlive(): Boolean {
        val processName = app.packageName + P.ENGINE_PROCESS_SUFFIX
        return app.getSystemService(ActivityManager::class.java)?.runningAppProcesses
            ?.any { it.processName == processName } == true
    }

    companion object {
        private const val POLL_MILLIS = 3_000L
        private val CONNECTION_STEPS = listOf(
            P.CONNECTION_DISCONNECTED, P.CONNECTION_CONNECTING, P.CONNECTION_CONNECTED,
            P.CONNECTION_STARTING_TRANSPORT, P.CONNECTION_HANDSHAKE_COMPLETE, P.CONNECTION_TRANSPORT_STARTED,
        )
        /** Waiting, link, secure handshake, starting picture, projecting. */
        private const val STEPS = 6
    }
}
