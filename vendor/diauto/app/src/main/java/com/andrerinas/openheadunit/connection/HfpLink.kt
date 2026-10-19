// CarLink: ported from open-headunit c86332be (AGPL-3.0), NativeAaHandshakeManager.serveHfpSocket,
// over plain streams so the AT exchange is unit-tested (see docs/VENDOR_CHANGES.md).
package com.andrerinas.openheadunit.connection

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

/**
 * Serves one hands-free channel: answers the phone and, when asked, opens the service level
 * connection itself. A phone will not start wireless Android Auto against a head unit that is merely
 * ACL-connected (Android Auto 17.8: `WIRELESS_SETUP_FAILED_TO_START_NO_HFP_FROM_HU_PRESENCE`); it
 * wants a hands-free profile that reached connected, which only happens once this exchange finishes.
 * [HfpSlcInitiator] holds the walk, [HfpAtResponder] the answers.
 */
internal object HfpLink {

    /** How far one channel got, readable after it ends (the caller cancels it). */
    class Progress {
        @Volatile var stage = HfpSlcInitiator.Stage.IDLE
        /** AT commands this side sent in the walk; answers and keepalives not counted. */
        @Volatile var commandsSent = 0
    }

    /**
     * Runs until [isOpen] turns false, the stream ends, or the caller cancels. Throws the stream's
     * own IOException. [onEstablished] runs once, when the link is up; a keepalive then reads the
     * indicators every [HfpSlcInitiator.KEEPALIVE_INTERVAL_MS].
     */
    suspend fun serve(
        input: InputStream,
        output: OutputStream,
        initiate: Boolean,
        isOpen: () -> Boolean,
        progress: Progress = Progress(),
        log: (String) -> Unit = {},
        onEstablished: () -> Unit = {},
    ) = coroutineScope {
        // The keepalive writes to this same stream from its own coroutine, and two interleaved
        // partial writes on one RFCOMM channel is a corrupt command line.
        val writeLock = Any()
        fun write(lines: List<String>) {
            if (lines.isEmpty()) return
            synchronized(writeLock) {
                lines.forEach { output.write(it.toByteArray(Charsets.US_ASCII)) }
                output.flush()
            }
            log("TX: ${lines.joinToString("|") { it.trim() }}")
        }
        fun take(step: HfpSlcInitiator.Step) {
            progress.stage = step.stage
            // Answers are framed with a leading CRLF; our own commands are not.
            progress.commandsSent += step.writes.count { !it.startsWith("\r\n") }
            write(step.writes)
        }

        var keepAlive: Job? = null
        try {
            if (initiate) take(HfpSlcInitiator.open())
            val buf = ByteArray(1024)
            while (isActive && isOpen()) {
                if (input.available() > 0) {
                    val read = input.read(buf)
                    if (read == -1) break
                    val buffer = String(buf, 0, read, Charsets.US_ASCII)
                    HfpAtResponder.split(buffer).forEach { log("RX: $it") }
                    val step = HfpSlcInitiator.onReceived(progress.stage, buffer)
                    take(step)
                    if (step.establishedNow && keepAlive == null) {
                        onEstablished()
                        keepAlive = launch(CoroutineName("NativeAa-HfpKeepAlive")) {
                            while (isActive && isOpen()) {
                                write(listOf(HfpSlcInitiator.command(HfpSlcInitiator.KEEPALIVE_COMMAND)))
                                delay(HfpSlcInitiator.KEEPALIVE_INTERVAL_MS)
                            }
                        }
                    }
                }
                // Fast only while the walk is in flight: four steps at 200 ms would spend most of
                // a poke's hold getting to the link the poke exists to establish.
                val stage = progress.stage
                delay(if (stage == HfpSlcInitiator.Stage.IDLE || stage == HfpSlcInitiator.Stage.ESTABLISHED) 200 else 50)
            }
        } finally {
            keepAlive?.cancel()
        }
    }
}
