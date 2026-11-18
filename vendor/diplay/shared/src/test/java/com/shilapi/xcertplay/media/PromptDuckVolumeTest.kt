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

/** CarLink (#83): a navigation prompt duck and focus changes each keep their own reason to lower music. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [AudioFocusAutoYieldTest.VolumeTrackingAudioTrack::class])
class PromptDuckVolumeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private val tracks = mutableListOf<AudioTrack>()

    @Before fun reset() {
        AudioFocusAutoYieldTest.VolumeTrackingAudioTrack.volumes.clear()
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }
    @After fun cleanup() {
        tracks.forEach(AudioTrack::release)
        AudioFocusAutoYieldTest.VolumeTrackingAudioTrack.volumes.clear()
    }

    @Test fun combinedVolumeIsTheQuieterReason() {
        assertEquals(1f, AudioFocusCoordinator.combinedMediaVolume(1f, false))
        assertEquals(0.2f, AudioFocusCoordinator.combinedMediaVolume(1f, true))
        assertEquals(0f, AudioFocusCoordinator.combinedMediaVolume(0f, true))
        assertEquals(0.2f, AudioFocusCoordinator.combinedMediaVolume(0.2f, false))
    }

    @Test fun focusGainDoesNotUndoAPromptDuck() {
        val coordinator = AudioFocusCoordinator(context, true)
        val media = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        coordinator.setPromptDuck(true)
        coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(0.2f), volumes(media))
        coordinator.setPromptDuck(false)
        assertEquals(listOf(0.2f, 1f), volumes(media))
        coordinator.close()
    }

    @Test fun unduckDoesNotUndoAFocusLoss() {
        val coordinator = AudioFocusCoordinator(context, true)
        val media = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        coordinator.setPromptDuck(true)
        coordinator.setPromptDuck(false)
        assertEquals(listOf(0f), volumes(media))
        coordinator.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        coordinator.setPromptDuck(true)
        coordinator.setPromptDuck(false)
        assertEquals(listOf(0f, 0.2f), volumes(media))
        coordinator.close()
    }

    @Test fun promptDuckWorksWithFocusOffAndOnlyLowersMedia() {
        val coordinator = AudioFocusCoordinator(context, false)
        val media = track(); val phone = track(); val late = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        coordinator.acquire(phone, AudioChannel.PHONE, attributes)
        coordinator.setPromptDuck(true)
        coordinator.acquire(late, AudioChannel.MEDIA, attributes)
        coordinator.setPromptDuck(false)
        assertEquals(listOf(0.2f, 1f), volumes(media))
        assertEquals(listOf(0.2f, 1f), volumes(late))
        assertTrue(volumes(phone).isEmpty())
        assertEquals(null, shadowOf(manager).lastAudioFocusRequest)
        coordinator.close()
    }

    private fun track(): AudioTrack = AudioTrack.Builder().setAudioAttributes(attributes)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(4096).build().also(tracks::add)
    private fun volumes(track: AudioTrack): List<Float> = AudioFocusAutoYieldTest.VolumeTrackingAudioTrack.volumes[track].orEmpty()
}
