// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0), app/src/test/.../nativeaa/blink/SplitFramesTest.kt
package com.andrerinas.openheadunit.connection.blink

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitFramesTest {

    @Test
    fun `whole frames are split and a partial one is left`() {
        val bytes = BlinkAutoLine.decodeHex("000200060800" + "00000002" + "0007")!!
        val (frames, used) = splitFrames(bytes)
        assertEquals(listOf("000200060800", "00000002"), frames.map { BlinkAutoLine.toHex(it) })
        assertEquals(10, used)
    }

    @Test
    fun `nothing whole means nothing sent`() {
        val (frames, used) = splitFrames(BlinkAutoLine.decodeHex("000900090800")!!)
        assertEquals(0, frames.size)
        assertEquals(0, used)
    }
}
