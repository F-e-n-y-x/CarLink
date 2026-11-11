package com.carlink.app.diagnostics

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.carlink.core.settings.AudioLevels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Diagnostics › Test call audio: records [SECONDS] s from the source CarPlay calls record with
 * (VOICE_COMMUNICATION), applies the driver's microphone level as the engines do, and plays it
 * back on the stream calls play on (a call, or media with "Call audio plays as: Media").
 */
object CallAudioTest {
    const val SECONDS = 5
    private const val RATE = 16_000

    enum class Phase { RECORDING, PLAYING }

    sealed interface Result {
        /** [peakPercent] of full scale after the gain; [clipped] when it reached full scale. */
        data class Done(val peakPercent: Int, val clipped: Boolean) : Result
        data object Silent : Result
        data object Failed : Result
    }

    @SuppressLint("MissingPermission") // The caller checks RECORD_AUDIO.
    suspend fun run(micGainDb: Int, callsAsMedia: Boolean, onPhase: (Phase) -> Unit): Result = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return@withContext Result.Failed
        val pcm = ByteArray(RATE * 2 * SECONDS)
        val recorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
        }.getOrNull()
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            return@withContext Result.Failed
        }
        try {
            withContext(Dispatchers.Main) { onPhase(Phase.RECORDING) }
            recorder.startRecording()
            var filled = 0
            while (filled < pcm.size) {
                ensureActive()
                val read = recorder.read(pcm, filled, minOf(pcm.size - filled, 3200))
                if (read < 0) return@withContext Result.Failed
                filled += read
            }
        } catch (_: IllegalStateException) {
            return@withContext Result.Failed
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        AudioLevels.scalePcm16(pcm, 0, pcm.size, AudioLevels.linear(micGainDb))
        val peak = peak(pcm)
        if (peak < SILENT_PEAK) return@withContext Result.Silent

        withContext(Dispatchers.Main) { onPhase(Phase.PLAYING) }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(if (callsAsMedia) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size)
                .build()
        }.getOrNull() ?: return@withContext Result.Failed
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            delay(SECONDS * 1000L + 300)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
        Result.Done(peak * 100 / Short.MAX_VALUE, clipped = peak >= CLIPPED_PEAK)
    }

    /** The largest absolute sample of 16-bit little-endian [pcm]. */
    fun peak(pcm: ByteArray): Int {
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            peak = maxOf(peak, abs((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)))
            i += 2
        }
        return peak
    }

    /** About -50 dBFS: below this the microphone heard nothing useful. */
    private const val SILENT_PEAK = 100
    private const val CLIPPED_PEAK = 32_600
}
