package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.orchestration.WiredStartupTimeline.Milestone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// CarLink: the always-on wired timing line format.
class WiredStartupTimelineTest {
    private var now = 0L
    private val lines = mutableListOf<String>()
    private val timeline = WiredStartupTimeline({ now }) { lines += it }

    private fun at(millis: Long) { now = millis * 1_000_000L }

    @Test fun reportsMillisecondsSincePlugAndStartSession() {
        at(1_000); timeline.mark(Milestone.IPHONE_FOUND)
        at(3_200); timeline.onIap2Progress("iap2 authentication accepted")
        at(3_400); timeline.onIap2Progress("iap2 tx=0x4301 carplay-start-session")
        at(4_100); timeline.mark(Milestone.NETWORK_CONNECTION)
        at(4_150); timeline.mark(Milestone.BULK_OUT_READY, "waited=40ms attempts=2")
        at(4_600); timeline.mark(Milestone.TCP_ACCEPTED)
        assertEquals(
            listOf(
                "WIRED_TIMING iphone-found plug+0ms session=n/a",
                "WIRED_TIMING mfi-done plug+2200ms session=n/a",
                "WIRED_TIMING start-session plug+2400ms session+0ms",
                "WIRED_TIMING network-connection plug+3100ms session+700ms",
                "WIRED_TIMING bulk-out-ready plug+3150ms session+750ms waited=40ms attempts=2",
                "WIRED_TIMING tcp-accepted plug+3600ms session+1200ms",
            ),
            lines,
        )
    }

    @Test fun eachMilestoneIsReportedOnce() {
        timeline.mark(Milestone.FIRST_FRAME)
        assertNull(timeline.mark(Milestone.FIRST_FRAME))
        timeline.onIap2Progress("iap2 rx=0x4300 carplay-availability")
        assertEquals(1, lines.size)
    }

    @Test fun aFailingSinkDoesNotBreakTheCaller() {
        val failing = WiredStartupTimeline({ now }) { throw IllegalStateException("sink") }
        assertEquals("WIRED_TIMING data-paths plug+0ms session=n/a", failing.mark(Milestone.DATA_PATHS))
    }
}
