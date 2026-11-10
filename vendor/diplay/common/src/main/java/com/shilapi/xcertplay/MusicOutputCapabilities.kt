package com.shilapi.xcertplay

import android.media.AudioManager
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.EntertainmentAudioCapabilities

/** A snapshot for the next session's music offer; querying it never opens or reroutes a track. */
internal data class MusicOutputCapabilities(
    val streamType: Int,
    val nativeSampleRate: Int?,
    val offeredSampleRates: List<Int>,
    val queryFailure: String?,
) {
    val fallback: Boolean get() = nativeSampleRate !in offeredSampleRates

    companion object {
        fun read(
            mediaStreamOverride: Int,
            queryNativeRate: (Int) -> Int = AudioTrack::getNativeOutputSampleRate,
        ): MusicOutputCapabilities {
            // Match the existing renderer: 0 uses USAGE_MEDIA, a positive setting selects
            // a legacy stream. The primary low-latency output property can describe a
            // different mixer from this stream on automotive firmware.
            val stream = mediaStreamOverride.takeIf { it > 0 } ?: AudioManager.STREAM_MUSIC
            val query = runCatching { queryNativeRate(stream) }
            val rate = query.getOrNull()
            return MusicOutputCapabilities(stream, rate,
                EntertainmentAudioCapabilities.sampleRatesForNativeOutput(rate),
                query.exceptionOrNull()?.javaClass?.simpleName)
        }
    }
}
