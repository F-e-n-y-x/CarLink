package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [AudioFocusAutoYieldTest.VolumeTrackingAudioTrack::class])
class AudioFocusAutoYieldTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var audioManager: AudioManager
    private lateinit var track: AudioTrack
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()

    @Before fun setUp() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(44100)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(4096)
            .build()
        VolumeTrackingAudioTrack.recordedVolumes.clear()
    }

    @After fun tearDown() {
        track.release()
        VolumeTrackingAudioTrack.recordedVolumes.clear()
    }

    @Test fun autoYieldMutesOnLossTransientAndRestoresOnGain() {
        val coordinator = AudioFocusCoordinator(context, enabled = true, autoYieldOnCall = true)
        coordinator.acquire(track, AudioChannel.MEDIA, attributes)

        // When call arrives (loss transient), volume is muted (0f)
        coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(listOf(0f), VolumeTrackingAudioTrack.recordedVolumes)

        // When call ends (gain), volume is restored to full (1f)
        coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(0f, 1f), VolumeTrackingAudioTrack.recordedVolumes)
    }

    @Test fun autoYieldDisabledDoesNotMuteOnLossTransient() {
        val coordinator = AudioFocusCoordinator(context, enabled = true, autoYieldOnCall = false)
        coordinator.acquire(track, AudioChannel.MEDIA, attributes)

        // When autoYield is disabled, loss transient does not mute
        coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(VolumeTrackingAudioTrack.recordedVolumes.isEmpty())
    }

    @Test fun explicitCallEndedSignalRestoresMutedVolume() {
        val coordinator = AudioFocusCoordinator(context, enabled = true, autoYieldOnCall = true)
        coordinator.acquire(track, AudioChannel.MEDIA, attributes)

        coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(listOf(0f), VolumeTrackingAudioTrack.recordedVolumes)

        // Safety fallback: explicit onCallEnded restores volume even without AUDIOFOCUS_GAIN
        coordinator.onCallEnded()
        assertEquals(listOf(0f, 1f), VolumeTrackingAudioTrack.recordedVolumes)
    }

    @Implements(AudioTrack::class)
    class VolumeTrackingAudioTrack {
        companion object {
            val recordedVolumes = mutableListOf<Float>()
        }

        @Implementation
        fun setStereoVolume(leftGain: Float, rightGain: Float): Int {
            recordedVolumes.add(leftGain)
            return AudioTrack.SUCCESS
        }
    }
}
