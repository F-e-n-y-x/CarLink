package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A blocking NCM data pipe that moves Ethernet frames as NTB16 blocks over bulk endpoints.
 *
 * The caller opens the USB connection while the CarPlay configuration is already active; this
 * bridge claims only the NCM control/data interfaces and owns the connection thereafter. All
 * calls may block and must run away from the Android main thread.
 */
class NcmUsbBridge internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val statusEndpoint: UsbEndpoint?,
    private val claimedInterfaces: List<UsbInterface>,
    descriptorHostMac: ByteArray?,
    private val onDiagnostic: (String) -> Unit = {},
    private val onLinkEvent: (NcmLinkEvent) -> Unit = {}, // CarLink
    private val isDeviceAlive: () -> Boolean = { usbDeviceResponds(connection) }, // CarLink
) : Closeable {
    private val descriptorMac = descriptorHostMac?.copyOf()
    val hostMac: ByteArray? get() = descriptorMac?.copyOf()
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var sequence = 0

    /** [System.nanoTime] when the iPhone first took a frame on bulk OUT, or null while it still NAKs the pipe. */
    @Volatile var linkOpenedAtNanos: Long? = null
        private set
    private val frames = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var buffered = ByteArray(0)
    private var bufferedSize = 0
    private var optionalShortPacketPad = false
    // A full-speed link uses 64-byte packets; the short-packet pad follows the real packet size.
    private val usbPacketSize = inEndpoint.maxPacketSize.takeIf { it > 0 } ?: USB_PACKET_SIZE
    private val resyncTimes = ArrayDeque<Long>()
    private var resyncCount = 0
    private var resyncReason: String? = null
    private var resyncSkipped = 0
    private val readBuffer = ByteArray(READ_CHUNK_BYTES)
    // Bulk IN uses one persistent async request: bulkTransfer() pins its byte[] in a JNI critical
    // section for the whole wait, which blocks ART's GC thread flip and, with it, every other USB
    // transfer (seen as ~0.8 s stalls of video and audio). A timed-out request stays queued, so no
    // data is lost between calls. This is the only requestWait() user on this connection.
    private val directReadBuffer = ByteBuffer.allocateDirect(READ_CHUNK_BYTES)
    private var readRequest: UsbRequest? = null
    private var readQueued = false
    private val readQueuePolicy = UsbReadQueuePolicy.forCurrentPlatform()
    private val readRequests = UsbRequestQueue(connection, "ncm-read-reaper")
    private val traffic = NcmTrafficCounter()
    // CarLink: the interrupt (status) endpoint is read continuously by a second persistent async
    // request on the same connection, reaped by the NCM reader's requestWait(). Upstream polled it
    // with a 20 ms bulkTransfer every 500 ms and only logged the first notification.
    private val statusBuffer = statusEndpoint?.let {
        ByteBuffer.allocateDirect(it.maxPacketSize.coerceAtLeast(STATUS_BUFFER_BYTES))
    }
    private var statusRequest: UsbRequest? = null
    private var statusQueued = false
    private var statusDisabled = statusEndpoint == null
    private var emptyStatusCompletions = 0
    private val nullReaps = UsbTransientFailureBudget() // CarLink

    // CarLink: bulk-out is owned by one writer thread fed from a bounded queue (see NcmOutbound.kt).
    private val outLock = Object()
    private val outbound = NcmOutboundQueue()
    private val pacing = NcmOutboundPacing()
    private var writeTimeoutMillis = DEFAULT_WRITE_TIMEOUT_MILLIS
    private var linkSignals = 0
    private val writerRunning = AtomicBoolean(true)
    private val writerThread = Thread(::runWriter, "ncm-bulk-out").apply {
        isDaemon = true
        start()
    }

    /**
     * Wraps one Ethernet frame in one NTB16 block and queues it for bulk OUT.
     *
     * CarLink: upstream wrote synchronously and dropped the frame when the phone did not accept it
     * within [timeoutMillis]. The block now waits in a bounded queue until the phone accepts it, so
     * the first replies after link-up are not lost. When the queue is full this waits up to
     * [timeoutMillis] for room (upstream's backpressure), then drops the oldest block.
     */
    fun send(frame: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val sequence = synchronized(stateLock) {
            checkOpenLocked()
            this.sequence.also { this.sequence = (this.sequence + 1) and 0xffff }
        }
        val block = Ntb16Codec.build(frame, sequence)
        synchronized(outLock) {
            writeTimeoutMillis = timeoutMillis
            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            while (!outbound.hasRoomFor(block) && writerRunning.get()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                try {
                    waitNanos(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IphoneUsbException.DeviceUnavailable("Interrupted while queueing an NCM frame")
                }
            }
            checkOpen()
            if (outbound.offer(block) > 0 && outbound.dropped == 1L) {
                Log.i(IphoneCarPlayConfiguration.TAG, "ncm outbound queue full; dropping oldest frames")
            }
            outLock.notifyAll()
        }
    }

    // CarLink
    private fun runWriter() = try {
        writeLoop()
    } catch (_: InterruptedException) {
        // close() or the owner interrupted the writer; nothing else to do.
    }

    private fun writeLoop() {
        while (true) {
            val (block, timeout) = synchronized(outLock) {
                while (writerRunning.get() && outbound.isEmpty()) outLock.wait()
                if (!writerRunning.get()) return
                outbound.peek()!! to pacing.attemptTimeoutMillis(writeTimeoutMillis)
            }
            val transferred = try {
                connection.bulkTransfer(outEndpoint, block, block.size, timeout)
            } catch (error: RuntimeException) {
                if (writerRunning.get()) failSession("NCM write failed", error)
                return
            }
            if (!writerRunning.get()) return
            if (transferred > 0 && transferred != block.size) {
                failSession("NCM write transferred $transferred of ${block.size} bytes")
                synchronized(outLock) { outLock.notifyAll() }
                return
            }
            var event: NcmLinkEvent? = null
            synchronized(outLock) {
                if (transferred == block.size) {
                    outbound.removeHead()
                    traffic.sent() // CarLink: upstream counted in its synchronous send()
                    if (linkOpenedAtNanos == null) linkOpenedAtNanos = System.nanoTime() // CarLink: upstream set it in send()
                    event = pacing.onAccepted()
                } else {
                    // Before StartCarPlaySession, and until the phone's NCM link is up, the phone
                    // NAKs bulk OUT. Android reports the timeout as -1; it is not a detach.
                    if (!pacing.onRefused()) outbound.removeHead()
                    if (pacing.firstRefusal) event = NcmLinkEvent.BulkOutNotReady
                }
                outLock.notifyAll()
            }
            event?.let(::emitLinkEvent)
            if (transferred != block.size) pauseAfterRefusal()
        }
    }

    // CarLink: waits before the next attempt, but wakes at once on a link signal or close.
    private fun pauseAfterRefusal() = synchronized(outLock) {
        val signals = linkSignals
        val deadline = System.nanoTime() + pacing.pauseAfterRefusalMillis() * NANOS_PER_MILLISECOND
        while (writerRunning.get() && signals == linkSignals) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return@synchronized
            waitNanos(remaining)
        }
    }

    private fun waitNanos(nanos: Long) {
        outLock.wait(nanos / NANOS_PER_MILLISECOND, (nanos % NANOS_PER_MILLISECOND).toInt())
    }

    // CarLink
    private fun onStatusNotification(bytes: ByteArray) {
        val events = NcmNotificationParser.parse(bytes)
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "ncm status notification bytes=${bytes.size} data=${bytes.hex(32)} events=$events",
        )
        for (event in events) {
            if (event is NcmLinkEvent.NetworkConnection) {
                synchronized(outLock) {
                    pacing.onLinkSignal(event.connected)
                    linkSignals += 1
                    outLock.notifyAll()
                }
            }
            emitLinkEvent(event)
        }
    }

    private fun emitLinkEvent(event: NcmLinkEvent) {
        when (event) {
            NcmLinkEvent.BulkOutNotReady ->
                Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out not ready; keeping frames for retry")
            is NcmLinkEvent.BulkOutReady -> Log.i(
                IphoneCarPlayConfiguration.TAG,
                "ncm bulk-out became ready waited=${event.waitedMillis}ms attempts=${event.attempts}",
            )
            else -> Unit
        }
        runCatching { onLinkEvent(event) }
    }

    /** Frames to and from the iPhone so far, for a report line; see [NcmTrafficCounter]. */
    fun trafficSummary(airPlayPort: Int?): String = traffic.summary(airPlayPort)

    /** Whether the iPhone tried to connect to [port] at or after [sinceNanos]; see [NcmTrafficCounter]. */
    fun connectionAttemptSince(port: Int, sinceNanos: Long): Boolean = traffic.connectionAttemptSince(port, sinceNanos)

    /**
     * Returns the next complete Ethernet frame, or null when [timeoutMillis] elapses without one.
     * USB reads may split or coalesce NTB blocks; this method reassembles whole blocks internally.
     */
    fun recv(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        synchronized(readLock) {
            checkOpen()
            if (frames.isNotEmpty()) return pollFrame()

            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            while (true) {
                drainFrames()
                if (frames.isNotEmpty()) return pollFrame()
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return null
                val chunkLength =
                    readChunk((remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                        ?: continue
                appendBuffered(readBuffer, chunkLength)
            }
        }
    }

    override fun close() {
        val (requestToClose, statusToClose) = synchronized(stateLock) {
            if (closed) return
            closed = true
            readRequest to statusRequest
        }
        // CarLink: stop the bulk-out writer; its attempts are short unless the link was ready.
        writerRunning.set(false)
        synchronized(outLock) {
            outbound.clear()
            outLock.notifyAll()
        }
        // Wakes a reader blocked in requestWait(); it then observes the closed state.
        runCatching { requestToClose?.cancel() }
        runCatching { statusToClose?.cancel() } // CarLink
        if (Thread.currentThread() !== writerThread) {
            try {
                writerThread.join(WRITER_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        for (usbInterface in claimedInterfaces.asReversed()) {
            try {
                connection.releaseInterface(usbInterface)
            } catch (_: RuntimeException) {
                // Best-effort release; the connection close below is authoritative.
            }
        }
        readRequests.close()
        connection.close()
        runCatching { requestToClose?.close() }
        runCatching { statusToClose?.close() } // CarLink
    }

    /**
     * CarLink: keeps one status (interrupt-IN) request queued. Call under [stateLock]. A status
     * endpoint Android will not read is logged once and skipped; it never fails the data path.
     */
    private fun queueStatusLocked() = try {
        queueStatusOnceLocked()
    } catch (error: RuntimeException) {
        statusDisabled = true
        Log.w(IphoneCarPlayConfiguration.TAG, "ncm status request failed; notifications ignored", error)
    }

    private fun queueStatusOnceLocked() {
        if (statusDisabled || statusQueued) return
        val endpoint = statusEndpoint ?: return
        val buffer = statusBuffer ?: return
        val request = statusRequest ?: UsbRequest().also {
            if (!it.initialize(connection, endpoint)) {
                it.close()
                statusDisabled = true
                Log.w(IphoneCarPlayConfiguration.TAG, "ncm status request could not be initialized; notifications ignored")
                return
            }
            statusRequest = it
        }
        buffer.clear()
        if (readRequests.queue(request, buffer)) {
            statusQueued = true
        } else {
            statusDisabled = true
            Log.w(IphoneCarPlayConfiguration.TAG, "ncm status request could not be queued; notifications ignored")
        }
    }

    private fun ByteArray.hex(limit: Int): String =
        take(limit).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun drainFrames() {
        while (true) {
            // wBlockLength describes the complete NTB. A packet-aligned USB transfer may end
            // with a ZLP (no byte in the buffer), or carry one zero byte to force a short packet.
            // Do not wait for that optional byte or consume the next NTB's header as padding.
            // Keep this state when the pad/header arrives in a later USB completion.
            if (optionalShortPacketPad && bufferedSize > 0) {
                optionalShortPacketPad = false
                when (buffered[0].toInt() and 0xff) {
                    0 -> discard(1)
                    Ntb16Codec.NTH16_SIG and 0xff -> Unit // The next header is validated below.
                    else -> if (!resynchronize("Invalid NTB16 short-packet pad")) return
                }
            }
            if (bufferedSize < NTH16_LENGTH) return
            val blockLength = readU16(buffered, 8)
            if (readU32(buffered, 0) != Ntb16Codec.NTH16_SIG) {
                if (!resynchronize("NCM read buffer does not begin with an NTB16 header")) return
                continue
            }
            if (blockLength < 28) {
                if (!resynchronize("Invalid NTB16 block length $blockLength")) return
                continue
            }
            if (bufferedSize < blockLength) return
            for (frame in Ntb16Codec.parse(buffered, 0, blockLength)) enqueueFrame(frame)
            discard(blockLength)
            optionalShortPacketPad = blockLength % usbPacketSize == 0
            finishResync()
        }
    }

    /**
     * Android reaps a bulk transfer that failed part way (babble, CRC, a dropped packet) as a
     * normal completion with whatever bytes arrived, so a damaged NTB can leave the stream
     * misaligned. Like Linux cdc_ncm, drop the damaged bytes and continue at the next valid NTB16
     * header; TCP resends what was lost. Returns false while no header is buffered yet. Damage
     * that keeps recurring still fails the session, as before.
     */
    private fun resynchronize(reason: String): Boolean {
        if (resyncReason == null) {
            val now = System.nanoTime()
            while (resyncTimes.isNotEmpty() && now - resyncTimes.first() > RESYNC_WINDOW_NANOS) resyncTimes.removeFirst()
            if (resyncTimes.size >= MAX_RESYNCS_PER_WINDOW) throw failSession("$reason (repeated)")
            resyncTimes.addLast(now)
            resyncCount++
            resyncReason = reason
            resyncSkipped = 0
        }
        val next = (1..bufferedSize - NTH16_LENGTH).firstOrNull(::plausibleHeaderAt)
        // Without a header, keep the bytes that could still be the start of one.
        val skipped = next ?: (bufferedSize - (NTH16_LENGTH - 1)).coerceAtLeast(0)
        discard(skipped)
        resyncSkipped += skipped
        return next != null
    }

    private fun finishResync() {
        val reason = resyncReason ?: return
        resyncReason = null
        if (resyncCount <= LOGGED_RESYNCS || resyncCount % 50 == 0) {
            runCatching { onDiagnostic("NCM resynchronized after $reason; skipped=$resyncSkipped resyncs=$resyncCount") }
        }
    }

    /** Stricter than the normal path, so payload bytes are unlikely to pass as a header. */
    private fun plausibleHeaderAt(offset: Int): Boolean {
        if (readU32(buffered, offset) != Ntb16Codec.NTH16_SIG) return false
        val headerLength = readU16(buffered, offset + 4)
        val blockLength = readU16(buffered, offset + 8)
        val ndpIndex = readU16(buffered, offset + 10)
        return headerLength == NTH16_LENGTH && blockLength >= 28 &&
            ndpIndex >= NTH16_LENGTH && ndpIndex % 4 == 0 && ndpIndex + 8 <= blockLength
    }

    private fun discard(count: Int) {
        if (count <= 0) return
        buffered.copyInto(buffered, 0, count, bufferedSize)
        bufferedSize -= count
    }

    private fun appendBuffered(source: ByteArray, length: Int) {
        val required = bufferedSize + length
        if (required > buffered.size) {
            val capacity = maxOf(required, maxOf(READ_CHUNK_BYTES, buffered.size * 2))
            val grown = ByteArray(capacity)
            buffered.copyInto(grown, 0, 0, bufferedSize)
            buffered = grown
        }
        source.copyInto(buffered, bufferedSize, 0, length)
        bufferedSize += length
    }

    private fun enqueueFrame(frame: ByteArray) {
        if (frames.size >= MAX_QUEUED_FRAMES || queuedBytes + frame.size > MAX_QUEUED_BYTES) {
            throw failSession("NCM frame queue exceeded its bounds")
        }
        frames.addLast(frame)
        queuedBytes += frame.size
    }

    private fun pollFrame(): ByteArray {
        val frame = frames.removeFirst()
        queuedBytes -= frame.size
        traffic.received(frame)
        return frame
    }

    private fun readChunk(timeoutMillis: Long): Int? {
        checkOpen()
        var acceptedFallback: UsbReadQueueResult? = null
        val request = try {
            // Publish and queue atomically with close(), so detach cannot miss a new request.
            synchronized(stateLock) {
                checkOpenLocked()
                val current = readRequest ?: UsbRequest().also {
                    if (!it.initialize(connection, inEndpoint)) {
                        it.close()
                        throw failSession("Android could not initialize the NCM read request")
                    }
                    readRequest = it
                }
                if (!readQueued) {
                    directReadBuffer.clear()
                    val queued = readQueuePolicy.queue(directReadBuffer, ::checkOpenLocked) { readRequests.queue(current, it) }
                    if (!queued.queued) throw failSession(
                        "Android could not queue the NCM read request (api=${Build.VERSION.SDK_INT} " +
                            "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                            "fallbackBytes=${queued.fallbackBytes ?: "not_attempted"})",
                    )
                    readQueued = true
                    if (queued.fallbackBytes != null) acceptedFallback = queued
                }
                queueStatusLocked() // CarLink
                current
            }
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
        // Emit outside stateLock; diagnostic callbacks must not affect queue or close behavior.
        acceptedFallback?.let { queued ->
            runCatching {
                onDiagnostic(
                    "NCM read queue compatibility fallback api=${Build.VERSION.SDK_INT} " +
                        "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                        "fallbackBytes=${queued.fallbackBytes}",
                )
            }
        }
        try {
            // CarLink: the status request completes on this connection too, and a null reap is
            // retried while the phone still answers (see UsbTransientRetry.kt).
            val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(1) * NANOS_PER_MILLISECOND
            var completed: UsbRequest
            while (true) {
                val remainingMillis = ((deadline - System.nanoTime() + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND).coerceAtLeast(1)
                val reaped = try {
                    readRequests.await(remainingMillis)
                } catch (_: TimeoutException) {
                    // Nothing arrived yet; the request stays queued for the next call. USBMUX owns
                    // authoritative detach/failure detection for the same phone.
                    return null
                }
                if (reaped == null) {
                    if (nullReaps.recordFailure() && !isClosed() && isDeviceAlive()) {
                        runCatching { onDiagnostic("NCM reap returned no request; phone present, retry=${nullReaps.consecutive}") }
                        if (System.nanoTime() >= deadline) return null
                        continue
                    }
                    throw failSession("Android returned no NCM read request")
                }
                nullReaps.reset()
                if (reaped === statusRequest && reaped !== request) {
                    val notification = synchronized(stateLock) {
                        statusQueued = false
                        val buffer = statusBuffer!!
                        val bytes = ByteArray(buffer.position()).also {
                            buffer.flip()
                            buffer.get(it)
                        }
                        // An endpoint that completes empty again and again (an error) is dropped
                        // rather than spun on.
                        emptyStatusCompletions = if (bytes.isEmpty()) emptyStatusCompletions + 1 else 0
                        if (emptyStatusCompletions >= MAX_EMPTY_STATUS_COMPLETIONS) statusDisabled = true
                        if (!closed) queueStatusLocked()
                        bytes
                    }
                    if (notification.isNotEmpty()) onStatusNotification(notification)
                    if (System.nanoTime() >= deadline) return null
                    continue
                }
                completed = reaped
                break
            }
            if (completed !== request) throw failSession("Android completed an unexpected NCM request")
            readQueued = false
            val transferred = directReadBuffer.position()
            if (transferred <= 0) return null
            directReadBuffer.flip()
            directReadBuffer.get(readBuffer, 0, transferred)
            return transferred
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun isClosed(): Boolean = synchronized(stateLock) { closed } // CarLink

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("NCM bridge is closed")
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    companion object {
        private const val READ_CHUNK_BYTES = 32 * 1024
        private const val USB_PACKET_SIZE = 512
        // CarLink: the status poll constants are gone (continuous async read); writer settings.
        private const val STATUS_BUFFER_BYTES = 64
        private const val MAX_EMPTY_STATUS_COMPLETIONS = 8
        private const val DEFAULT_WRITE_TIMEOUT_MILLIS = 2_000
        private const val WRITER_JOIN_MILLIS = 300L
        private const val NTH16_LENGTH = 12
        private const val MAX_RESYNCS_PER_WINDOW = 16
        private const val RESYNC_WINDOW_NANOS = 10_000_000_000L
        private const val LOGGED_RESYNCS = 5
        internal const val USB_SETUP_ATTEMPTS = 5
        private const val USB_SETUP_RETRY_MILLIS = 100L
        private const val MAX_QUEUED_FRAMES = 256
        private const val MAX_QUEUED_BYTES = 1 shl 20
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /**
         * Retries a USB setup call that a vendor kernel can lose to its own cdc_ncm driver, which may
         * rebind between Android's forced detach and the claim (#518). Returns the successful attempt.
         */
        internal fun retryUsbSetup(
            attempts: Int = USB_SETUP_ATTEMPTS,
            pause: (Long) -> Unit = Thread::sleep,
            action: () -> Boolean,
        ): Int? {
            for (attempt in 1..attempts) {
                if (action()) return attempt
                if (attempt < attempts) pause(USB_SETUP_RETRY_MILLIS)
            }
            return null
        }

        /** Claims and activates the NCM control/data interfaces; owns the connection on success. */
        fun open(
            connection: UsbDeviceConnection,
            function: NcmFunctionDiscovery.NcmFunction,
            onDiagnostic: (String) -> Unit = {},
            onLinkEvent: (NcmLinkEvent) -> Unit = {}, // CarLink
        ): NcmUsbBridge {
            val claimed = ArrayList<UsbInterface>(2)
            try {
                val descriptorHostMac = readNcmHostMac(connection, function.control.id)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm descriptor hostMac=${descriptorHostMac?.macString() ?: "unavailable"}",
                )
                // Apple's Ethernet function exposes control and data as alternate settings of the
                // same interface id, so it must be claimed once and switched with setInterface.
                val sameInterface = function.control.id == function.data.id
                val first = if (sameInterface) function.data else function.control
                val firstClaimed = retryUsbSetup { connection.claimInterface(first, true) }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "claim iface=${first.id}/${first.alternateSetting} class=${first.interfaceClass}" +
                        " subclass=${first.interfaceSubclass} proto=${first.interfaceProtocol} ok=${firstClaimed != null}" +
                        " attempts=${firstClaimed ?: USB_SETUP_ATTEMPTS}",
                )
                if (firstClaimed == null) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not claim the NCM interface ${first.id}",
                    )
                }
                claimed.add(first)
                if (!sameInterface) {
                    val dataClaimed = retryUsbSetup { connection.claimInterface(function.data, true) }
                    Log.i(
                        IphoneCarPlayConfiguration.TAG,
                        "claim iface=${function.data.id}/${function.data.alternateSetting}" +
                            " class=${function.data.interfaceClass} ok=${dataClaimed != null}" +
                            " attempts=${dataClaimed ?: USB_SETUP_ATTEMPTS}",
                    )
                    if (dataClaimed == null) {
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not claim the NCM data interface ${function.data.id}",
                        )
                    }
                    claimed.add(function.data)
                }
                val altSelected = retryUsbSetup { connection.setInterface(function.data) }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "setInterface iface=${function.data.id}/${function.data.alternateSetting} ok=${altSelected != null}" +
                        " attempts=${altSelected ?: USB_SETUP_ATTEMPTS}",
                )
                if (altSelected == null) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not select the NCM data alternate setting",
                    )
                }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status endpoint=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}",
                )
                return NcmUsbBridge(
                    connection,
                    function.bulkOut,
                    function.bulkIn,
                    function.statusIn,
                    claimed,
                    descriptorHostMac,
                    onDiagnostic,
                    onLinkEvent, // CarLink
                )
            } catch (error: Throwable) {
                for (usbInterface in claimed.asReversed()) {
                    try {
                        connection.releaseInterface(usbInterface)
                    } catch (_: RuntimeException) {
                        // The connection close below is authoritative.
                    }
                }
                connection.close()
                if (error is IphoneUsbException) throw error
                throw IphoneUsbException.DeviceUnavailable("Android NCM open failed", error)
            }
        }

        private fun readNcmHostMac(connection: UsbDeviceConnection, controlInterfaceId: Int): ByteArray? {
            val index = ethernetMacStringIndex(connection.rawDescriptors, controlInterfaceId) ?: return null
            val buffer = ByteArray(256)
            val length = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                USB_REQUEST_GET_DESCRIPTOR,
                (USB_STRING_DESCRIPTOR_TYPE shl 8) or index,
                USB_ENGLISH_US,
                buffer,
                buffer.size,
                USB_CONTROL_TIMEOUT_MILLIS,
            )
            if (length < 4 || (buffer[1].toInt() and 0xff) != USB_STRING_DESCRIPTOR_TYPE) return null
            val descriptorLength = (buffer[0].toInt() and 0xff).coerceAtMost(length)
            if (descriptorLength < 4) return null
            val value = buffer.copyOfRange(2, descriptorLength).toString(Charsets.UTF_16LE)
            val hex = value.filter { it.digitToIntOrNull(16) != null }
            if (hex.length != 12) return null
            return ByteArray(6) { offset -> hex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte() }
        }

        private fun ethernetMacStringIndex(raw: ByteArray, controlInterfaceId: Int): Int? {
            var offset = 0
            var currentInterface = -1
            while (offset + 2 <= raw.size) {
                val length = raw[offset].toInt() and 0xff
                val type = raw[offset + 1].toInt() and 0xff
                if (length < 2 || offset + length > raw.size) return null
                if (type == USB_INTERFACE_DESCRIPTOR_TYPE && length >= 9) {
                    currentInterface = raw[offset + 2].toInt() and 0xff
                } else if (
                    type == CDC_FUNCTIONAL_DESCRIPTOR_TYPE &&
                    length >= 4 &&
                    currentInterface == controlInterfaceId &&
                    (raw[offset + 2].toInt() and 0xff) == CDC_ETHERNET_SUBTYPE
                ) {
                    return (raw[offset + 3].toInt() and 0xff).takeIf { it != 0 }
                }
                offset += length
            }
            return null
        }

        private fun ByteArray.macString(): String =
            joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private const val USB_INTERFACE_DESCRIPTOR_TYPE = 0x04
        private const val USB_REQUEST_GET_DESCRIPTOR = 0x06
        private const val USB_STRING_DESCRIPTOR_TYPE = 0x03
        private const val CDC_FUNCTIONAL_DESCRIPTOR_TYPE = 0x24
        private const val CDC_ETHERNET_SUBTYPE = 0x0f
        private const val USB_ENGLISH_US = 0x0409
        private const val USB_CONTROL_TIMEOUT_MILLIS = 1_000
    }
}
