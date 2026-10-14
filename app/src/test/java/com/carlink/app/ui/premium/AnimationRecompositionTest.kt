package com.carlink.app.ui.premium

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The connecting animations (the dial's charge, the progress line's pulse) redraw without
 * recomposing: their values are read in the draw phase (docs/LOW_END_PERFORMANCE.md, round 2).
 * Before, both composables ran again on every animation frame (60 times a second in full
 * rendering), with the ProgressLine re-measuring its step labels each time.
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1024dp-h600dp-land-mdpi")
class AnimationRecompositionTest {
    @get:Rule val rule = createComposeRule()

    /** Counts the composable functions that run, by their trace name ("LinkDial (LinkDial.kt:73)"). */
    private class Counter : CompositionTracer {
        val runs = mutableMapOf<String, Int>()
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            val name = info.substringBefore(" (").substringAfterLast('.')
            runs[name] = (runs[name] ?: 0) + 1
        }
        override fun traceEventEnd() {}
        override fun isTraceInProgress() = true
    }

    private object Off : CompositionTracer {
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {}
        override fun traceEventEnd() {}
        override fun isTraceInProgress() = false
    }

    @After fun tracerOff() = Composer.setTracer(Off)

    private fun runsDuringOneSecond(lite: Boolean): Map<String, Int> {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CarLinkTheme(UiProfile.CAR, ThemeMode.DARK, liteRendering = lite) {
                Column {
                    LinkDial(DialReading(Color(0xFF4C8DFF), segments = 4, active = 1), Modifier.size(300.dp)) {}
                    ProgressLine(listOf("Found", "Identified", "Linked", "Picture"), active = 1, failed = false)
                }
            }
        }
        rule.mainClock.advanceTimeBy(200)
        val counter = Counter()
        Composer.setTracer(counter)
        repeat(60) { rule.mainClock.advanceTimeByFrame() }
        Composer.setTracer(Off)
        return counter.runs
    }

    @Test fun fullRenderingAnimatesWithoutRecomposing() {
        val runs = runsDuringOneSecond(lite = false)
        assertEquals("LinkDial runs", 0, runs["LinkDial"] ?: 0)
        assertEquals("ProgressLine runs", 0, runs["ProgressLine"] ?: 0)
        assertTrue("nothing recomposes while the dial charges: $runs", runs.isEmpty())
    }

    @Test fun liteRenderingAnimatesWithoutRecomposing() {
        val runs = runsDuringOneSecond(lite = true)
        assertTrue("nothing recomposes while the dial charges: $runs", runs.isEmpty())
    }
}
