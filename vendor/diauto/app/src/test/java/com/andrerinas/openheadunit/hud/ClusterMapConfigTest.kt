package com.andrerinas.openheadunit.hud

import com.andrerinas.openheadunit.hud.ClusterMapConfig.Detail
import com.andrerinas.openheadunit.hud.ClusterMapConfig.Size
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterMapConfigTest {

    private fun profile(
        size: Size = Size.NORMAL, detail: Detail = Detail.NORMAL,
        x: Int = 0, y: Int = 0,
    ) = ClusterMapConfig.profile(size, detail, x, y)

    @Test
    fun `normal size draws the 8 to 3 panel inside a 1080p frame`() {
        val p = profile()
        assertEquals(ClusterMapConfig.RES_1920x1080, p.codecResolution)
        assertEquals(1920, p.contentWidth)
        assertEquals(720, p.contentHeight)
        assertEquals(0, p.widthMargin)
        assertEquals(360, p.heightMargin)
        assertEquals(ClusterMapConfig.Insets(180, 180, 0, 0), p.margins)
    }

    @Test
    fun `large size uses a 720p frame for a 1280x480 picture`() {
        val p = profile(size = Size.LARGE)
        assertEquals(ClusterMapConfig.RES_1280x720, p.codecResolution)
        assertEquals(1280, p.contentWidth)
        assertEquals(480, p.contentHeight)
        assertEquals(240, p.heightMargin)
        assertEquals(ClusterMapConfig.Insets(120, 120, 0, 0), p.margins)
    }

    @Test
    fun `detail sets the density`() {
        assertEquals(120, profile(detail = Detail.SMALL).density)
        assertEquals(160, profile().density)
        assertEquals(240, profile(detail = Detail.EXTRA_LARGE).density)
        assertEquals(213, profile(size = Size.LARGE, detail = Detail.LARGE).density)
    }

    @Test
    fun `default safe area matches the small navi window`() {
        val i = profile().contentInsets
        assertEquals(1920 * 35 / 100, i.left)
        assertEquals(1920 * 36 / 100, i.right)
        assertEquals(720 * 16 / 100, i.top)
        assertEquals(720 * 25 / 100, i.bottom)
    }

    @Test
    fun `marker steps move the safe area without resizing it`() {
        val centre = ClusterMapConfig.insets(1920, 720, 0, 0)
        val right = ClusterMapConfig.insets(1920, 720, 1, 0)
        assertEquals(centre.left + 192, right.left)
        assertEquals(centre.right - 192, right.right)
        val up = ClusterMapConfig.insets(1920, 720, 0, -1)
        assertEquals(centre.top - 72, up.top)
        assertEquals(centre.bottom + 72, up.bottom)
    }

    @Test
    fun `marker steps are clamped and never leave the panel`() {
        for (x in -10..10) for (y in -10..10) {
            val i = ClusterMapConfig.insets(1920, 720, x, y)
            assertTrue("x=$x y=$y $i", i.left >= 0 && i.right >= 0 && i.top >= 0 && i.bottom >= 0)
            assertTrue("x=$x y=$y $i", i.left + i.right < 1920 && i.top + i.bottom < 720)
        }
        assertEquals(ClusterMapConfig.insets(1920, 720, 3, 2), ClusterMapConfig.insets(1920, 720, 9, 9))
    }

    @Test
    fun `crop shows only the drawn content`() {
        // 1920x1080 buffer on a 1920x720 view: the 1080 rows must stretch so the middle 720 fill it.
        val m = ClusterMapConfig.cropMatrix(profile(), 1920, 720)
        assertArrayEquals(floatArrayOf(1f, 1.5f, 0f, -180f), m, 0.001f)
        // Content row 180 (top of the picture) lands on view row 0, row 900 on view row 720.
        val default = 720f / 1080f // view pixels per buffer row before the transform
        assertEquals(0f, 180 * default * m[1] + m[3], 0.01f)
        assertEquals(720f, 900 * default * m[1] + m[3], 0.01f)
    }

    @Test
    fun `crop of the large stream on a 1280x480 panel`() {
        val m = ClusterMapConfig.cropMatrix(profile(size = Size.LARGE), 1280, 480)
        assertArrayEquals(floatArrayOf(1f, 1.5f, 0f, -120f), m, 0.001f)
    }

    @Test
    fun `wider layouts widen the safe area around the same centre`() {
        val wide = ClusterMapConfig.profile(Size.NORMAL, Detail.NORMAL, 0, 0, ClusterMapConfig.Width.WIDE)
        assertEquals(1920 * 16 / 100, wide.contentInsets.left)
        assertEquals(1920 * 16 / 100, wide.contentInsets.right)
        assertEquals(profile().contentInsets.top, wide.contentInsets.top)
        val full = ClusterMapConfig.insets(1920, 720, 0, 0, ClusterMapConfig.Width.FULL)
        assertEquals(1920 * 3 / 100, full.left)
    }

    @Test
    fun `a wide safe area still moves with the marker and stays on the panel`() {
        for (x in -10..10) {
            val i = ClusterMapConfig.insets(1920, 720, x, 0, ClusterMapConfig.Width.FULL)
            assertTrue("x=$x $i", i.left >= 0 && i.right >= 0 && i.left + i.right < 1920)
        }
        val centre = ClusterMapConfig.insets(1920, 720, 0, 0, ClusterMapConfig.Width.WIDE)
        val left = ClusterMapConfig.insets(1920, 720, -1, 0, ClusterMapConfig.Width.WIDE)
        assertEquals(centre.left - 192, left.left)
    }

    @Test
    fun `unknown stored values fall back to defaults`() {
        assertEquals(ClusterMapConfig.Width.COMPACT, ClusterMapConfig.Width.of(7))
        assertEquals(Size.NORMAL, Size.of(-1))
        assertEquals(Detail.NORMAL, Detail.of(7))
    }

    @Test
    fun `measured DiLink 5_1 windows are the ones DiPlay measured`() {
        assertEquals(ClusterMapConfig.Window(0, 144, 1920, 480, true), ClusterMapConfig.window(ClusterMapConfig.Theme.MAP))
        assertEquals(ClusterMapConfig.Window(1320, 0, 600, 720, false), ClusterMapConfig.window(ClusterMapConfig.Theme.SCENARIO))
        assertEquals(ClusterMapConfig.window(ClusterMapConfig.Theme.SCENARIO), ClusterMapConfig.window(ClusterMapConfig.Theme.SIMPLE))
        assertTrue(ClusterMapConfig.measured("BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys"))
        assertTrue(!ClusterMapConfig.measured("other"))
    }

    @Test
    fun `one safe area for every theme, and the map slides into each window`() {
        val side = ClusterMapConfig.window(ClusterMapConfig.Theme.SCENARIO)
        val map = ClusterMapConfig.window(ClusterMapConfig.Theme.MAP)
        // The phone is told the same safe area whatever the theme, so a switch needs nothing from it.
        assertEquals(ClusterMapConfig.insets(1920, 720, 0, 0, window = side), ClusterMapConfig.insets(1920, 720, 0, 0, window = map))
        val i = ClusterMapConfig.insets(1920, 720, 0, 0, window = side)
        val carX = i.left + (1920 - i.left - i.right) / 2
        val carY = i.top + (720 - i.top - i.bottom) / 2
        assertEquals(960, carX)
        assertEquals(360, carY)
        assertTrue("cards fit the side window", 1920 - i.left - i.right <= side.width)
        // Slid by mapOffset, the car lands in the middle of each window.
        for (w in listOf(side, map)) {
            val (dx, dy) = ClusterMapConfig.mapOffset(w)
            assertEquals(w.left + w.width / 2, carX + dx)
            assertEquals(w.top + w.height / 2, carY + dy)
        }
    }

    @Test
    fun `the turn card stays inside the window it belongs to`() {
        val side = ClusterMapConfig.window(ClusterMapConfig.Theme.SCENARIO)
        val card = ClusterTurnCardPlacement.card(1920, 720, side)!!
        assertTrue(card.left >= side.left && card.left + card.width <= side.left + side.width)
        val map = ClusterMapConfig.window(ClusterMapConfig.Theme.MAP)
        val mapCard = ClusterTurnCardPlacement.card(1920, 720, map)!!
        assertTrue(mapCard.top >= map.top && mapCard.top + mapCard.height <= map.top + map.height)
        assertNull(ClusterTurnCardPlacement.card(0, 720, null))
    }

    @Test
    fun `a map on another theme's layer hides, so Scenario is never covered`() {
        // Full-map layer: only in the Map theme.
        assertTrue(ClusterMapConfig.shownOnLayer(ClusterMapConfig.Theme.MAP, true, fullMapLayer = true))
        assertTrue(!ClusterMapConfig.shownOnLayer(ClusterMapConfig.Theme.SCENARIO, false, fullMapLayer = true))
        assertTrue(!ClusterMapConfig.shownOnLayer(ClusterMapConfig.Theme.SCENARIO, true, fullMapLayer = true))
        // Side layer: Scenario or Simple, and only with the map card open.
        assertTrue(ClusterMapConfig.shownOnLayer(ClusterMapConfig.Theme.SCENARIO, true, fullMapLayer = false))
        assertTrue(!ClusterMapConfig.shownOnLayer(ClusterMapConfig.Theme.SCENARIO, false, fullMapLayer = false))
        assertTrue(!ClusterMapConfig.shownOnLayer(ClusterMapConfig.Theme.MAP, true, fullMapLayer = false))
        // Nothing known: as without Usage access.
        assertTrue(ClusterMapConfig.shownOnLayer(null, false, fullMapLayer = true))
    }
}
