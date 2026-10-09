package com.shilapi.xcertplay.transport

import java.util.ArrayDeque

// CarLink: new file. Event-driven NCM link readiness (see docs/VENDOR_CHANGES.md).

/** What the NCM bridge tells its owner about the phone's link and the bulk-out data path. */
sealed class NcmLinkEvent {
    /** CDC NETWORK_CONNECTION from the phone's interrupt endpoint. */
    data class NetworkConnection(val connected: Boolean) : NcmLinkEvent()

    /** CDC CONNECTION_SPEED_CHANGE; bit rates in bit/s as reported by the phone. */
    data class SpeedChange(val downlinkBitsPerSecond: Long, val uplinkBitsPerSecond: Long) : NcmLinkEvent()

    /** The first outbound frame was refused (NAK timeout); later frames are kept and retried. */
    data object BulkOutNotReady : NcmLinkEvent()

    /** The phone accepted an outbound NTB after refusing; [waitedMillis] since the first refusal. */
    data class BulkOutReady(val waitedMillis: Long, val attempts: Int) : NcmLinkEvent()
}

/** Parses CDC notifications (CDC 1.2 §6.3) carried by one interrupt-IN transfer. */
internal object NcmNotificationParser {
    const val NETWORK_CONNECTION = 0x00
    const val CONNECTION_SPEED_CHANGE = 0x2a
    private const val HEADER_BYTES = 8

    fun parse(bytes: ByteArray, length: Int = bytes.size): List<NcmLinkEvent> {
        val events = ArrayList<NcmLinkEvent>(2)
        var offset = 0
        while (offset + HEADER_BYTES <= length) {
            val code = bytes[offset + 1].toInt() and 0xff
            val value = u16(bytes, offset + 2)
            val dataLength = u16(bytes, offset + 6)
            when (code) {
                NETWORK_CONNECTION -> events.add(NcmLinkEvent.NetworkConnection(value != 0))
                CONNECTION_SPEED_CHANGE -> if (dataLength >= 8 && offset + HEADER_BYTES + 8 <= length) {
                    events.add(
                        NcmLinkEvent.SpeedChange(
                            u32(bytes, offset + HEADER_BYTES),
                            u32(bytes, offset + HEADER_BYTES + 4),
                        ),
                    )
                }
            }
            offset += HEADER_BYTES + dataLength
        }
        return events
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun u32(b: ByteArray, o: Int): Long = (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
}

/**
 * Bounded FIFO of outbound NTB blocks. Upstream dropped every frame the phone refused within the
 * 2 s write timeout, so the first SYN-ACK/neighbor advertisement after link-up was lost and the
 * session waited for the phone's next TCP/ND retransmission (the quantized 2-36 s delay in
 * upstream issue #11). Frames are now kept until accepted; the oldest go first when full.
 */
internal class NcmOutboundQueue(
    private val maxFrames: Int = MAX_FRAMES,
    private val maxBytes: Int = MAX_BYTES,
) {
    private val blocks = ArrayDeque<ByteArray>()
    private var bytes = 0
    var dropped = 0L
        private set

    val size: Int get() = blocks.size
    fun isEmpty() = blocks.isEmpty()
    fun hasRoomFor(block: ByteArray) = blocks.size < maxFrames && bytes + block.size <= maxBytes

    /** Adds [block], dropping the oldest blocks if needed; returns how many were dropped. */
    fun offer(block: ByteArray): Int {
        var removed = 0
        while (blocks.isNotEmpty() && !hasRoomFor(block)) {
            removeHead()
            removed += 1
        }
        blocks.addLast(block)
        bytes += block.size
        dropped += removed
        return removed
    }

    fun peek(): ByteArray? = blocks.peekFirst()

    fun removeHead() {
        val head = blocks.pollFirst() ?: return
        bytes -= head.size
    }

    fun clear() {
        blocks.clear()
        bytes = 0
    }

    companion object {
        const val MAX_FRAMES = 128
        const val MAX_BYTES = 256 * 1024
    }
}

/**
 * Write timeouts and retry pauses for the NCM bulk-out writer. All values are short and bounded:
 * - ready (the phone accepted the last block): the caller's timeout, as upstream; a timeout then
 *   drops that block (it may have been partly sent) and returns to "not ready";
 * - not ready, within [FAST_WINDOW_MILLIS] of NETWORK_CONNECTION connected=1: [FAST_PROBE_MILLIS]
 *   attempts [FAST_PAUSE_MILLIS] apart, so the first accepted write follows link-up at once;
 * - otherwise not ready: [IDLE_PROBE_MILLIS] attempts every [IDLE_PAUSE_MILLIS]. This also covers
 *   a phone that sends no notification. Attempts are short because a blocked bulkTransfer holds a
 *   JNI critical section that stalls the GC (see the read-request comment in [NcmUsbBridge]).
 */
internal class NcmOutboundPacing(private val nanoTime: () -> Long = System::nanoTime) {
    var ready = false
        private set
    private var connected = false
    private var connectedAtNanos = 0L
    private var firstRefusalNanos: Long? = null
    private var attempts = 0

    fun onLinkSignal(isConnected: Boolean) {
        connected = isConnected
        if (isConnected) connectedAtNanos = nanoTime() else ready = false
    }

    fun attemptTimeoutMillis(writeTimeoutMillis: Int): Int = when {
        ready -> writeTimeoutMillis
        fastWindow() -> FAST_PROBE_MILLIS
        else -> IDLE_PROBE_MILLIS
    }.coerceAtMost(writeTimeoutMillis).coerceAtLeast(1)

    /** Returns the readiness event when this write ended a refusal period, else null. */
    fun onAccepted(): NcmLinkEvent.BulkOutReady? {
        val refusedSince = firstRefusalNanos
        val event = if (!ready && refusedSince != null) {
            NcmLinkEvent.BulkOutReady((nanoTime() - refusedSince) / 1_000_000L, attempts + 1)
        } else {
            null
        }
        ready = true
        firstRefusalNanos = null
        attempts = 0
        return event
    }

    /** Records a refused write. Returns true if the refused block must be kept for a retry. */
    fun onRefused(): Boolean {
        val keep = !ready
        ready = false
        if (firstRefusalNanos == null) firstRefusalNanos = nanoTime()
        attempts += 1
        return keep
    }

    /** True for the first refusal of a refusal period (worth one log line). */
    val firstRefusal: Boolean get() = attempts == 1

    fun pauseAfterRefusalMillis(): Long = if (fastWindow()) FAST_PAUSE_MILLIS else IDLE_PAUSE_MILLIS

    private fun fastWindow(): Boolean =
        connected && nanoTime() - connectedAtNanos < FAST_WINDOW_MILLIS * 1_000_000L

    companion object {
        const val FAST_WINDOW_MILLIS = 3_000L
        const val FAST_PROBE_MILLIS = 30
        const val FAST_PAUSE_MILLIS = 5L
        const val IDLE_PROBE_MILLIS = 20
        const val IDLE_PAUSE_MILLIS = 200L
    }
}
