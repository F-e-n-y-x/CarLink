package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.WirelessTimeline.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// CarLink: the always-on Android Auto wireless timing line format.
class WirelessTimelineTest {
    private var now = 0L
    private val lines = mutableListOf<String>()
    private val timeline = WirelessTimeline({ now }) { lines += it }

    @Test fun timesEachPhaseFromTheBringUpsStart() {
        now = 1_000; timeline.begin("service-created")
        now = 1_050; timeline.begin("start-wireless", unlessWithinMillis = 5_000) // same bring-up
        now = 1_100; timeline.mark(Phase.WIFI_INIT, "transport=WIFI_DIRECT")
        now = 1_200; timeline.mark(Phase.GROUP_CREATE, "attempt=0")
        now = 2_300; timeline.mark(Phase.GROUP_CREATED)
        now = 2_600; timeline.mark(Phase.CREDENTIALS)
        now = 2_700; timeline.mark(Phase.POKE_START)
        now = 9_000; timeline.mark(Phase.FIRST_FRAME)
        assertEquals(
            listOf(
                "WIRELESS_TIMING begin start+0ms reason=service-created",
                "WIRELESS_TIMING wifi-init start+100ms transport=WIFI_DIRECT",
                "WIRELESS_TIMING group-create start+200ms attempt=0",
                "WIRELESS_TIMING group-created start+1300ms",
                "WIRELESS_TIMING credentials start+1600ms",
                "WIRELESS_TIMING poke-start start+1700ms",
                "WIRELESS_TIMING first-frame start+8000ms",
            ),
            lines,
        )
    }

    @Test fun eachPhaseOncePerBringUpAndALaterBeginStartsAgain() {
        now = 0; timeline.begin("start-wireless")
        timeline.mark(Phase.GROUP_CREATE, "attempt=0")
        assertNull(timeline.mark(Phase.GROUP_CREATE, "attempt=0"))
        now = 60_000; timeline.begin("poke", unlessWithinMillis = 5_000)
        now = 60_400; timeline.mark(Phase.GROUP_CREATE, "attempt=0")
        assertEquals("WIRELESS_TIMING group-create start+400ms attempt=0", lines.last())
        assertEquals(4, lines.size)
    }
}
