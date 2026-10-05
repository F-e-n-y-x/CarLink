package com.shilapi.xcertplay.airplay

import android.util.Log
import java.io.Closeable
import java.io.DataInputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay's main buffered audio (WWDC23 "Enhanced buffering"). For apps that support it (Apple Music),
 * the iPhone sends music ahead of time over TCP, here up to about two minutes, so short Wi-Fi gaps do
 * not interrupt playback. The car keeps the timing: SETRATE starts playback at an RTP time and the car
 * answers with the anchor (when that sample plays on the session's network clock); GETANCHOR reads it;
 * SETRATEANCHORTIME with rate 0 pauses; FLUSHBUFFERED drops what was sent before a track change.
 *
 * Frames are length-prefixed RTP packets sealed with the stream's key (shk) like the other audio
 * streams. They are kept here and handed to the [MediaSink] about [LEAD_MILLIS] ahead of playback, so
 * the media renderer (focus, ducking, channel settings) treats them like any music stream.
 */
internal class BufferedAudioStream(
    private val key: ByteArray,
    private val format: AudioFormat,
    private val sink: MediaSink,
    private val log: (String) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
) : Closeable {
    internal class Frame(val sequence: Int, val timestamp: Long, val rtp: ByteArray)

    private val id = AudioStreamId(format.payloadType, format.audioType)
    private val closed = AtomicBoolean(false)
    private val server = ServerSocket(0)
    @Volatile private var client: Socket? = null
    private val lock = Object()
    private val queue = ArrayDeque<Frame>()
    private var queuedBytes = 0L

    // Playback state, guarded by [lock].
    private var rate = 0
    private var flushUntil: Long? = null
    private var anchorRtp: Long? = null
    private var anchorNtp: BigInteger? = null
    private var feedStartTimestamp: Long? = null
    private var feedStartNs = 0L
    private var fedSamples = 0L

    val port: Int get() = server.localPort

    fun start() {
        Thread({ receive() }, "airplay-buffered-rx").apply { isDaemon = true; start() }
        Thread({ feed() }, "airplay-buffered-feed").apply { isDaemon = true; start() }
    }

    private fun receive() {
        try {
            val socket = server.accept().also { client = it }
            log("Buffered audio: connected")
            val input = DataInputStream(socket.getInputStream().buffered())
            var failures = 0
            var receivedBytes = 0L
            var windowStart = nanoTime()
            while (!closed.get()) {
                val length = input.readUnsignedShort()
                val body = ByteArray(maxOf(0, length - LENGTH_BYTES))
                input.readFully(body)
                val frame = open(key, body)
                if (frame == null) {
                    if (failures++ < 3) log("Buffered audio: frame did not open (${body.size} bytes)")
                    continue
                }
                receivedBytes += length
                val now = nanoTime()
                if (now - windowStart >= STATS_WINDOW_NS) {
                    val queuedMillis = synchronized(lock) { queue.size * SAMPLES_PER_FRAME * 1000L / format.sampleRate }
                    log("Buffered audio: queued ${queuedMillis / 1000} s, received " +
                        "${receivedBytes * 8 * 1_000_000L / ((now - windowStart) / 1000L) / 1000} kbit/s")
                    receivedBytes = 0
                    windowStart = now
                }
                if (frame.rtp.size <= RTP_HEADER) continue
                synchronized(lock) {
                    if (flushUntil?.let { before(frame.timestamp, it) } == true) return@synchronized
                    queue.addLast(frame)
                    queuedBytes += frame.rtp.size
                    lock.notifyAll()
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) log("Buffered audio: connection ended (${error.javaClass.simpleName})")
        }
    }

    /** Hands frames to the sink while playing, keeping about [LEAD_MILLIS] of audio ahead of real time. */
    private fun feed() {
        try {
            while (!closed.get()) {
                val frame = synchronized(lock) {
                    if (rate == 0 || queue.isEmpty() || leadMillisLocked() >= LEAD_MILLIS) {
                        lock.wait(FEED_POLL_MILLIS)
                        null
                    } else {
                        queue.pollFirst()?.also { queuedBytes -= it.rtp.size }?.also { frame ->
                            if (feedStartTimestamp == null) {
                                feedStartTimestamp = frame.timestamp
                                feedStartNs = nanoTime()
                                fedSamples = 0
                                sink.onAudioStarted(id, format, frame.timestamp.toInt())
                            }
                            fedSamples += SAMPLES_PER_FRAME
                        }
                    }
                } ?: continue
                sink.onAudioRtp(id, format, frame.rtp, frame.timestamp.toInt())
            }
        } catch (_: InterruptedException) {
        } catch (error: Exception) {
            if (!closed.get()) Log.w(TAG, "buffered audio feed failed", error)
        }
    }

    private fun leadMillisLocked(): Long {
        if (feedStartTimestamp == null) return 0
        val playedMillis = (nanoTime() - feedStartNs) / 1_000_000L
        return fedSamples * 1000L / format.sampleRate - playedMillis
    }

    /**
     * SETRATE / SETRATEANCHORTIME. Rate 1 starts at [rtpTime] (frames before it are dropped) about
     * [START_LATENCY_MILLIS] from [nowNtp]; rate 0 pauses where playback is. Returns the new anchor.
     */
    fun setRate(rtpTime: Long?, newRate: Int, nowNtp: BigInteger): Map<String, Any?>? = synchronized(lock) {
        if (newRate > 0) {
            val start = rtpTime ?: positionLocked() ?: queue.peekFirst()?.timestamp
            if (rtpTime != null) dropBeforeLocked(rtpTime)
            restartOutputLocked()
            anchorRtp = start
            anchorNtp = nowNtp.add(millisToNtp(START_LATENCY_MILLIS))
        } else {
            positionLocked()?.let { anchorRtp = it }
            anchorNtp = nowNtp
            restartOutputLocked()
        }
        rate = newRate.coerceIn(0, 1)
        lock.notifyAll()
        anchorLocked()
    }

    /** GETANCHOR: the current anchor, or null before the first SETRATE. */
    fun anchor(): Map<String, Any?>? = synchronized(lock) { anchorLocked() }

    /** FLUSHBUFFERED: drop what was sent before [untilTimestamp] and restart the output there. */
    fun flush(untilTimestamp: Long?) = synchronized(lock) {
        if (untilTimestamp != null) dropBeforeLocked(untilTimestamp)
        restartOutputLocked()
        lock.notifyAll()
    }

    /** The /feedback entry for this stream: the sample playing now on the session clock. */
    fun feedback(nowNtp: BigInteger, connectionId: Any?): Map<String, Any?> = synchronized(lock) {
        linkedMapOf<String, Any?>(
            "type" to format.payloadType,
            "sampleRate" to format.sampleRate,
        ).apply {
            val position = positionLocked() ?: return@apply
            put("streamConnectionID", unsignedPlistInteger(connectionId ?: 0L))
            put("timestamp", nowNtp)
            put("timestampRawNs", nanoTime())
            put("sampleTime", position)
        }
    }

    private fun positionLocked(): Long? {
        val start = feedStartTimestamp
        if (start != null && rate > 0) {
            val playedMillis = maxOf(0L, (nanoTime() - feedStartNs) / 1_000_000L - START_LATENCY_MILLIS)
            return (start + playedMillis * format.sampleRate / 1000L) and U32
        }
        return anchorRtp
    }

    private fun anchorLocked(): Map<String, Any?>? {
        val rtp = anchorRtp ?: return null
        val at = anchorNtp ?: return null
        return anchorPlist(rtp, at, rate)
    }

    private fun dropBeforeLocked(timestamp: Long) {
        flushUntil = timestamp
        while (queue.peekFirst()?.let { before(it.timestamp, timestamp) } == true) {
            queuedBytes -= queue.pollFirst()!!.rtp.size
        }
    }

    /** Stops what the sink still holds, so a pause or a track change is heard at once. */
    private fun restartOutputLocked() {
        if (feedStartTimestamp != null) sink.onAudioStopped(id)
        feedStartTimestamp = null
        fedSamples = 0
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { client?.close() }
        runCatching { server.close() }
        synchronized(lock) {
            queue.clear()
            queuedBytes = 0
            lock.notifyAll()
        }
        sink.onAudioStopped(id)
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        const val STREAM_TYPE = 103
        /** What the SETUP response offers; the iPhone sends at most this much ahead. */
        const val AUDIO_BUFFER_BYTES = 8 * 1024 * 1024
        internal const val LEAD_MILLIS = 1_000L
        /** About when the first fed sample is heard: the media renderer's start level and the decoder. */
        internal const val START_LATENCY_MILLIS = 400L
        private const val FEED_POLL_MILLIS = 20L
        private const val STATS_WINDOW_NS = 10_000_000_000L
        private const val SAMPLES_PER_FRAME = 1024L
        private const val LENGTH_BYTES = 2
        private const val RTP_HEADER = 12
        private const val NONCE_BYTES = 8
        private const val TAG_BYTES = 16
        private const val U32 = 0xffff_ffffL
        /** Seconds between NTP's 1900 epoch and the 1970 epoch the anchor's networkTimeSecs uses. */
        private const val NTP_UNIX_OFFSET = 2_208_988_800L

        /**
         * One buffered frame: RTP header, ChaCha20-Poly1305 ciphertext and tag sealed with [key] (the
         * header's timestamp and SSRC as associated data), then an 8-byte nonce. Null when it does not open.
         */
        internal fun open(key: ByteArray, body: ByteArray): Frame? {
            if (body.size < RTP_HEADER + TAG_BYTES + NONCE_BYTES) return null
            val sealedEnd = body.size - NONCE_BYTES
            val nonce = ByteArray(12).also { body.copyInto(it, 4, sealedEnd, body.size) }
            val payload = runCatching {
                AirPlayCrypto.chachaOpen(key, nonce, body.copyOfRange(RTP_HEADER, sealedEnd), body.copyOfRange(4, RTP_HEADER))
            }.getOrNull() ?: return null
            val sequence = ((body[2].toInt() and 0xff) shl 8) or (body[3].toInt() and 0xff)
            return Frame(sequence, u32(body, 4), body.copyOf(RTP_HEADER) + payload)
        }

        /**
         * The anchor plist: RTP sample [rtp] plays at [ntp] (NTP64 on the session's timing clock). The
         * iPhone reads networkTimeSecs on the 1970 epoch and networkTimeFrac as a 64-bit fraction.
         */
        internal fun anchorPlist(rtp: Long, ntp: BigInteger, rate: Int): Map<String, Any?> = linkedMapOf(
            "rtpTime" to (rtp and U32),
            "networkTimeSecs" to ntp.shiftRight(32).toLong() - NTP_UNIX_OFFSET,
            "networkTimeFrac" to ntp.and(BigInteger.valueOf(U32)).shiftLeft(32),
            "rate" to rate,
        )

        internal fun millisToNtp(millis: Long): BigInteger =
            BigInteger.valueOf(millis).shiftLeft(32).divide(BigInteger.valueOf(1000))

        /** Whether RTP timestamp [a] comes before [b], across the 32-bit wrap. */
        internal fun before(a: Long, b: Long): Boolean = ((a - b) and U32) >= 0x8000_0000L

        private fun u32(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 0xff) shl 24) or ((bytes[offset + 1].toLong() and 0xff) shl 16) or
                ((bytes[offset + 2].toLong() and 0xff) shl 8) or (bytes[offset + 3].toLong() and 0xff)
    }
}
