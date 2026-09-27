package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayClusterDisplayTest {
    @Test
    fun defaultClusterIsAdvertisedAsAnInputlessMapScreen() {
        val cluster = CarPlayClusterDisplay.config(
            1920,
            720,
            CarPlayClusterDisplay.DEFAULT_LIFT_PERCENT,
            CarPlayClusterDisplay.DEFAULT_SHIFT_PERCENT,
        )
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
                cluster = cluster,
            ),
        )

        val displays = info["displays"] as List<*>
        assertEquals(2, displays.size)
        val alt = displays[1] as Map<*, *>
        assertEquals(111, alt["type"])
        assertEquals(1920, alt["widthPixels"])
        assertEquals(720, alt["heightPixels"])
        assertEquals(0, alt["features"])
        assertEquals(0, alt["primaryInputDevice"])
        assertEquals("maps:/car/instrumentcluster/map", alt["initialURL"])
        assertEquals(292, alt["widthPhysical"])
        assertEquals(110, alt["heightPhysical"])

        val view = (alt["viewAreas"] as List<*>).single() as Map<*, *>
        assertEquals(1920, view["widthPixels"])
        assertEquals(720, view["heightPixels"])
        val safe = view["safeArea"] as Map<*, *>
        // 25 % off the bottom and 22 % off the right: the car sits higher and about 1/9 further left.
        assertEquals(1498, safe["widthPixels"])
        assertEquals(540, safe["heightPixels"])
        assertEquals(0, safe["originXPixels"])
        assertEquals(0, safe["originYPixels"])
        assertEquals(true, safe["drawUIOutsideSafeArea"])

        // The main screen keeps its touch and knob features.
        assertEquals(0x0A, (displays[0] as Map<*, *>)["features"])
    }

    @Test
    fun noOffsetsLeaveTheWholeClusterAsTheSafeArea() {
        val cluster = CarPlayClusterDisplay.config(1920, 720, 0, 0)

        assertNull(cluster.safeArea)
    }

    @Test
    fun offsetsAreBoundedSoTheSafeAreaNeverCollapses() {
        val cluster = CarPlayClusterDisplay.config(1000, 400, 150, -20)

        assertEquals(AirPlayInsets(bottom = 360, right = 0), cluster.safeArea)
    }

    @Test
    fun defaultsAreAmongTheOfferedChoices() {
        assertTrue(CarPlayClusterDisplay.DEFAULT_LIFT_PERCENT in CarPlayClusterDisplay.liftPresets)
        assertTrue(CarPlayClusterDisplay.DEFAULT_SHIFT_PERCENT in CarPlayClusterDisplay.shiftPresets)
    }
}
