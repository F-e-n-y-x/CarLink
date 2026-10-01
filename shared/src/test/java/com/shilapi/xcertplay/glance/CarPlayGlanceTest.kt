package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CarPlayGlanceTest {
    private fun frame(id: Int, block: Iap2BodyBuilder.() -> Unit) = Iap2Messages.buildRaw(id, block)

    @Before
    fun reset() {
        CarPlayGlance.listener = null
        CarPlayGlance.setConnected(true)
        CarPlayGlance.setConnected(false)
    }

    @After
    fun clear() = reset()

    @Test
    fun followsTheNextTurnAndTheSong() {
        val seen = mutableListOf<CarPlayGlance.Snapshot>()
        CarPlayGlance.listener = { seen += it }
        CarPlayGlance.setConnected(true)

        // Maneuver 7 is a right turn onto Main Street; the route update makes it the next one.
        CarPlayGlance.onFrame(frame(0x5202) { u16(1, 7); u8(3, 2); string(4, "Main Street") })
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u32(0x0a, 350); u16List(0x0d, listOf(7)) })
        CarPlayGlance.onFrame(frame(0x5001) {
            group(0) { string(1, "Numb"); string(12, "Linkin Park") }
            group(1) { u8(0, 1) }
        })

        val now = CarPlayGlance.snapshot()
        assertTrue(now.connected)
        assertEquals(2, now.maneuverType)
        assertEquals(350, now.distanceMeters)
        assertEquals("Main Street", now.road)
        assertEquals("Numb — Linkin Park", now.song)
        assertTrue(now.playing)
        assertEquals(now, seen.last())
    }

    @Test
    fun routeEndAndSessionEnd() {
        CarPlayGlance.setConnected(true)
        CarPlayGlance.onFrame(frame(0x5202) { u16(1, 1); u8(3, 1) })
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u32(0x0a, 90); u16List(0x0d, listOf(1)) })
        CarPlayGlance.onFrame(frame(0x5001) { group(0) { string(1, "Podcast") } })

        // Arrived: no turn any more, the song stays.
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 2) })
        assertNull(CarPlayGlance.snapshot().maneuverType)
        assertEquals("Podcast", CarPlayGlance.snapshot().song)

        CarPlayGlance.setConnected(false)
        assertEquals(CarPlayGlance.Snapshot(), CarPlayGlance.snapshot())
    }

    @Test
    fun otherMessagesChangeNothing() {
        CarPlayGlance.setConnected(true)
        val before = CarPlayGlance.snapshot()
        var calls = 0
        CarPlayGlance.listener = { calls++ }

        CarPlayGlance.onFrame(frame(0x4E09) { u8(1, 1) })

        assertEquals(before, CarPlayGlance.snapshot())
        assertEquals(0, calls)
    }
}
