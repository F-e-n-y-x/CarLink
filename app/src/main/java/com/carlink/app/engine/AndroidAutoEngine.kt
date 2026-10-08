package com.carlink.app.engine

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
internal class AndroidAutoEngine(
    context: Context,
    private val onState: (LinkState) -> Unit,
    // Unit tests record commands instead of running DiAuto's receiver; production broadcasts them.
    sendCommand: ((Intent) -> Unit)? = null,
) {
    private val app = context.applicationContext
    private val sendCommand: (Intent) -> Unit = sendCommand ?: app::sendBroadcast
    private val handler = Handler(Looper.getMainLooper())
    private var transport: Transport? = null
    private var phoneName: String? = null
    private var engaged = false
    private var lastServiceRunning = false
    private var lastConnection: String? = null

    /** When the last start was sent (elapsed realtime); see [START_GRACE_MILLIS]. */
    private var startedAt = 0L

    /** Consecutive polls that found no ":aa" process. */
    private var deadPolls = 0

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
            // A report request would start ":aa" again just to hear that nothing runs there.
            // The engine's process can die on its own (DiAuto's "close app on disconnect" exits it).
            if (!isProcessAlive() && !starting()) {
                if (++deadPolls >= DEAD_POLLS) {
                    Log.i(TAG, "Android Auto process is gone; session over")
                    disengage()
                    onState(LinkState.Idle)
                    return
                }
            } else {
                deadPolls = 0
                send(P.COMMAND_REPORT)
            }
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
        startedAt = SystemClock.elapsedRealtime()
        lastConnection = null
        engage()
        Log.i(TAG, "start transport=$transport phone=${address != null} usbDevice=${usbDevice != null}")
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
        deadPolls = 0
        handler.postDelayed(poll, POLL_MILLIS)
    }

    private fun disengage() {
        engaged = false
        handler.removeCallbacks(poll)
    }

    /**
     * Right after a start, ":aa" stops a stuck AapService, removes a leftover Wi-Fi Direct group
     * (up to ~6 s) and only then starts the service. A report in that window says "not running";
     * it is the start in progress, not the end of the session.
     */
    private fun starting() = SystemClock.elapsedRealtime() - startedAt < START_GRACE_MILLIS

    private fun publish(connection: String, message: String?) {
        if (!engaged) return
        if (connection != lastConnection) {
            Log.i(TAG, "engine state $connection serviceRunning=$lastServiceRunning${message?.let { " ($it)" } ?: ""}")
            lastConnection = connection
        }
        val step = CONNECTION_STEPS.indexOf(connection)
        val state = when {
            connection == P.CONNECTION_TRANSPORT_STARTED ->
                LinkState(LinkStage.CONNECTED, Engine.ANDROID_AUTO, transport, phoneName, STEPS, STEPS)
            connection == P.CONNECTION_ERROR ->
                LinkState(LinkStage.FAILED, Engine.ANDROID_AUTO, transport, phoneName, detail = message,
                    problem = com.carlink.app.core.LinkProblem.ENGINE)
            step > 0 -> LinkState(LinkStage.CONNECTING, Engine.ANDROID_AUTO, transport, phoneName, step + 1, STEPS)
            lastServiceRunning || starting() -> LinkState(LinkStage.WAITING, Engine.ANDROID_AUTO, transport, phoneName, 1, STEPS)
            else -> {
                Log.i(TAG, "AapService is not running; session over")
                disengage()
                LinkState.Idle
            }
        }
        onState(state)
    }

    private fun send(command: String, extras: Intent.() -> Unit = {}) {
        sendCommand(Intent(P.ACTION_COMMAND)
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
        private const val TAG = "CarLink-AAEngine"
        private const val POLL_MILLIS = 3_000L
        private const val START_GRACE_MILLIS = 15_000L
        private const val DEAD_POLLS = 2
        private val CONNECTION_STEPS = listOf(
            P.CONNECTION_DISCONNECTED, P.CONNECTION_CONNECTING, P.CONNECTION_CONNECTED,
            P.CONNECTION_STARTING_TRANSPORT, P.CONNECTION_HANDSHAKE_COMPLETE, P.CONNECTION_TRANSPORT_STARTED,
        )
        /** Waiting, link, secure handshake, starting picture, projecting. */
        private const val STEPS = 6
    }
}
