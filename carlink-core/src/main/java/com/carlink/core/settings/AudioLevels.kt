package com.carlink.core.settings

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The sound levels and microphone gain CarLink applies to both engines, and the PCM math behind
 * them. Android Auto takes the playback levels as DiAuto's own volume offsets ([aaOffsetPercent]);
 * CarPlay and both microphones go through [pcmGain], which scales 16-bit PCM in place.
 */
object AudioLevels {
    /** The choices for media, navigation and call levels. */
    val PLAYBACK_DB = listOf(-12, -9, -6, -3, 0, 3, 6)

    /** The choices for the microphone gain. */
    val MIC_DB = listOf(-6, 0, 3, 6, 9, 12)

    /** Amplitude factor for [db] (0 dB = 1.0, +6 dB ≈ 2.0, -12 dB ≈ 0.25). */
    fun linear(db: Int): Float = 10.0.pow(db / 20.0).toFloat()

    /**
     * DiAuto's volume offset (percent, gain = 1 + offset/100, which DiAuto caps at 0..2) for [db].
     * Above 1.0 DiAuto scales the PCM itself, with clipping (AudioTrackWrapper.applyGain).
     */
    fun aaOffsetPercent(db: Int): Int = ((linear(db) - 1f) * 100f).roundToInt().coerceIn(-100, 100)

    /** Scales 16-bit little-endian PCM in place by [gain], clipping at full scale. */
    fun scalePcm16(data: ByteArray, offset: Int, length: Int, gain: Float) {
        if (gain == 1f) return
        val end = offset + (length and 1.inv())
        var i = offset
        while (i < end) {
            val sample = (data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)
            val scaled = (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            data[i] = scaled.toByte()
            data[i + 1] = (scaled shr 8).toByte()
            i += 2
        }
    }

    /** A PCM scaler for [db], or null at 0 dB so the engine's path stays exactly as it was. */
    fun pcmGain(db: Int): ((ByteArray, Int, Int) -> Unit)? {
        if (db == 0) return null
        val gain = linear(db)
        return { data, offset, length -> scalePcm16(data, offset, length, gain) }
    }

    /**
     * DiPlay's playback scaler: [channel] is the stream's own kind (DiPlay's `AudioChannel` name:
     * MEDIA, NAVIGATION, ASSISTANT or PHONE). Siri goes with navigation, as Android Auto's speech
     * channel carries both directions and the assistant. Null when every level is 0 dB.
     */
    fun carPlayPlayback(mediaDb: Int, navigationDb: Int, callDb: Int): ((String, ByteArray, Int, Int) -> Unit)? {
        val media = pcmGain(mediaDb)
        val navigation = pcmGain(navigationDb)
        val call = pcmGain(callDb)
        if (media == null && navigation == null && call == null) return null
        return { channel, data, offset, length ->
            when (channel) {
                "MEDIA" -> media
                "PHONE" -> call
                else -> navigation
            }?.invoke(data, offset, length)
        }
    }
}
