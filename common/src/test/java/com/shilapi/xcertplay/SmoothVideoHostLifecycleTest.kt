package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Host-level lifecycle of Smooth video: which retained session a host may adopt, how a host that rebuilds
 * its video view hands the session slot on, and which sinks a destroyed SurfaceView is detached from while
 * sessions are being torn down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class SmoothVideoHostLifecycleTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var controllerConstruction: MockedConstruction<CarPlayController>
    private val display = CarPlaySessionDisplay(1920, 990, Surface.ROTATION_0, true, true, 1920, 990)
    private val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
    private val ownedSinks = mutableListOf<AndroidMediaSink>()
    private val surfaces = mutableListOf<Pair<SurfaceTexture, Surface>>()

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        AirPlayPersistence.saveAdaptPipResolution(activity, false)
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.USB_CH341)
        // Host startup without vendor-service workers or real transports.
        controllerConstruction = mockConstruction(CarPlayController::class.java)
        (getField("teardownExecutor") as ExecutorService).shutdownNow()
        setField("teardownExecutor", PausedExecutorService())
        setField("activeDisplaySize", size(1920, 990))
    }

    @After fun tearDown() {
        (getField("shuttingDown") as AtomicBoolean).set(true)
        (getField("mainHandler") as Handler).removeCallbacksAndMessages(null)
        AirPlayPersistence.overlaySettingsListener = null
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setTurnOverlayListener(null)
        (getField("controller") as? CarPlayController)?.let { CarPlayMediaKeys.detach(it) }
        (getField("sink") as? AndroidMediaSink)?.close()
        ownedSinks.forEach { it.close() }
        surfaces.forEach { (texture, surface) -> surface.release(); texture.release() }
        (getField("teardownExecutor") as ExecutorService).shutdownNow()
        (getField("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.saveSmoothVideo(activity, false)
        controllerConstruction.close()
    }

    // (1) The maintainer's sequence: Smooth video turned on, the reconnect stopped at a prerequisite, so
    // the old host still runs a session whose sink does not pace. A new host built with smooth video must
    // stop that session, not adopt it, and then start its own.
    @Test fun aNewSmoothHostStopsANonPacingBackgroundSessionOnceAndThenStartsItsOwn() {
        AirPlayPersistence.saveSmoothVideo(activity, true)
        setField("smoothVideo", true)
        allowStartup()
        val oldController = mock(CarPlayController::class.java)
        val oldSink = sink(pacingDelayMillis = 0)
        val stop = DeferredStop()
        CarPlayBackgroundSession.store(oldController, oldSink, 1920, 990, Any(), display, stop::invoke)

        assertEquals(false, invoke("adoptBackgroundSession"))
        assertNull("A non-pacing sink must not be adopted by a smooth video view", getField("sink"))
        assertNull(getField("controller"))
        assertEquals(1, stop.calls)

        // The non-owner path polls every 500 ms while the other host is still stopping.
        invoke("maybeStartCarPlay")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_100))
        assertEquals("One stop request per retained session, however often the host retries", 1, stop.calls)
        assertNull(getField("sink"))
        assertNull(getField("controller"))
        assertTrue(CarPlayBackgroundSession.hasSession())

        // The old host finishes its shutdown (which clears the slot) and completes the stop.
        stop.complete()
        shadowOf(Looper.getMainLooper()).idle()

        val started = getField("sink") as? AndroidMediaSink
        assertNotNull("Once the old session stops, this host starts its own", started)
        assertNotSame(oldSink, started)
        assertTrue("The new session paces, matching this host's view", started!!.videoPacingEnabled)
        assertNotNull(getField("controller"))
        assertNotSame(oldController, getField("controller"))
        assertEquals(1, stop.calls)
    }

    @Test fun aBackgroundSessionWhosePacingMatchesTheViewIsAdopted() {
        for (smooth in listOf(true, false)) {
            CarPlayBackgroundSession.clear()
            setField("controller", null)
            setField("sink", null)
            AirPlayPersistence.saveSmoothVideo(activity, smooth)
            setField("smoothVideo", smooth)
            val controller = mock(CarPlayController::class.java)
            val sink = sink(pacingDelayMillis = if (smooth) smoothVideoDelayMillis(60) else 0)
            val stop = DeferredStop()
            CarPlayBackgroundSession.store(controller, sink, 1920, 990, Any(), display, stop::invoke)

            assertEquals("smooth=$smooth", true, invoke("adoptBackgroundSession"))
            assertSame(sink, getField("sink"))
            assertSame(controller, getField("controller"))
            assertTrue(CarPlayBackgroundSession.isOwner(activity))
            assertEquals(0, stop.calls)
        }
    }

    // (2) The recreate branch: the host that owns the session reconnects for a changed Smooth video
    // setting. Its restart keeps it as owner; before it recreates itself it must free the slot, or the new
    // instance (another object) would only poll a session that no longer exists.
    @Test fun aHostRebuildingItsVideoViewReleasesTheSessionSlotFirst() {
        setField("smoothVideo", false)
        allowStartup()
        val controller = mock(CarPlayController::class.java)
        val sink = sink(pacingDelayMillis = 0)
        setField("controller", controller)
        setField("sink", sink)
        val stop = DeferredStop()
        CarPlayBackgroundSession.store(controller, sink, 1920, 990, activity, display, stop::invoke)
        AirPlayPersistence.saveSmoothVideo(activity, true)

        restartCarPlay("Smooth video changed")
        assertTrue("A restart keeps the host as the owner", CarPlayBackgroundSession.isOwner(activity))
        assertTrue(CarPlayBackgroundSession.hasSession())

        finishTeardown()

        assertNull(getField("controller"))
        assertFalse("The rebuilding host must not keep the session slot", CarPlayBackgroundSession.isOwner(activity))
        assertFalse(CarPlayBackgroundSession.hasSession())
        assertEquals(0, stop.calls)

        // So the rebuilt host starts its own session instead of waiting for one.
        val rebuilt = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            (getField(rebuilt, "teardownExecutor") as ExecutorService).shutdownNow()
            setField(rebuilt, "teardownExecutor", PausedExecutorService())
            setField(rebuilt, "activeDisplaySize", size(1920, 990))
            setField(rebuilt, "smoothVideo", true)
            allowStartup(rebuilt)
            invoke(rebuilt, "maybeStartCarPlay")
            val started = getField(rebuilt, "sink") as? AndroidMediaSink
            assertNotNull(started)
            assertTrue(started!!.videoPacingEnabled)
            assertTrue(CarPlayBackgroundSession.isOwner(rebuilt))
        } finally {
            (getField(rebuilt, "shuttingDown") as AtomicBoolean).set(true)
            (getField(rebuilt, "mainHandler") as Handler).removeCallbacksAndMessages(null)
            (getField(rebuilt, "controller") as? CarPlayController)?.let { CarPlayMediaKeys.detach(it) }
            (getField(rebuilt, "sink") as? AndroidMediaSink)?.close()
            (getField(rebuilt, "teardownExecutor") as ExecutorService).shutdownNow()
            (getField(rebuilt, "airPlayCommandExecutor") as ExecutorService).shutdownNow()
        }
    }

    // (3) A shutdown while a restart's teardown is still closing must not drop the restart's sink: until
    // its decoders release their codecs it may still render to the SurfaceView being destroyed.
    @Test fun aShutdownDuringARestartKeepsTheRestartsSinkDetachable() {
        val controller = mock(CarPlayController::class.java)
        val restarting = spy(AndroidMediaSink()).also(ownedSinks::add)
        setField("controller", controller)
        setField("sink", restarting)
        CarPlayBackgroundSession.store(controller, restarting, 1920, 990, activity, display) { it() }

        restartCarPlay("Test reconnect")
        assertEquals(setOf(restarting), retiringSinks())
        shutdown("Test exit")
        assertTrue("The restart's sink is still closing", restarting in retiringSinks())

        val surface = surface()
        val holder = mock(SurfaceHolder::class.java)
        `when`(holder.surface).thenReturn(surface)
        (getField("fallbackSurfaceCallback") as SurfaceHolder.Callback).surfaceDestroyed(holder)
        verify(restarting).beginSurfaceDetach(surface, false)

        finishTeardown()
        assertTrue("Each sink leaves once its decoders have released their codecs", retiringSinks().isEmpty())
    }

    private fun sink(pacingDelayMillis: Int) =
        AndroidMediaSink(videoPacingDelayMillis = pacingDelayMillis).also(ownedSinks::add)

    private fun surface(): Surface {
        val texture = SurfaceTexture(0)
        return Surface(texture).also { surfaces += texture to it }
    }

    /** A retained session's stop action that completes only when the test says the old host is done. */
    private class DeferredStop {
        var calls = 0
        private var done: (() -> Unit)? = null
        fun invoke(completion: () -> Unit) { calls++; done = completion }
        fun complete() {
            CarPlayBackgroundSession.clear()
            done!!.invoke()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun retiringSinks(): Set<AndroidMediaSink> = (getField("retiringSinks") as Set<AndroidMediaSink>).toSet()

    private fun restartCarPlay(reason: String) {
        activity.javaClass.getDeclaredMethod("restartCarPlay", String::class.java)
            .apply { isAccessible = true }.invoke(activity, reason)
    }

    private fun shutdown(reason: String) {
        activity.javaClass.getDeclaredMethod("shutdown", Boolean::class.javaPrimitiveType, String::class.java,
            Function0::class.java).apply { isAccessible = true }.invoke(activity, false, reason, {})
    }

    private fun allowStartup(target: CarPlayHostActivity = activity) {
        setField(target, "airPlayIdentity", AirPlayIdentity.generate())
        setField(target, "mfiTarget", MfiTarget.LOCAL)
        setField(target, "vpnReady", true)
        setField(target, "microphonePermissionResolved", true)
    }

    private fun finishTeardown() {
        (getField("teardownExecutor") as PausedExecutorService).runAll()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun size(width: Int, height: Int): Any = sizeClass
        .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.newInstance(width, height)

    private fun getField(name: String): Any? = getField(activity, name)

    private fun getField(target: CarPlayHostActivity, name: String): Any? =
        CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(name: String, value: Any?) = setField(activity, name, value)

    private fun setField(target: CarPlayHostActivity, name: String, value: Any?) {
        CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun invoke(name: String): Any? = invoke(activity, name)

    private fun invoke(target: CarPlayHostActivity, name: String): Any? =
        CarPlayHostActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target)
}
