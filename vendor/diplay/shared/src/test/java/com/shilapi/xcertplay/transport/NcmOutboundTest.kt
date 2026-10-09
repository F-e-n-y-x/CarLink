package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// CarLink: tests for the NCM outbound queue, write pacing and notification parser.
class NcmOutboundTest {
    private var now = 0L
    private fun advance(millis: Long) { now += millis * 1_000_000L }

    @Test fun queueKeepsOrderAndDropsOldestWhenFull() {
        val queue = NcmOutboundQueue(maxFrames = 2, maxBytes = 1_000)
        assertEquals(0, queue.offer(byteArrayOf(1)))
        assertEquals(0, queue.offer(byteArrayOf(2)))
        assertEquals(1, queue.offer(byteArrayOf(3)))
        assertEquals(2.toByte(), queue.peek()!![0])
        queue.removeHead()
        assertEquals(3.toByte(), queue.peek()!![0])
        assertEquals(1L, queue.dropped)
    }

    @Test fun queueBoundsBytes() {
        val queue = NcmOutboundQueue(maxFrames = 10, maxBytes = 10)
        queue.offer(ByteArray(6))
        assertFalse(queue.hasRoomFor(ByteArray(5)))
        assertEquals(1, queue.offer(ByteArray(5)))
        assertEquals(1, queue.size)
    }

    @Test fun beforeAnyLinkSignalAttemptsAreShortAndSpacedOut() {
        val pacing = NcmOutboundPacing { now }
        assertEquals(NcmOutboundPacing.IDLE_PROBE_MILLIS, pacing.attemptTimeoutMillis(2_000))
        assertTrue("a refused block is kept before the link was ready", pacing.onRefused())
        assertTrue(pacing.firstRefusal)
        assertEquals(NcmOutboundPacing.IDLE_PAUSE_MILLIS, pacing.pauseAfterRefusalMillis())
        assertTrue(pacing.onRefused())
        assertFalse(pacing.firstRefusal)
    }

    @Test fun linkUpSwitchesToFastAttemptsForABoundedWindow() {
        val pacing = NcmOutboundPacing { now }
        pacing.onRefused()
        pacing.onLinkSignal(true)
        assertEquals(NcmOutboundPacing.FAST_PROBE_MILLIS, pacing.attemptTimeoutMillis(2_000))
        assertEquals(NcmOutboundPacing.FAST_PAUSE_MILLIS, pacing.pauseAfterRefusalMillis())
        advance(NcmOutboundPacing.FAST_WINDOW_MILLIS)
        assertEquals(NcmOutboundPacing.IDLE_PROBE_MILLIS, pacing.attemptTimeoutMillis(2_000))
        assertEquals(NcmOutboundPacing.IDLE_PAUSE_MILLIS, pacing.pauseAfterRefusalMillis())
    }

    @Test fun firstAcceptAfterRefusalsReportsTheWaitAndAttempts() {
        val pacing = NcmOutboundPacing { now }
        pacing.onRefused()
        advance(150)
        pacing.onRefused()
        advance(50)
        val ready = pacing.onAccepted()!!
        assertEquals(200L, ready.waitedMillis)
        assertEquals(3, ready.attempts)
        assertTrue(pacing.ready)
        assertNull("only a transition reports readiness", pacing.onAccepted())
    }

    @Test fun readyLinkUsesTheCallerTimeoutAndDropsARefusedBlock() {
        val pacing = NcmOutboundPacing { now }
        pacing.onAccepted()
        assertEquals(2_000, pacing.attemptTimeoutMillis(2_000))
        assertFalse("a block refused after a full timeout may be partly sent", pacing.onRefused())
        assertFalse(pacing.ready)
        assertEquals(NcmOutboundPacing.IDLE_PROBE_MILLIS, pacing.attemptTimeoutMillis(2_000))
    }

    @Test fun linkDownClearsReadiness() {
        val pacing = NcmOutboundPacing { now }
        pacing.onAccepted()
        pacing.onLinkSignal(false)
        assertFalse(pacing.ready)
        assertEquals(NcmOutboundPacing.IDLE_PROBE_MILLIS, pacing.attemptTimeoutMillis(2_000))
    }

    @Test fun attemptTimeoutNeverExceedsTheCallerTimeout() {
        assertEquals(10, NcmOutboundPacing { now }.attemptTimeoutMillis(10))
    }

    @Test fun parsesNetworkConnectionFromIssue11() {
        // "ncm status notification code=0x00 NETWORK_CONNECTION connected=1 data=a100010004000000"
        val bytes = byteArrayOf(0xa1.toByte(), 0x00, 0x01, 0x00, 0x04, 0x00, 0x00, 0x00)
        assertEquals(listOf(NcmLinkEvent.NetworkConnection(true)), NcmNotificationParser.parse(bytes))
        bytes[2] = 0
        assertEquals(listOf(NcmLinkEvent.NetworkConnection(false)), NcmNotificationParser.parse(bytes))
    }

    @Test fun parsesSpeedChangeAndAConcatenatedConnection() {
        val speed = byteArrayOf(0xa1.toByte(), 0x2a, 0, 0, 4, 0, 8, 0) +
            le32(480_000_000) + le32(480_000_000)
        val connection = byteArrayOf(0xa1.toByte(), 0x00, 1, 0, 4, 0, 0, 0)
        assertEquals(
            listOf(NcmLinkEvent.SpeedChange(480_000_000, 480_000_000), NcmLinkEvent.NetworkConnection(true)),
            NcmNotificationParser.parse(speed + connection),
        )
    }

    @Test fun ignoresTruncatedAndUnknownNotifications() {
        assertTrue(NcmNotificationParser.parse(byteArrayOf(0xa1.toByte(), 0x00, 1)).isEmpty())
        assertTrue(NcmNotificationParser.parse(byteArrayOf(0xa1.toByte(), 0x2a, 0, 0, 4, 0, 8, 0, 1, 2)).isEmpty())
        assertTrue(NcmNotificationParser.parse(byteArrayOf(0xa1.toByte(), 0x7f, 0, 0, 4, 0, 0, 0)).isEmpty())
    }

    private fun le32(value: Long) = ByteArray(4) { (value ushr (it * 8)).toByte() }
}
