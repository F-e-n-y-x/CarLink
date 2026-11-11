package com.carlink.core.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioLevelsTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { b ->
        samples.forEachIndexed { i, s -> b[2 * i] = s.toByte(); b[2 * i + 1] = (s shr 8).toByte() }
    }

    private fun samples(b: ByteArray) = IntArray(b.size / 2) { (b[2 * it + 1].toInt() shl 8) or (b[2 * it].toInt() and 0xff) }

    @Test fun decibelsBecomeAmplitude() {
        assertEquals(1f, AudioLevels.linear(0), 0f)
        assertEquals(1.995f, AudioLevels.linear(6), 0.001f)
        assertEquals(0.251f, AudioLevels.linear(-12), 0.001f)
    }

    @Test fun androidAutoOffsetsStayInsideDiAutosRange() {
        assertEquals(0, AudioLevels.aaOffsetPercent(0))
        assertEquals(100, AudioLevels.aaOffsetPercent(6)) // 1.995 rounds to DiAuto's 2.0 ceiling
        assertEquals(41, AudioLevels.aaOffsetPercent(3))
        assertEquals(-50, AudioLevels.aaOffsetPercent(-6))
        assertEquals(-75, AudioLevels.aaOffsetPercent(-12))
        for (db in AudioLevels.PLAYBACK_DB) assertEquals(true, AudioLevels.aaOffsetPercent(db) in -100..100)
    }

    @Test fun gainScalesAndClipsAtFullScale() {
        val data = pcm(1000, -1000, 20000, -20000, 32767, -32768)
        AudioLevels.scalePcm16(data, 0, data.size, 2f)
        assertArrayEquals(intArrayOf(2000, -2000, 32767, -32768, 32767, -32768), samples(data))
    }

    @Test fun gainTouchesOnlyTheGivenRangeAndWholeSamples() {
        val data = pcm(100, 100, 100)
        AudioLevels.scalePcm16(data, 2, 3, 0.5f) // one whole sample from byte 2; the odd byte is left
        assertArrayEquals(intArrayOf(100, 50, 100), samples(data))
    }

    @Test fun zeroDecibelsLeavesTheEngineUntouched() {
        assertNull(AudioLevels.pcmGain(0))
        assertNull(AudioLevels.carPlayPlayback(0, 0, 0))
    }

    @Test fun carPlayLevelsFollowEachStreamsOwnChannel() {
        val scale = AudioLevels.carPlayPlayback(mediaDb = 6, navigationDb = -6, callDb = 0)!!
        fun out(channel: String) = pcm(1000).also { scale(channel, it, 0, it.size) }.let { samples(it)[0] }
        assertEquals(1995, out("MEDIA"))
        assertEquals(501, out("NAVIGATION"))
        assertEquals("Siri goes with navigation", 501, out("ASSISTANT"))
        assertEquals(1000, out("PHONE"))
    }
}
