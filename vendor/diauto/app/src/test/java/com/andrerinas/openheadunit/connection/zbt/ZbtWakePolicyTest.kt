// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0), app/src/test/.../nativeaa/zbt/ZbtWakePolicyTest.kt
package com.andrerinas.openheadunit.connection.zbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZbtWakePolicyTest {

    private fun shouldSend(
        lastSentAtMs: Long = 0L,
        nowMs: Long = 1_000_000L,
        settling: Boolean = false,
        handshakeInFlight: Boolean = false,
        sessionConnected: Boolean = false
    ) = ZbtWakePolicy.shouldSend(lastSentAtMs, nowMs, settling, handshakeInFlight, sessionConnected)

    @Test
    fun `the first one goes out`() {
        assertTrue(shouldSend())
    }

    @Test
    fun `nothing is sent while the link is busy`() {
        // This message asks the module to change state. Doing that mid-exchange, mid-settle, or
        // under a live session can only disturb something already working.
        assertFalse(shouldSend(settling = true))
        assertFalse(shouldSend(handshakeInFlight = true))
        assertFalse(shouldSend(sessionConnected = true))
    }

    @Test
    fun `a busy link outranks even a first send`() {
        assertFalse(shouldSend(lastSentAtMs = 0L, settling = true))
    }

    @Test
    fun `sends are spaced by the full interval`() {
        val last = 1_000_000L
        assertFalse(shouldSend(lastSentAtMs = last, nowMs = last))
        assertFalse(shouldSend(lastSentAtMs = last, nowMs = last + ZbtWakePolicy.MIN_INTERVAL_MS - 1))
        assertTrue(shouldSend(lastSentAtMs = last, nowMs = last + ZbtWakePolicy.MIN_INTERVAL_MS))
    }

    @Test
    fun `a clock that moved backwards waits rather than bursting`() {
        // elapsed goes negative. Reading that as "long enough ago" would send one immediately, and
        // then again on the next tick, at exactly the moment we can least reason about the state.
        assertFalse(shouldSend(lastSentAtMs = 1_000_000L, nowMs = 900_000L))
    }

    @Test
    fun `the interval stays clear of the module's own heartbeat`() {
        // The module re-sends link state about every 12 s. A wake landing on that cadence would be
        // indistinguishable from it in a log read after the fact, which is how these are read.
        assertTrue(ZbtWakePolicy.MIN_INTERVAL_MS > 12_000L)
    }

    private fun pending(
        requestedAtMs: Long = 1_000_000L,
        lastSentAtMs: Long = 0L,
        nowMs: Long = 1_003_000L,
        settling: Boolean = false,
        handshakeInFlight: Boolean = false,
        sessionConnected: Boolean = false
    ) = ZbtWakePolicy.pending(
        requestedAtMs, lastSentAtMs, nowMs, settling, handshakeInFlight, sessionConnected
    )

    @Test
    fun `a wake asked for before the channel opened goes out once it can`() {
        // The cold bring-up: credentials arrive while the daemon is still being measured.
        assertEquals(ZbtWakePolicy.Pending.SEND, pending())
    }

    @Test
    fun `a pending wake waits out a busy link rather than being lost`() {
        assertEquals(ZbtWakePolicy.Pending.HOLD, pending(handshakeInFlight = true))
        assertEquals(ZbtWakePolicy.Pending.HOLD, pending(settling = true))
        assertEquals(ZbtWakePolicy.Pending.HOLD, pending(lastSentAtMs = 1_000_000L))
    }

    @Test
    fun `a live session makes a pending wake pointless`() {
        assertEquals(ZbtWakePolicy.Pending.SESSION_UP, pending(sessionConnected = true))
    }

    @Test
    fun `a pending wake does not outlive its bound or a clock that went backwards`() {
        val at = 1_000_000L
        assertEquals(
            ZbtWakePolicy.Pending.HOLD,
            pending(requestedAtMs = at, nowMs = at + ZbtWakePolicy.PENDING_MAX_MS - 1, handshakeInFlight = true)
        )
        assertEquals(
            ZbtWakePolicy.Pending.EXPIRED,
            pending(requestedAtMs = at, nowMs = at + ZbtWakePolicy.PENDING_MAX_MS)
        )
        assertEquals(ZbtWakePolicy.Pending.EXPIRED, pending(requestedAtMs = at, nowMs = at - 1))
    }
}
