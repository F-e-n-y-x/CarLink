package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterTurnGuidanceTest {
    @Test
    fun straightAheadKeepsTheCard() {
        val guidance = ClusterTurnGuidance.from(BydClusterFrame.from(BydAppleManeuver(400, 3, 0, "环城路")))
        assertEquals(9, guidance.icon)
        assertEquals(400, guidance.distanceMeters)
        assertEquals("环城路", guidance.road)
    }

    @Test
    fun unknownManeuverMustNotInventAStraightInstruction() {
        val guidance = ClusterTurnGuidance.from(BydClusterFrame.from(BydAppleManeuver(80, 0, 0)))
        assertEquals(0, guidance.icon)
        assertEquals(80, guidance.distanceMeters)
    }

    @Test
    fun routeSummarySurvivesTheOverlayHandoff() {
        val maneuver = BydAppleManeuver(53, 1, 0, "Next road", 630, 4200, 1791072000)
        val guidance = ClusterTurnGuidance.from(BydClusterFrame.from(maneuver), maneuver.arrivalEpochSeconds)
        assertEquals(630, guidance.remainingSeconds)
        assertEquals(4200, guidance.remainingMeters)
        assertEquals(1791072000L, guidance.arrivalEpochSeconds)
        val missing = ClusterTurnGuidance.from(BydClusterFrame.from(BydAppleManeuver(80, 0, 0)))
        assertEquals(-1, missing.remainingSeconds)
        assertEquals(-1, missing.remainingMeters)
        assertEquals(null, missing.arrivalEpochSeconds)
    }

    @Test
    fun leftTurnIsUnchanged() {
        val guidance = ClusterTurnGuidance.from(BydClusterFrame.from(BydAppleManeuver(53, 1, 0)))
        assertEquals(2, guidance.icon)
    }
}
