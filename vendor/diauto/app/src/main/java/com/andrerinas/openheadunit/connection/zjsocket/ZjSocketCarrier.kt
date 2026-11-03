// CarLink: ported from open-headunit 754e6c01 (PR #1168, AGPL-3.0), connection/wifi/modes/nativeaa/
// zjsocket/ZjSocketCarrier.kt (see docs/VENDOR_CHANGES.md). One hooks object instead of upstream's
// lambdas (isFinishedForSession is deliberately not read: the carrier serves every drive); no
// join-refusal widening, no module-address seeding and no WiFi-button "dial now" (none exists in
// this DiAuto). CarLink: three refused connects in a row are reported to the host, as for ZBT/BLINK.
package com.andrerinas.openheadunit.connection.zjsocket

import android.net.LocalSocket
import com.andrerinas.openheadunit.aap.WirelessTimeline
import com.andrerinas.openheadunit.connection.ExternalBtTransportPolicy
import com.andrerinas.openheadunit.connection.ExternalModuleCarrier
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

/**
 * Dials the ZJ daemon's socket and serves each link until the session ends; it sends no wake.
 * It stops only when the manager does. [ExternalBtTransportPolicy.Hooks.onPhoneEvidence] runs at
 * each connect so silent attempts in an empty car never reach the handshake hard stop.
 */
class ZjSocketCarrier(
    private val hooks: ExternalBtTransportPolicy.Hooks,
    private val socketPath: String = ZjSocketPolicy.SOCKET_PATH,
) : ExternalModuleCarrier {

    companion object {
        const val POLL_INTERVAL_MS = 2_000L

        /** CarLink: refused connects in a row before the host is told the module cannot be reached. */
        const val REPORT_AFTER_REFUSALS = 3
    }

    @Volatile private var stopped = false
    @Volatile private var activeSocket: LocalSocket? = null
    @Volatile private var carrierJob: Job? = null
    @Volatile private var wakeNoteLogged = false

    private var attempts = 0
    private var refusedConnects = 0
    private var reportedUnavailable = false

    /** The daemon wakes the phone on its own; nothing is sent. */
    override fun requestWake() {
        if (!wakeNoteLogged) {
            wakeNoteLogged = true
            AppLog.i("NativeAA: [ZJ] this route sends no wake; the module brings the phone's link up by itself.")
        }
    }

    override suspend fun run() {
        carrierJob = currentCoroutineContext()[Job]
        AppLog.i("NativeAA: [ZJ] Carrier started, watching $socketPath")
        try {
            serveUntilStopped()
        } finally {
            AppLog.i("NativeAA: [ZJ] Carrier stopped after $attempts attempts.")
        }
    }

    private suspend fun serveUntilStopped() {
        var socketMissingLogged = false
        while (true) {
            when (ZjSocketPolicy.decide(running(), hooks.isSessionConnected(), hooks.isHandshakeInFlight(),
                hooks.isSettling(), hooks.mayServeHandshake())) {
                ZjSocketPolicy.Action.STOP -> break
                ZjSocketPolicy.Action.WAIT -> { delay(POLL_INTERVAL_MS); continue }
                ZjSocketPolicy.Action.DIAL -> Unit
            }
            if (!File(socketPath).exists()) {
                if (!socketMissingLogged) {
                    AppLog.w("NativeAA: [ZJ] Socket $socketPath does not exist yet. Waiting...")
                    socketMissingLogged = true
                }
                delay(POLL_INTERVAL_MS)
                continue
            }
            socketMissingLogged = false

            val attempt = ZjSocketConnector.connect(socketPath)
            val socket = attempt.socket
            if (socket == null) {
                refusedConnects++
                val gap = ZjSocketPolicy.retryDelayMs(afterServed = false, count = refusedConnects)
                val line = "NativeAA: [ZJ] connect to $socketPath failed (${attempt.outcome}: ${attempt.detail}); next try in ${gap}ms"
                if (refusedConnects == 1) AppLog.i(line) else AppLog.d(line)
                if (refusedConnects >= REPORT_AFTER_REFUSALS && !reportedUnavailable) {
                    reportedUnavailable = true
                    ExternalBtTransportPolicy.reportUnavailable(ExternalBtTransportPolicy.Route.ZJ_SOCKET,
                        "$socketPath does not accept a connect (${attempt.outcome})")
                }
                delay(gap)
                continue
            }
            refusedConnects = 0
            attempts++
            activeSocket = socket
            AppLog.i("NativeAA: [ZJ] Connected to $socketPath (attempt #$attempts)")
            ExternalBtTransportPolicy.step(WirelessTimeline.Phase.MODULE_REACHABLE, "zj socket $socketPath connected")
            hooks.onPhoneEvidence()
            try {
                hooks.serve(ZjSocketHandshakeLink(socket))
            } catch (e: CancellationException) {
                // A session-end rearm cancels the handshake's own job, not this one.
                if (ZjSocketPolicy.cancellationEndsCarrier(stopped, currentCoroutineContext().isActive)) throw e
                AppLog.i("NativeAA: [ZJ] handshake was cancelled; the carrier keeps watching for the next drive.")
            } catch (e: Exception) {
                AppLog.w("NativeAA: [ZJ] handshake ended with ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                ZjSocketConnector.close(socket)
                activeSocket = null
            }
            delay(ZjSocketPolicy.retryDelayMs(afterServed = true, count = 0))
        }
    }

    override fun close() {
        stopped = true
        activeSocket?.let { ZjSocketConnector.close(it) }
    }

    private fun running(): Boolean = !stopped && hooks.isRunning() && carrierJob?.isActive != false
}
