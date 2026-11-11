package com.shilapi.xcertplay.media

import java.util.AbstractQueue
import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bounded queue of RTP packets in sequence order. Wi-Fi delivers some packets after later ones;
 * played in arrival order, each makes the music jump back and forth. A late packet goes back to its
 * place while a later packet is still queued. One whose place was already taken, or a duplicate, is
 * not queued. When the queue is full the arriving packet is not queued, whatever its place.
 */
internal class RtpReorderQueue<T : Any>(
    private val capacity: Int,
    private val sequenceOf: (T) -> Int,
    private val timestampOf: (T) -> Int,
) : AbstractQueue<T>() {
    enum class Placement { APPENDED, REORDERED, LATE, FULL }

    private class Entry<T>(val item: T, val sequence: Int, val timestamp: Int, val run: Int)

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val entries = LinkedList<Entry<T>>()
    // A run is the packets since the last restart or sequence discontinuity; only they are put in order.
    private var run = 0
    private var newest: Entry<T>? = null
    private var lastTaken: Entry<T>? = null

    fun insert(item: T): Placement {
        val sequence = sequenceOf(item)
        val timestamp = timestampOf(item)
        lock.withLock {
            if (entries.size >= capacity) return Placement.FULL
            val latest = newest
            if (latest != null && (sequence < 0 || latest.sequence < 0 || order(sequence, timestamp, latest) <= 0)) {
                if (isLate(sequence, timestamp, latest)) return insertLate(item, sequence, timestamp)
                startRun()
            }
            val entry = Entry(item, sequence, timestamp, run)
            entries.addLast(entry)
            newest = entry
            notEmpty.signal()
            return Placement.APPENDED
        }
    }

    /** The next packet starts a new sequence: none is dropped as late against the packets before it. */
    fun restartSequence() {
        lock.withLock { startRun() }
    }

    fun poll(timeout: Long, unit: TimeUnit): T? {
        var nanos = unit.toNanos(timeout)
        lock.withLock {
            while (entries.isEmpty()) {
                if (nanos <= 0L) return null
                nanos = notEmpty.awaitNanos(nanos)
            }
            return takeFirst()
        }
    }

    fun remainingCapacity(): Int = lock.withLock { capacity - entries.size }

    override fun offer(e: T): Boolean = when (insert(e)) {
        Placement.APPENDED, Placement.REORDERED -> true
        Placement.LATE, Placement.FULL -> false
    }

    override fun poll(): T? = lock.withLock { takeFirst() }

    override fun peek(): T? = lock.withLock { entries.peekFirst()?.item }

    override val size: Int get() = lock.withLock { entries.size }

    /** A snapshot: the decoder may take packets while the caller iterates. */
    override fun iterator(): MutableIterator<T> =
        Collections.unmodifiableList(lock.withLock { entries.map { it.item } }).iterator()

    override fun clear() {
        lock.withLock {
            entries.clear()
            startRun()
        }
    }

    private fun startRun() {
        run++
        newest = null
        lastTaken = null
    }

    private fun takeFirst(): T? {
        val entry = entries.pollFirst() ?: return null
        if (entry.run == run) lastTaken = entry
        return entry.item
    }

    /** Behind the newest packet by a reordering, not by a restarted sequence or timestamp. */
    private fun isLate(sequence: Int, timestamp: Int, latest: Entry<T>): Boolean {
        if (sequence < 0 || latest.sequence < 0) return false
        val behind = -sequenceDelta(sequence, latest.sequence)
        return behind in 0..MAX_REORDER_PACKETS && timestamp - latest.timestamp <= 0
    }

    private fun insertLate(item: T, sequence: Int, timestamp: Int): Placement {
        lastTaken?.let { if (order(sequence, timestamp, it) <= 0) return Placement.LATE }
        val position = entries.listIterator(entries.size)
        while (position.hasPrevious()) {
            val queued = position.previous()
            val after = if (queued.run == run) order(sequence, timestamp, queued) else 1
            if (after == 0) return Placement.LATE
            if (after > 0) {
                position.next()
                break
            }
        }
        position.add(Entry(item, sequence, timestamp, run))
        notEmpty.signal()
        return Placement.REORDERED
    }

    /** Positive when the packet comes after [other], negative before it, 0 for the same packet. */
    private fun order(sequence: Int, timestamp: Int, other: Entry<T>): Int {
        val bySequence = sequenceDelta(sequence, other.sequence)
        if (bySequence != 0) return bySequence
        val byTimestamp = timestamp - other.timestamp
        return byTimestamp.coerceIn(-1, 1)
    }

    private fun sequenceDelta(sequence: Int, other: Int): Int = ((sequence - other) shl 16) shr 16

    internal companion object {
        const val NO_SEQUENCE = -1
        /** Further behind, a packet is taken as a restarted sequence rather than a late one. */
        const val MAX_REORDER_PACKETS = 64

        /** The 16-bit sequence number of an RTP packet, or [NO_SEQUENCE] when the header is incomplete. */
        fun rtpSequence(rtp: ByteArray): Int =
            if (rtp.size < RTP_HEADER_BYTES) NO_SEQUENCE
            else ((rtp[2].toInt() and 0xff) shl 8) or (rtp[3].toInt() and 0xff)

        private const val RTP_HEADER_BYTES = 12
    }
}
