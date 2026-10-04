package com.shilapi.xcertplay

import android.os.Handler
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.mockConstruction
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.util.concurrent.PausedExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostSettingsTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var controllers: MockedConstruction<CarPlayController>

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        controllers = mockConstruction(CarPlayController::class.java)
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        setField("teardownExecutor", PausedExecutorService())
        setField("airPlayIdentity", AirPlayIdentity.generate())
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1920, 990)
        setField("activeDisplaySize", size)
        CarPlayBackgroundSession::class.java.getDeclaredField("owner").apply { isAccessible = true }
            .set(CarPlayBackgroundSession, activity)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.WIFI_P2P)
        invoke("loadPersistedSettings")
        invoke("buildContentView")
    }

    @After fun tearDown() {
        (field("shuttingDown") as AtomicBoolean).set(true)
        (field("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        (field("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
        controllers.close()
    }

    @Test fun configuredFingerCountsOpenTheMountedMenuWithoutLeavingCarPlay() {
        assertEquals(3, AirPlayPersistence.loadSettingsGestureFingers(activity))
        for (fingers in 2..4) {
            AirPlayPersistence.saveSettingsGestureFingers(activity, fingers)
            invoke("loadPersistedSettings")
            gesture(fingers)
            assertTrue(field("menuOpen") as Boolean)
            assertEquals(View.VISIBLE, menu().visibility)
            assertNotNull(menu().parent)
            assertEquals(View.GONE, (field("gestureOverlay") as View).visibility)
            assertNull(shadowOf(activity).nextStartedActivity)
            invoke("cancelSettingsEdits")
        }
    }

    @Test fun wrongFingerCountsAndNonDownwardSwipesDoNotOpenTheMenu() {
        for (configured in 2..4) {
            AirPlayPersistence.saveSettingsGestureFingers(activity, configured)
            invoke("loadPersistedSettings")
            for (actual in 2..4) {
                if (actual == configured) continue
                gesture(actual)
                assertFalse(field("menuOpen") as Boolean)
            }
            gesture(configured, x = 1000f, y = 130f)
            assertFalse(field("menuOpen") as Boolean)
            gesture(configured, y = -500f)
            assertFalse(field("menuOpen") as Boolean)
        }
    }

    @Test fun liftingAFingerCancelsTrackingUntilTheNextGesture() {
        touch(MotionEvent.ACTION_DOWN, 1, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 3, 100f)
        touch(MotionEvent.ACTION_POINTER_UP, 3, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 3, 100f)
        touch(MotionEvent.ACTION_MOVE, 3, 700f)
        assertFalse(field("menuOpen") as Boolean)
        touch(MotionEvent.ACTION_UP, 1, 700f)
        gesture(3)
        assertTrue(field("menuOpen") as Boolean)
    }

    @Test fun openingAndCancellingKeepsTheCurrentControllerAndRestoresControls() {
        val controller = attachController()
        invoke("openSettingsMenu")
        val original = AirPlayPersistence.loadDisplayScaleTenths(activity)
        resolutionSlider().progress = 0
        gestureButton().performClick()
        assertEquals(3, AirPlayPersistence.loadSettingsGestureFingers(activity))
        invoke("cancelSettingsEdits")
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
        assertEquals(original, field("displayScaleTenths"))
        assertEquals(3, field("gestureFingerCount"))
        invoke("openSettingsMenu")
        assertEquals(original - CarPlayDisplayScale.MIN_TENTHS, resolutionSlider().progress)
        assertEquals(activity.getString(R.string.settings_gesture_fingers, 3), gestureButton().text)
    }

    @Test fun savingPersistsSettingsAndRestartsOnce() {
        attachController()
        invoke("openSettingsMenu")
        resolutionSlider().progress = 0
        gestureButton().performClick()
        invoke("saveSettingsAndReconnect")
        assertFalse(field("menuOpen") as Boolean)
        assertEquals(CarPlayDisplayScale.MIN_TENTHS, AirPlayPersistence.loadDisplayScaleTenths(activity))
        assertEquals(4, AirPlayPersistence.loadSettingsGestureFingers(activity))
        assertEquals(1, field("restartGeneration"))
        assertTrue(field("handshakeResetInProgress") as Boolean)
    }

    @Test fun failedStartupWhileMenuIsOpenRecoversOnCancel() {
        attachController()
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("Authentication failed"))
        assertEquals(0, field("restartGeneration"))
        invoke("cancelSettingsEdits")
        assertEquals(1, field("restartGeneration"))
    }

    @Test fun transportLossWhileMenuIsOpenRecoversOnCancel() {
        attachController()
        invoke("openSettingsMenu")
        val listener = activity.javaClass.getDeclaredMethod("createSessionListener", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, 0) as AirPlaySessionListener
        listener.onTransportError("transport lost")
        invoke("cancelSettingsEdits")
        assertEquals(1, field("restartGeneration"))
    }

    @Test fun wifiResetFailureRequiresManualRecoveryAfterCancel() {
        attachController()
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("Wi-Fi needs a reset", wifiResetRequired = true))
        invoke("cancelSettingsEdits")
        assertEquals(View.VISIBLE, (field("wifiRecoveryButton") as View).visibility)
        assertEquals(0, field("restartGeneration"))
        assertFalse(field("reconnectScheduled") as Boolean)
    }

    @Test fun savingWirelessAfterWifiResetFailureStillRequiresManualRecovery() {
        attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("Wi-Fi needs a reset", wifiResetRequired = true))
        invoke("saveSettingsAndReconnect")
        assertEquals(View.VISIBLE, (field("wifiRecoveryButton") as View).visibility)
        assertEquals(0, field("restartGeneration"))
    }

    @Test fun savingWiredModeAfterWifiResetFailureRestartsWithTheNewSettings() {
        attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("Wi-Fi needs a reset", wifiResetRequired = true))
        setField("wirelessEnabled", false)
        invoke("saveSettingsAndReconnect")
        assertFalse(AirPlayPersistence.loadWirelessEnabled(activity))
        assertEquals(1, field("restartGeneration"))
    }

    @Test fun staleFailureDoesNotRestartWhenTheMenuCloses() {
        val controller = attachController()
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("old failure"), generation = -1)
        invoke("cancelSettingsEdits")
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
    }

    private fun gesture(fingers: Int, x: Float = 400f, y: Float = 700f) {
        touch(MotionEvent.ACTION_DOWN, 1, 100f)
        for (count in 2..fingers) touch(MotionEvent.ACTION_POINTER_DOWN, count, 100f)
        touch(MotionEvent.ACTION_MOVE, fingers, y, x)
        touch(MotionEvent.ACTION_UP, 1, y, x)
    }

    private fun touch(action: Int, count: Int, y: Float, x: Float = 400f) {
        val pointers = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val positions = Array(count) { i -> MotionEvent.PointerCoords().apply { this.x = x + i * 50f; this.y = y; pressure = 1f; size = 1f } }
        val indexedAction = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
            action or ((count - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val event = MotionEvent.obtain(0, 10, indexedAction, count, pointers, positions, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try {
            activity.javaClass.getDeclaredMethod("onHostTouch", View::class.java, MotionEvent::class.java)
                .apply { isAccessible = true }.invoke(activity, field("gestureOverlay"), event)
        } finally { event.recycle() }
    }

    private fun attachController(): CarPlayController = CarPlayController(
        activity, CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
            identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)),
        AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(1920, 990)),
        AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {},
    ).also { setField("controller", it) }

    @Suppress("UNCHECKED_CAST")
    private fun report(status: CarPlayStatus, generation: Int = 0) {
        val reporter = activity.javaClass.getDeclaredMethod("createStatusReporter", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, generation) as (CarPlayStatus) -> Unit
        reporter(status)
    }

    private fun menu() = field("settingsMenu") as View
    private fun resolutionSlider() = views(menu()).filterIsInstance<SeekBar>()
        .first { it.max == CarPlayDisplayScale.MAX_TENTHS - CarPlayDisplayScale.MIN_TENTHS }
    private fun gestureButton() = views(menu()).filterIsInstance<Button>()
        .first { it.text == activity.getString(R.string.settings_gesture_fingers, field("gestureFingerCount")) }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
    private fun invoke(name: String): Any? = activity.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)
    private fun field(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun setField(name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
