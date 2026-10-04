package com.shilapi.xcertplay

import org.robolectric.RuntimeEnvironment
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en")
class ClusterTurnCardSummaryTest {
    @Test fun missingRouteTotalsStayBlankAndDurationRoundsUp() {
        val view = ClusterTurnCardView(RuntimeEnvironment.getApplication())
        assertEquals("", view.routeSummary(ClusterTurnGuidance(2, 0, 80, "Road")))
        assertTrue(view.routeSummary(ClusterTurnGuidance(2, 0, 80, "Road", 4200, 61)).contains("2 min"))
        assertTrue(view.routeSummary(ClusterTurnGuidance(2, 0, 80, "Road", 4200, 61)).contains("4.2"))
    }

    @Test fun arrivalUsesThePhonesTimestampAndClearingHidesTheCard() {
        val view = ClusterTurnCardView(RuntimeEnvironment.getApplication())
        val guidance = ClusterTurnGuidance(2, 0, 80, "Road", -1, 61, 1791072000L)
        val summary = view.routeSummary(guidance)
        assertTrue(summary.startsWith("Arrival "))
        assertFalse(summary.contains("min left"))
        view.setGuidance(guidance)
        assertEquals(android.view.View.VISIBLE, view.visibility)
        view.setGuidance(null)
        assertEquals(android.view.View.GONE, view.visibility)
    }
}
