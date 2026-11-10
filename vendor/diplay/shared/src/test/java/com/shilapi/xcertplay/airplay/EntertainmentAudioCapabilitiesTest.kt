package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class EntertainmentAudioCapabilitiesTest {
    @Test fun nativeRouteRateDeterminesTheOfferAndDecoderRate() {
        for ((rate, aacBits, pcmMusicBits) in listOf(
            Triple(44_100, 0x400000L, 0xc00L),
            Triple(48_000, 0x800000L, 0xc000L),
        )) {
            val rates = EntertainmentAudioCapabilities.sampleRatesForNativeOutput(rate)
            assertEquals(listOf(rate), rates)
            val formats = formats(rates)
            assertEquals(pcmMusicBits, bits(formats, 100) and 0xcc00L)
            for (type in listOf(102, BufferedAudioStream.STREAM_TYPE)) {
                assertEquals(aacBits, bits(formats, type))
                assertEquals(rate, AudioStreamCodec.fromFormatBits(bits(formats, type), 96, "media").sampleRate)
            }
        }
    }

    @Test fun unavailableOrUnsupportedNativeRateLetsThePhoneChooseSupportedFormats() {
        for (nativeRate in listOf(null, 0, -1, 32_000, 96_000)) {
            val rates = EntertainmentAudioCapabilities.sampleRatesForNativeOutput(nativeRate)
            assertEquals(listOf(44_100, 48_000), rates)
            val formats = formats(rates)
            assertEquals(0xcc00L, bits(formats, 100) and 0xcc00L)
            assertEquals(0xc00000L, bits(formats, 102))
            assertEquals(0xc00000L, bits(formats, BufferedAudioStream.STREAM_TYPE))
        }
    }

    @Test fun unsupportedRatesAreNeverSilentlyAdvertisedAs44100() {
        for (rates in listOf(emptyList(), listOf(32_000), listOf(44_100, 96_000))) {
            assertThrows(IllegalArgumentException::class.java) { formats(rates) }
        }
    }

    @Test fun legacySingleRateIsStillHonoredWhenCopyingAConfiguration() {
        val original = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:02", "366.0",
            main = AirPlayDisplayConfig(1280, 720))
        assertEquals(0x800000L, bits(formats(original), 102))
        assertEquals(0x400000L, bits(formats(original.copy(entertainmentSampleRate = 44_100)), 102))
        assertEquals(0x800000L, bits(formats(original.copy(entertainmentSampleRate = 48_000)), 102))
    }

    private fun formats(rates: List<Int>): List<Map<*, *>> {
        val config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:02", "366.0",
            main = AirPlayDisplayConfig(1280, 720), entertainmentSampleRates = rates, mainBufferedAudio = true)
        return formats(config)
    }

    private fun formats(config: AirPlayConfig): List<Map<*, *>> =
        (AirPlayInfoPlist.build(config)["audioFormats"] as List<*>).map { it as Map<*, *> }

    private fun bits(formats: List<Map<*, *>>, type: Int): Long =
        (formats.single { it["type"] == type && it["audioType"] == "media" }["audioOutputFormats"] as Number).toLong()
}
