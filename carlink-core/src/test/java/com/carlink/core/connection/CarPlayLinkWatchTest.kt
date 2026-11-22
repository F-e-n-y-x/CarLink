package com.carlink.core.connection

import com.carlink.core.connection.CarPlayLinkWatch.Verdict
import com.shilapi.xcertplay.orchestration.WirelessStartupTimeline.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** The tablet's 2026-10-20 runs (live-tab.txt), in ms from the attempt's start. */
class CarPlayLinkWatchTest {
    private val w = CarPlayLinkWatch()

    @Test fun aGoodStartIsLeftAlone() { // #s6 02:32:20
        w.onMilestone(Phase.WIFI_CONFIG, 1_438); w.onMilestone(Phase.START_SESSION, 1_676)
        w.onClients(1, 0, 2_411); w.onClients(1, 1, 2_537); w.onMilestone(Phase.TCP_ACCEPTED, 2_806)
        for (t in 3_000L..60_000L step 500) assertNull(w.verdict(t))
    }

    @Test fun noStartSessionIsCaughtFourSecondsAfterTheJoin() { // #s5 02:31:34
        w.onMilestone(Phase.WIFI_CONFIG, 1_381)
        w.onClients(1, 0, 4_663)
        assertNull(w.verdict(8_000))
        assertEquals(Verdict.NO_START_REQUEST, w.verdict(8_663))
        assertNull(w.verdict(12_000)) // once per bring-up
    }

    @Test fun aSlowFirstJoinIsNotCutJustAfterIt() { // Galaxy Tab 2026-10-27 20:03:12, first try
        w.onMilestone(Phase.WIFI_CONFIG, 4_619)
        w.onMilestone(Phase.PEER_ASSOCIATED, 8_533)
        assertNull(w.verdict(9_100)) // the old count from the details fired here, 0.5 s after the join
        w.onMilestone(Phase.START_SESSION, 9_500); w.onClients(1, 1, 9_800); w.onMilestone(Phase.TCP_ACCEPTED, 12_000)
        for (t in 12_500L..30_000L step 500) assertNull(w.verdict(t))
    }

    @Test fun aLeaseWithoutTcpIsCaughtEightSecondsAfterTheLease() { // #s5 02:31:56
        w.onMilestone(Phase.WIFI_CONFIG, 1_330); w.onMilestone(Phase.START_SESSION, 1_412)
        w.onClients(1, 0, 2_212)
        assertNull(w.verdict(6_000)) // no lease yet: the generic deadline's case (a missed DHCP)
        w.onClients(1, 1, 2_330)
        assertNull(w.verdict(10_000))
        assertEquals(Verdict.NO_TCP, w.verdict(10_330))
    }

    @Test fun anIphoneThatNeverJoinsHasItsWifiOff() {
        w.onMilestone(Phase.WIFI_CONFIG, 1_000); w.onMilestone(Phase.START_SESSION, 1_100)
        assertNull(w.verdict(9_999))
        assertEquals(Verdict.NOT_JOINED, w.verdict(10_000))
    }

    @Test fun aRunningSessionsIphoneThatLeavesIsReportedAfterTheGrace() { // #s6 02:47:02
        w.onMilestone(Phase.TCP_ACCEPTED, 3_000); w.onClients(1, 1, 2_500); w.onConnected()
        w.onClients(0, 0, 60_000)
        assertNull(w.verdict(61_999))
        assertEquals(Verdict.PHONE_LEFT, w.verdict(62_000))
    }

    @Test fun aShortDropoutIsNotALeave() {
        w.onClients(1, 1, 2_500); w.onConnected()
        w.onClients(0, 0, 60_000); w.onClients(1, 1, 61_500)
        assertNull(w.verdict(70_000))
    }

    @Test fun resetStartsOver() {
        w.onMilestone(Phase.WIFI_CONFIG, 0)
        assertEquals(Verdict.NOT_JOINED, w.verdict(9_000))
        w.reset()
        assertNull(w.verdict(10_000))
        w.onMilestone(Phase.WIFI_CONFIG, 10_000)
        assertEquals(Verdict.NOT_JOINED, w.verdict(19_000))
    }

    @Test fun aStartSentBeforeTheJoinIsSentAgainThreeSecondsAfterTheLease() { // 2026-11-22 23:29:53 (#s3)
        w.onMilestone(Phase.WIFI_CONFIG, 905); w.onMilestone(Phase.START_SESSION, 939)
        w.onMilestone(Phase.PEER_ASSOCIATED, 2_741); w.onClients(1, 1, 2_800)
        assertFalse(w.startAgain(5_700))
        assertTrue(w.startAgain(5_800))
        assertFalse(w.startAgain(6_300)) // once per bring-up
        w.reset()
        w.onMilestone(Phase.START_SESSION, 100); w.onClients(1, 0, 900) // no lease reported (below Android 15)
        assertTrue(w.startAgain(3_900))
    }

    @Test fun aStartAfterTheJoinOrAConnectedOneIsNotSentAgain() {
        w.onMilestone(Phase.PEER_ASSOCIATED, 350); w.onMilestone(Phase.START_SESSION, 1_539) // 23:30:05, joined first
        for (t in 2_000L..20_000L step 500) assertFalse(w.startAgain(t))
        w.reset()
        w.onMilestone(Phase.START_SESSION, 2_275); w.onClients(1, 1, 2_834); w.onMilestone(Phase.TCP_ACCEPTED, 3_269)
        for (t in 3_500L..20_000L step 500) assertFalse(w.startAgain(t))
    }
}
