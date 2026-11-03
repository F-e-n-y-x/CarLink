// CarLink: ported from open-headunit 754e6c01/390b6931 (PR #1168), zjsocket/ZjSocketPolicyTest.kt;
// the cases for the parts left out (connect cache, probe walk, ZBT-window hooks) are dropped and a
// served attempt's gap is the base delay (no JoinRefusalPolicy here).
package com.andrerinas.openheadunit.connection.zjsocket

import com.andrerinas.openheadunit.connection.zjsocket.ZjSocketPolicy.Action
import com.andrerinas.openheadunit.connection.zjsocket.ZjSocketPolicy.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZjSocketPolicyTest {

    @Test
    fun `only a connect counts as connecting`() {
        assertTrue(ZjSocketPolicy.connects(Outcome.CONNECTS))
        for (outcome in listOf(Outcome.REFUSED, Outcome.DENIED, Outcome.TIMED_OUT, Outcome.MISSING))
            assertFalse(outcome.name, ZjSocketPolicy.connects(outcome))
    }

    @Test
    fun `a permission failure is denied and any other failure is refused`() {
        assertEquals(Outcome.DENIED, ZjSocketPolicy.failureOutcome("Permission denied"))
        assertEquals(Outcome.DENIED, ZjSocketPolicy.failureOutcome("connect failed: EACCES (Permission denied)"))
        assertEquals(Outcome.REFUSED, ZjSocketPolicy.failureOutcome("Connection refused"))
        assertEquals(Outcome.REFUSED, ZjSocketPolicy.failureOutcome(null))
    }

    private fun decide(
        running: Boolean = true,
        sessionUp: Boolean = false,
        handshakeInFlight: Boolean = false,
        settling: Boolean = false,
        mayServe: Boolean = true
    ) = ZjSocketPolicy.decide(running, sessionUp, handshakeInFlight, settling, mayServe)

    @Test
    fun `a running carrier with nothing in the way dials`() {
        assertEquals(Action.DIAL, decide())
    }

    @Test
    fun `each single obstacle makes the carrier wait`() {
        assertEquals(Action.WAIT, decide(sessionUp = true))
        assertEquals(Action.WAIT, decide(handshakeInFlight = true))
        assertEquals(Action.WAIT, decide(settling = true))
        assertEquals(Action.WAIT, decide(mayServe = false))
    }

    @Test
    fun `only a carrier that is no longer running stops`() {
        assertEquals(Action.STOP, decide(running = false))
        assertEquals(Action.STOP, decide(running = false, sessionUp = true, handshakeInFlight = true))
    }

    @Test
    fun `a live session waits and never stops the carrier`() {
        for (settling in listOf(false, true))
            for (inFlight in listOf(false, true))
                for (mayServe in listOf(false, true))
                    assertEquals(Action.WAIT, decide(sessionUp = true, handshakeInFlight = inFlight, settling = settling, mayServe = mayServe))
    }

    @Test
    fun `after a served attempt the next dial waits the base delay`() {
        assertEquals(ZjSocketPolicy.BASE_RETRY_MS, ZjSocketPolicy.retryDelayMs(afterServed = true, count = 0))
        assertEquals(ZjSocketPolicy.BASE_RETRY_MS, ZjSocketPolicy.retryDelayMs(afterServed = true, count = 5))
    }

    @Test
    fun `refused connects double from the base and stop at the cap`() {
        val base = ZjSocketPolicy.BASE_RETRY_MS
        assertEquals(base, ZjSocketPolicy.retryDelayMs(afterServed = false, count = 1))
        assertEquals(base * 2, ZjSocketPolicy.retryDelayMs(afterServed = false, count = 2))
        assertEquals(base * 4, ZjSocketPolicy.retryDelayMs(afterServed = false, count = 3))
        assertEquals(base * 8, ZjSocketPolicy.retryDelayMs(afterServed = false, count = 4))
        assertEquals(ZjSocketPolicy.CONNECT_BACKOFF_CAP_MS, ZjSocketPolicy.retryDelayMs(afterServed = false, count = 5))
        assertEquals(ZjSocketPolicy.CONNECT_BACKOFF_CAP_MS, ZjSocketPolicy.retryDelayMs(afterServed = false, count = 500))
    }

    @Test
    fun `a handshake cancelled by the session-end rearm leaves the carrier running`() {
        assertFalse(ZjSocketPolicy.cancellationEndsCarrier(stopped = false, carrierActive = true))
    }

    @Test
    fun `a cancellation ends the carrier when the carrier was stopped or cancelled`() {
        assertTrue(ZjSocketPolicy.cancellationEndsCarrier(stopped = true, carrierActive = true))
        assertTrue(ZjSocketPolicy.cancellationEndsCarrier(stopped = false, carrierActive = false))
        assertTrue(ZjSocketPolicy.cancellationEndsCarrier(stopped = true, carrierActive = false))
    }
}
