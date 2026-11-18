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

    @Test fun aPictureStallWithHevcHoldsCarPlayToH264FromTheNextAttempt() {
        assertEquals(true, memory.apply(hevc).carPlayHevc)
        assertTrue(memory.onNoPicture())
        assertTrue("kept across instances (and restarts)", memory.failed)
        assertEquals(false, memory.apply(hevc).carPlayHevc)
        assertFalse("the H.264 retry's own stall is not blamed on HEVC", memory.onNoPicture())
    }

    @Test fun aStallWithoutHevcRemembersNothing() {
        assertNull(memory.apply(PicturePolicy.ENGINE_DEFAULTS).carPlayHevc) // a preset, or DiPlay's own
        assertFalse(memory.onNoPicture())
        assertFalse(memory.failed)
        assertEquals("HEVC still offered", true, memory.apply(hevc).carPlayHevc)
    }
}
