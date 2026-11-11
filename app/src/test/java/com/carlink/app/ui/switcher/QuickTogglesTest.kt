package com.carlink.app.ui.switcher

import com.carlink.app.switcher.ScreenDimmer
import com.carlink.core.settings.DayNight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The switcher's quick toggles cycle as their tiles say. */
class QuickTogglesTest {
    @Test fun nightModeCyclesDayNightAutomatic() {
        assertEquals(DayNight.NIGHT, nextDayNight(DayNight.DAY))
        assertEquals(DayNight.AUTOMATIC, nextDayNight(DayNight.NIGHT))
        assertEquals(DayNight.DAY, nextDayNight(DayNight.AUTOMATIC))
        assertEquals("a schedule from Settings goes on to Day", DayNight.DAY, nextDayNight(DayNight.SCHEDULE))
    }

    @Test fun dimmerCyclesBackToOffAndNeverBlocksTouches() {
        val steps = ScreenDimmer.ALPHAS.size
        assertEquals(listOf(1, 2, 3, 0), (0 until steps).map { nextDimLevel(it, steps) })
        assertEquals(0f, ScreenDimmer.ALPHAS.first())
        // Android 12+ blocks touches under an untrusted overlay more opaque than 0.8.
        assertTrue(ScreenDimmer.ALPHAS.all { it <= 0.8f })
    }
}
