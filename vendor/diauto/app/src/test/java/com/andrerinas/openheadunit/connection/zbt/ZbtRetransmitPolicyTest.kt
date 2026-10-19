// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0), app/src/test/.../nativeaa/zbt/ZbtRetransmitPolicyTest.kt
package com.andrerinas.openheadunit.connection.zbt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZbtRetransmitPolicyTest {

    private val gap = ZbtRetransmitPolicy.INTERVAL_MS

    private fun shouldResend(
        enabled: Boolean = true,
        phoneHasAnswered: Boolean = false,
        lastSentAtMs: Long = 1_000L,
        nowMs: Long = 1_000L + ZbtRetransmitPolicy.INTERVAL_MS
    ) = ZbtRetransmitPolicy.shouldResend(enabled, phoneHasAnswered, lastSentAtMs, nowMs)

    @Test
    fun `the ordinary route never repeats`() {
        // An accept proves the phone is there, so a message that went out arrived.
        assertFalse(shouldResend(enabled = false))
        assertFalse(shouldResend(enabled = false, nowMs = 1_000L + gap * 100))
    }

    @Test
    fun `one answer from the phone retires it`() {
        assertFalse(shouldResend(phoneHasAnswered = true))
        assertFalse(shouldResend(phoneHasAnswered = true, nowMs = 1_000L + gap * 100))
    }

    @Test
    fun `nothing is repeated before the first copy has gone out`() {
        // Also what keeps this inert when the version exchange is switched off.
        assertFalse(shouldResend(lastSentAtMs = 0L))
        assertFalse(shouldResend(lastSentAtMs = 0L, nowMs = gap * 100))
    }

    @Test
    fun `a repeat waits out the interval`() {
        assertFalse(shouldResend(nowMs = 1_000L))
        assertFalse(shouldResend(nowMs = 1_000L + gap - 1))
        assertTrue(shouldResend(nowMs = 1_000L + gap))
        assertTrue(shouldResend(nowMs = 1_000L + gap * 4))
    }

    @Test
    fun `a clock that moved backwards reads as too soon`() {
        assertFalse(shouldResend(lastSentAtMs = 10_000L, nowMs = 1_000L))
    }
}
