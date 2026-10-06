package com.shilapi.xcertplay

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayMediaFocusForwardingTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<CarPlayController>()
    private val sinks = mutableListOf<AndroidMediaSink>()

    @After fun cleanup() {
        controllers.forEach(CarPlayMediaKeys::detach)
        sinks.forEach(AndroidMediaSink::close)
        CarPlayBackgroundSession.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun currentMediaKeyFocusReachesMatchingSinkOutsideOwnerLocks() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        val sink = spy(AndroidMediaSink()).also(sinks::add)
        doAnswer {
            assertFalse(Thread.holdsLock(CarPlayMediaKeys))
            assertFalse(Thread.holdsLock(CarPlayBackgroundSession))
            it.callRealMethod()
        }.`when`(sink).onMediaAudioFocusChanged(anyInt())
        val listener = start(controller, sink)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        verify(sink).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        verify(sink).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
    }

    @Test fun oldControllerAndOldRequestCallbacksCannotAffectReplacementSink() {
        val firstController = mock(CarPlayController::class.java).also(controllers::add)
        val firstSink = spy(AndroidMediaSink()).also(sinks::add)
        val oldListener = start(firstController, firstSink)
        val secondController = mock(CarPlayController::class.java).also(controllers::add)
        val secondSink = spy(AndroidMediaSink()).also(sinks::add)
        val currentListener = start(secondController, secondSink)
        oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        verify(firstSink, never()).onMediaAudioFocusChanged(anyInt())
        verify(secondSink, never()).onMediaAudioFocusChanged(anyInt())
        currentListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        verify(secondSink).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        CarPlayMediaKeys.detach(secondController)
        val replacement = start(secondController, secondSink)
        clearInvocations(secondSink)
        currentListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        verify(secondSink, never()).onMediaAudioFocusChanged(anyInt())
        replacement.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        verify(secondSink).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
    }

    @Test fun backgroundSessionMismatchIsNotForwarded() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        val sink = spy(AndroidMediaSink()).also(sinks::add)
        val listener = start(controller, sink)
        CarPlayBackgroundSession.clear()
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        verify(sink, never()).onMediaAudioFocusChanged(anyInt())
    }

    private fun start(controller: CarPlayController, sink: AndroidMediaSink): AudioManager.OnAudioFocusChangeListener {
        CarPlayBackgroundSession.store(controller, sink, 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) {}
        CarPlayMediaKeys.attach(app, controller)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        return ReflectionHelpers.getField<AudioFocusRequest>(CarPlayMediaKeys, "focusRequest").onAudioFocusChangeListener
    }
}
