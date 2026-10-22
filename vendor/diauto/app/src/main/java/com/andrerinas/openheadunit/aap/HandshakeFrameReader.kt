package com.andrerinas.openheadunit.aap

// CarLink: ported from open-headunit 67bcae27 (docs/VENDOR_CHANGES.md).

import java.io.IOException

/**
 * Cleartext handshake envelope: channel(1), flags(1), body length(2), type(2), payload.
 * Length includes type, but excludes the four-byte transport header. Version and TLS handshake
 * messages use channel 0 and flags 3 (one complete, unencrypted message).
 *
 * TCP reads and USB bulk transfers are not message boundaries. Request only the bytes remaining
 * in this frame; the connection retains any following frame for the next handshake phase.
 * Use non-readFully I/O so every consumed byte is counted even if a later read times out.
 */
internal class HandshakeFrameReader(
    private val now: () -> Long,
    private val minimumReadTimeoutMs: Int = 1,
    private val receive: (ByteArray, Int, Int) -> Int
) {
    private var frame = ByteArray(4)
    private var used = 0
    private var scratch = ByteArray(4)

    /** Null means no complete frame yet; partial bytes survive the caller's retry deadline. */
    fun readFrame(deadline: Long): ByteArray? {
        while (now() < deadline) {
            val needed = frame.size - used
            if (scratch.size < needed) scratch = ByteArray(needed)
            // USB bulk APIs can lose a partial transfer when given a tiny timeout. Stop at
            // the end of this window rather than issue an unsafe read or extend the deadline.
            val remaining = deadline - now()
            if (remaining < minimumReadTimeoutMs) return null
            val timeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val count = receive(scratch, needed, timeout)
            if (count < 0) return null
            if (count == 0) continue // USB zero-length packet: still inside the same window.
            if (count > needed) throw IOException("Handshake read exceeded requested length")
            scratch.copyInto(frame, used, 0, count)
            used += count
            if (used != frame.size) continue
            if (frame.size == 4) {
                val length = ((frame[2].toInt() and 0xff) shl 8) or (frame[3].toInt() and 0xff)
                if (frame[1] != 3.toByte() || length < 2) {
                    throw IOException("Invalid cleartext handshake header")
                }
                // The unsigned 16-bit wire length bounds allocation to 65,539 bytes.
                frame = frame.copyOf(4 + length)
            } else {
                val complete = frame
                frame = ByteArray(4)
                used = 0
                return complete
            }
        }
        return null
    }
}
