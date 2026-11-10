package com.shilapi.xcertplay

import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test

class MusicOutputCapabilitiesTest {
    @Test fun defaultMediaRoutingQueriesTheMusicStream() {
        val queried = mutableListOf<Int>()
        val result = MusicOutputCapabilities.read(0) { stream -> queried += stream; 44_100 }
        assertEquals(listOf(AudioManager.STREAM_MUSIC), queried)
        assertEquals(listOf(44_100), result.offeredSampleRates)
        assertFalse(result.fallback)
        assertNull(result.queryFailure)
    }

    @Test fun configuredMediaStreamIsQueriedInsteadOfTheDefaultMixer() {
        for ((stream, rate) in listOf(14 to 44_100, AudioManager.STREAM_ALARM to 48_000)) {
            val queried = mutableListOf<Int>()
            val result = MusicOutputCapabilities.read(stream) { queriedStream ->
                queried += queriedStream
                rate
            }
            assertEquals(listOf(stream), queried)
            assertEquals(stream, result.streamType)
            assertEquals(rate, result.nativeSampleRate)
            assertEquals(listOf(rate), result.offeredSampleRates)
            assertFalse(result.fallback)
        }
    }

    @Test fun unknownVendorStreamRateDoesNotBorrowAnUnrelatedMixersRate() {
        val queried = mutableListOf<Int>()
        val result = MusicOutputCapabilities.read(14) { stream -> queried += stream; 0 }
        assertEquals(listOf(14), queried)
        assertTrue(result.fallback)
        assertEquals(listOf(44_100, 48_000), result.offeredSampleRates)
    }

    @Test fun queryFailureIsReportedAndLeavesSupportedFormatsToThePhone() {
        val result = MusicOutputCapabilities.read(14) { throw IllegalArgumentException("Unsupported stream") }
        assertEquals(14, result.streamType)
        assertNull(result.nativeSampleRate)
        assertEquals("IllegalArgumentException", result.queryFailure)
        assertTrue(result.fallback)
        assertEquals(listOf(44_100, 48_000), result.offeredSampleRates)
    }
}
