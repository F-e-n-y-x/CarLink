package com.carlink.app.switcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge snapping, clamping clear of the system bars, and the stored position. */
class BubbleGeometryTest {
    // A 2560×1600 tablet in landscape: status bar 60px, navigation bar 90px, a 150px bubble.
    private val tablet = BubbleGeometry(2560, 1600, bubble = 150, margin = 30, insets = SafeInsets(top = 60, bottom = 90))

    @Test
    fun snapsToTheNearerEdge() {
        assertEquals(BubblePlacement(Edge.LEFT, 30, 700), tablet.snap(900, 700))
        assertEquals(BubblePlacement(Edge.RIGHT, 2560 - 30 - 150, 700), tablet.snap(1400, 700))
        // The bubble's centre decides, not its corner: corner 1204 puts the centre at 1279 < 1280.
        assertEquals(Edge.LEFT, tablet.snap(1204, 700).edge)
        assertEquals(Edge.RIGHT, tablet.snap(1206, 700).edge)
    }

    @Test
    fun staysClearOfTheSystemBarsAndCutouts() {
        assertEquals(60 + 30, tablet.snap(10, -500).y)
        assertEquals(1600 - 90 - 30 - 150, tablet.snap(10, 5000).y)
        val sideBars = BubbleGeometry(1920, 1080, bubble = 120, margin = 20, insets = SafeInsets(left = 100, right = 80, top = 40))
        assertEquals(100 + 20, sideBars.snap(0, 300).x)
        assertEquals(1920 - 80 - 20 - 120, sideBars.snap(1900, 300).x)
    }

    @Test
    fun neverRestsOverTheCentre() {
        // Wherever it is dropped, the resting bubble stays out of the middle half of the screen.
        val geometries = listOf(tablet, BubbleGeometry(1920, 1080, 85, 21), BubbleGeometry(1080, 2400, 147, 32, SafeInsets(top = 80, bottom = 63)))
        for (g in geometries) for (x in 0..g.screenWidth step 37) for (y in listOf(-100, 0, g.screenHeight / 2, g.screenHeight)) {
            val p = g.snap(x, y)
            val centreBand = (g.screenWidth / 4)..(g.screenWidth * 3 / 4)
            assertTrue("$g drop $x → ${p.x}", p.x + g.bubble <= centreBand.first || p.x >= centreBand.last)
        }
    }

    @Test
    fun dragIsKeptOnScreen() {
        assertEquals(0 to 0, tablet.clampDrag(-40, -40))
        assertEquals((2560 - 150) to (1600 - 150), tablet.clampDrag(9999, 9999))
        assertEquals(1200 to 800, tablet.clampDrag(1200, 800))
    }

    @Test
    fun storedPositionRoundTripsAndFollowsAScreenChange() {
        val placed = tablet.snap(2000, 900)
        val fraction = tablet.fractionOf(placed)
        assertEquals(placed, tablet.fromFraction(placed.edge, fraction))
        // The same fraction on a smaller screen keeps the relative height on the same edge.
        val small = BubbleGeometry(1280, 800, 75, 15, SafeInsets(top = 30, bottom = 45))
        val moved = small.fromFraction(placed.edge, fraction)
        assertEquals(Edge.RIGHT, moved.edge)
        assertEquals(1280 - 15 - 75, moved.x)
        assertTrue(moved.y in (30 + 15)..(800 - 45 - 15 - 75))
        assertEquals(fraction, small.fractionOf(moved), 0.02f)
    }

    @Test
    fun defaultIsTheRightEdgeAThirdDown() {
        val p = tablet.default()
        assertEquals(Edge.RIGHT, p.edge)
        assertEquals(BubbleGeometry.DEFAULT_FRACTION, tablet.fractionOf(p), 0.01f)
    }

    @Test
    fun tinyScreenDoesNotCrash() {
        val tiny = BubbleGeometry(100, 100, bubble = 150, margin = 30)
        val p = tiny.snap(0, 0)
        assertEquals(30, p.x)
        assertEquals(0f, tiny.fractionOf(p))
    }
}
