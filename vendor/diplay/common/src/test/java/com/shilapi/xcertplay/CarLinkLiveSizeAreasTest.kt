package com.shilapi.xcertplay

// CarLink (new file): the interface-size variants of CarPlayViewAreas (live size changes).

import com.shilapi.xcertplay.CarPlayViewAreas.Kind
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_BOTTOM
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarLinkLiveSizeAreasTest {
    private val widths = listOf(350, 300, 220)

    private fun square(sessionMm: Int = 300, dock: CarPlayDock = CarPlayDock.AUTOMATIC, sidePanel: Boolean = false) =
        CarPlayViewAreas.build(1920, 1920, listOf(
            CarPlayViewAreas.Screen(1920, 1200, portrait = false),
            CarPlayViewAreas.Screen(1200, 1920, portrait = true),
        ), dock, splitWindow = { null }, startPortrait = false, sidePanel = sidePanel,
            sizeWidthsMm = widths, sessionWidthMm = sessionMm)!!

    @Test
    fun aMediumSessionDeclaresALargeAreaPerScreenAfterItsOwn() {
        val areas = square()
        assertEquals(listOf(1920 to 1200, 1200 to 1920, 1408 to 880, 880 to 1408), areas.areas.map { it.width to it.height })
        assertEquals(0, areas.current)
        assertEquals(1f, areas.sizeFactor)
    }

    @Test
    fun aSmallSessionDeclaresMediumAndLarge() {
        val areas = square(sessionMm = 350)
        assertEquals(6, areas.areas.size)
        assertEquals(1644 to 1028, areas.areas[areas.sizeVariant(300)!!].let { it.width to it.height })
        assertEquals(1206 to 754, areas.areas[areas.sizeVariant(220)!!].let { it.width to it.height })
    }

    @Test
    fun aLargerWidthThanTheSessionsHasNoArea() {
        val areas = square()
        assertNull(areas.sizeVariant(350))
        assertEquals(0, areas.sizeVariant(300))
        assertEquals(2, square(sessionMm = 220).areas.size) // a Large session declares no variants
    }

    @Test
    fun theChosenSizeSurvivesATurnAndGoesBack() {
        val areas = square()
        areas.use(areas.sizeVariant(220)!!)
        val portrait = areas.indexFor(1200, 1920, splitScreen = false, portrait = true)!!
        assertEquals(880 to 1408, areas.areas[portrait].let { it.width to it.height })
        areas.use(portrait)
        assertEquals(1, areas.sizeVariant(300))
        areas.use(1)
        assertEquals(1, areas.indexFor(1200, 1920, splitScreen = false, portrait = true))
        assertEquals(0, areas.indexFor(1920, 1200, splitScreen = false, portrait = false))
    }

    @Test
    fun theDockKeepsTheSizeAndTheSidePanelKeepsItsOwnLayout() {
        val areas = square(dock = CarPlayDock.DRIVER_SIDE, sidePanel = true)
        val large = areas.sizeVariant(220)!!
        assertEquals(DOCK_EDGE_DRIVER_SIDE, areas.areas[large].dockEdge)
        areas.use(large)
        val moved = areas.withDock(DOCK_EDGE_BOTTOM)!!
        assertEquals(1408 to 880, areas.areas[moved].let { it.width to it.height })
        assertEquals(DOCK_EDGE_BOTTOM, areas.areas[moved].dockEdge)
        val side = areas.sidePanel()!!
        assertEquals(1920 to 1200, areas.layoutArea(side).let { it.width to it.height })
        areas.use(side)
        assertEquals(Kind.SIDE_PANEL, areas.kindOf(areas.current))
        assertNull(areas.sizeVariant(300)) // beside the side panel the size reconnects
    }

    @Test
    fun sizeVariantsNeverMakeAPlainCanvasDeclareAreas() {
        assertNull(CarPlayViewAreas.build(2560, 1440, CarPlayDock.AUTOMATIC, splitWindow = null,
            sizeWidthsMm = widths, sessionWidthMm = 300))
        val docked = CarPlayViewAreas.build(2560, 1440, CarPlayDock.BOTTOM, splitWindow = null,
            sizeWidthsMm = widths, sessionWidthMm = 300)!!
        assertEquals(1, docked.current)
        assertEquals(1876 to 1056, docked.areas[docked.sizeVariant(220)!!].let { it.width to it.height })
    }
}
