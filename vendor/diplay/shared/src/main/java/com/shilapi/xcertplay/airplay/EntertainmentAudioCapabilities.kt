package com.shilapi.xcertplay.airplay

/** Intersect the media route's native rate with the music formats implemented by CarPlay. */
object EntertainmentAudioCapabilities {
    // These are protocol/decoder capabilities, not a vehicle-specific output preference.
    val supportedSampleRates: List<Int> = listOf(44_100, 48_000)

    fun sampleRatesForNativeOutput(nativeRate: Int?): List<Int> =
        if (nativeRate in supportedSampleRates) listOf(requireNotNull(nativeRate)) else supportedSampleRates
}
