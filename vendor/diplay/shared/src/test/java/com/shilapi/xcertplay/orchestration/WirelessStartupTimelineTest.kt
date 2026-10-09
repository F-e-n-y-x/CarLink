package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.orchestration.WirelessStartupTimeline.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// CarLink: the always-on wireless timing line format.
class WirelessStartupTimelineTest {
    private var now = 0L
    private val lines = mutableListOf<String>()
    private val timeline = WirelessStartupTimeline({ now }) { lines += it }

    private fun at(millis: Long) { now = millis * 1_000_000L }

    @Test fun reportsMillisecondsSinceStartAndStartSession() {
        at(1_000); timeline.mark(Phase.CONTROLLER_START)
        at(1_100); timeline.mark(Phase.RFCOMM_DIAL)
        at(1_600); timeline.mark(Phase.HOTSPOT_READY, "family=IPv6")
        at(7_700); timeline.mark(Phase.RFCOMM_CONNECTED, "dialMs=6600 waitedMs=5800")
        at(8_000); timeline.onIap2Progress("iap2 authentication accepted")
        at(8_300); timeline.onIap2Progress("iap2 tx=0x5703 accessory-wifi-configuration")
        at(8_500); timeline.onIap2Progress("iap2 tx=0x4301 carplay-start-session")
        at(9_300); timeline.mark(Phase.PEER_ASSOCIATED, "clients=1")
        at(10_500); timeline.mark(Phase.TCP_ACCEPTED, "family=IPv6")
        assertEquals(
            listOf(
                "WIRELESS_TIMING controller-start start+0ms session=n/a",
                "WIRELESS_TIMING rfcomm-dial start+100ms session=n/a",
                "WIRELESS_TIMING hotspot-ready start+600ms session=n/a family=IPv6",
                "WIRELESS_TIMING rfcomm-connected start+6700ms session=n/a dialMs=6600 waitedMs=5800",
                "WIRELESS_TIMING authenticated start+7000ms session=n/a",
                "WIRELESS_TIMING wifi-config start+7300ms session=n/a",
                "WIRELESS_TIMING start-session start+7500ms session+0ms",
                "WIRELESS_TIMING peer-associated start+8300ms session+800ms clients=1",
                "WIRELESS_TIMING tcp-accepted start+9500ms session+2000ms family=IPv6",
            ),
            lines,
        )
    }

    @Test fun eachPhaseIsReportedOncePerBringUp() {
        timeline.mark(Phase.FIRST_FRAME)
        assertNull(timeline.mark(Phase.FIRST_FRAME))
        timeline.onIap2Progress("iap2 rx=0x4300 carplay-availability")
        timeline.onIap2Progress("iap2 tx=0x5703 post-transport accessory-wifi-configuration")
        assertEquals(1, lines.size)
    }

    @Test fun restartTimesANewBringUpFromItsOwnStart() {
        at(0); timeline.mark(Phase.CONTROLLER_START)
        at(500); timeline.onIap2Progress("iap2 tx=0x4301 carplay-start-session")
        at(20_000); timeline.restart()
        at(20_250); timeline.mark(Phase.BRING_UP)
        at(21_000); timeline.mark(Phase.START_SESSION)
        assertEquals(
            listOf(
                "WIRELESS_TIMING bring-up start+250ms session=n/a",
                "WIRELESS_TIMING start-session start+1000ms session+0ms",
            ),
            lines.drop(2),
        )
    }
}
