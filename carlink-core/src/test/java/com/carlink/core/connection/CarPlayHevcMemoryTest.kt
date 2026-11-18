package com.carlink.core.connection

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CarPlayHevcMemoryTest {
    private val memory get() = CarPlayHevcMemory(RuntimeEnvironment.getApplication())
    private val hevc = PicturePolicy.ENGINE_DEFAULTS.copy(carPlayHevc = true)

    @org.junit.Before fun reset() { CarPlayHevcMemory.hevcInUse = false; CarPlayHevcMemory.suspect = false }

    @Test fun hevcIsBlamedOnlyWhenTheH264TrialShowsAPicture() {
        assertEquals(true, memory.apply(hevc).carPlayHevc)
        assertTrue(memory.onNoPicture())
        assertFalse("a suspect is not saved", memory.failed)
        assertEquals("the next attempt tries H.264", false, memory.apply(hevc).carPlayHevc)
        memory.onPicture()
        assertTrue("kept across instances (and restarts)", memory.failed)
        assertEquals(false, memory.apply(hevc).carPlayHevc)
        assertFalse("a stall with H.264 is not blamed on HEVC", memory.onNoPicture())
    }

    @Test fun whenH264StallsTooHevcIsNotTheCause() {
        memory.apply(hevc); memory.onNoPicture()
        assertEquals(false, memory.apply(hevc).carPlayHevc)
        assertFalse(memory.onNoPicture())
        assertFalse(memory.failed)
        assertEquals("HEVC offered again", true, memory.apply(hevc).carPlayHevc)
        memory.onPicture()
        assertFalse("a working HEVC picture blames nothing", memory.failed)
    }

    @Test fun aStallWithoutHevcRemembersNothing() {
        assertNull(memory.apply(PicturePolicy.ENGINE_DEFAULTS).carPlayHevc) // a preset, or DiPlay's own
        assertFalse(memory.onNoPicture())
        assertFalse(memory.failed)
        assertEquals("HEVC still offered", true, memory.apply(hevc).carPlayHevc)
    }
}
