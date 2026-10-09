package com.carlink.app.connection

import android.app.Application
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Looper
import com.carlink.app.core.Engine
import com.carlink.app.core.PhoneKind
import com.carlink.app.core.Transport
import com.carlink.app.settings.WirelessPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * [SessionManager] with fake engines. The USB/Bluetooth rules each broke on the driver's head
 * unit once (v1 EngineCoordinatorTest); the path and stop-completion rules come from the engine
 * dissections (design/REBUILD_V2.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SessionManagerTest {
    private val log = mutableListOf<String>()
    private val applied = mutableListOf<SessionPlan>()
    private val remembered = mutableListOf<Pair<String, WirelessPath>>()
    private var problems: (SessionRequest, WirelessPath?) -> List<Problem> = { _, _ -> emptyList() }
    private var paths = listOf(WirelessPath.WIFI_DIRECT, WirelessPath.CAR_HOTSPOT)
    private var usbPresent = false
    private val engaged = mutableListOf<Engine?>()

    private inner class FakeAdapter(override val engine: Engine) : EngineAdapter {
        var listener: ((AdapterEvent) -> Unit)? = null
        var plan: SessionPlan? = null
        var session = false
        /** When false, stop completions are held until [completeStop]. */
        var stopsAtOnce = true
        private val pendingStops = mutableListOf<() -> Unit>()

        override fun start(plan: SessionPlan, listener: (AdapterEvent) -> Unit) {
            check(!session) { "started while a session still runs" }
            log += "$engine start ${plan.transport} ${plan.path}"
            this.plan = plan
            this.listener = listener
            session = true
        }

        override fun stop(onStopped: () -> Unit) {
            log += "$engine stop"
            if (stopsAtOnce) { session = false; onStopped() } else pendingStops += { session = false; onStopped() }
        }

        override fun hasSession() = session

        fun completeStop() { pendingStops.toList().forEach { it() }; pendingStops.clear() }

        fun emit(event: AdapterEvent) = listener!!.invoke(event)
    }

    private val carPlay = FakeAdapter(Engine.CARPLAY)
    private val androidAuto = FakeAdapter(Engine.ANDROID_AUTO)

    private val env = object : SessionManager.Environment {
        override var autoConnectUsb = true
        override var autoConnectBluetooth = true
        override var autoConnectPaused = false
        override fun preflight(request: SessionRequest, path: WirelessPath?) = problems(request, path)
        override fun wirelessPaths(request: SessionRequest) = paths
        override fun rememberWorkingPath(engine: Engine, phoneAddress: String, path: WirelessPath) { remembered += phoneAddress to path }
        override fun rememberPhone(phoneAddress: String, kind: PhoneKind) {}
        override fun usbPhonePresent(engine: Engine) = usbPresent
        override fun onEngaged(engine: Engine?) { engaged += engine }
        override fun log(message: String) {}
    }

    private fun manager() = SessionManager(
        mapOf(Engine.CARPLAY to carPlay, Engine.ANDROID_AUTO to androidAuto), env,
        applySettings = { applied += it }, worker = { it.run() },
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    private val starts get() = log.filter { "start" in it }

    // --- Basic flow ---------------------------------------------------------------------------

    @Test fun connectWritesSettingsThenStartsOnTheFirstPath() {
        val m = manager()
        m.connect("AA:BB", PhoneKind.ANDROID, "Pixel")
        idle()
        assertEquals(listOf("ANDROID_AUTO start WIRELESS WIFI_DIRECT"), starts)
        assertEquals(WirelessPath.WIFI_DIRECT, applied.single().path)
        assertEquals(Engine.ANDROID_AUTO, engaged.last())
        androidAuto.emit(AdapterEvent.Connected); idle()
        assertEquals(SessionState.Phase.Connected, (m.state.value as SessionState.Active).phase)
        assertEquals(listOf("AA:BB" to WirelessPath.WIFI_DIRECT), remembered)
    }

    @Test fun blockedRequestsNeverOpenAnEngine() {
        problems = { _, _ -> listOf(Problem.BLUETOOTH_OFF) }
        val m = manager()
        m.connect("AA:BB", PhoneKind.IPHONE, "iPhone")
        idle()
        assertTrue(starts.isEmpty())
        assertEquals(listOf(Problem.BLUETOOTH_OFF), (m.state.value as SessionState.Blocked).problems)
        problems = { _, _ -> emptyList() }
        m.retry(); idle()
        assertEquals(1, starts.size)
    }

    // --- Paths --------------------------------------------------------------------------------

    @Test fun onlyPathRelatedFailuresMoveToTheNextPathAndTheDriverSeesNoFailureInBetween() {
        val m = manager()
        m.connect("AA:BB", PhoneKind.ANDROID, "Pixel"); idle()
        androidAuto.emit(AdapterEvent.Failed(FailureReason.PHONE_REFUSED_WIFI)); idle()
        assertEquals(listOf("ANDROID_AUTO start WIRELESS WIFI_DIRECT", "ANDROID_AUTO start WIRELESS CAR_HOTSPOT"), starts)
        assertTrue(m.state.value is SessionState.Active)
        assertEquals(2, (m.state.value as SessionState.Active).attempt)
        // The last path failing is shown.
        androidAuto.emit(AdapterEvent.Failed(FailureReason.PHONE_REFUSED_WIFI)); idle()
        assertTrue(m.state.value is SessionState.Failed)
    }

    @Test fun bluetoothFailuresDoNotAbandonThePath() {
        val m = manager()
        m.connect("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        carPlay.emit(AdapterEvent.Failed(FailureReason.BLUETOOTH)); idle()
        assertEquals(1, starts.size)
        assertEquals(FailureReason.BLUETOOTH, (m.state.value as SessionState.Failed).reason)
    }

    @Test fun theNextSessionStartsOnlyAfterTheEngineFinishedStopping() {
        carPlay.stopsAtOnce = false
        val m = manager()
        m.connect("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        carPlay.emit(AdapterEvent.Failed(FailureReason.PHONE_NEVER_REACHED_CAR)); idle()
        assertEquals("the next path waits for the old teardown", 1, starts.size)
        carPlay.completeStop(); idle()
        assertEquals(2, starts.size)
    }

    @Test fun switchingEnginesStopsTheOtherFirst() {
        androidAuto.stopsAtOnce = false
        val m = manager()
        m.connect("AA:BB", PhoneKind.ANDROID, "Pixel"); idle()
        m.connect("CC:DD", PhoneKind.IPHONE, "iPhone"); idle()
        assertEquals(1, starts.size)
        androidAuto.completeStop(); idle()
        assertEquals(listOf("ANDROID_AUTO start WIRELESS WIFI_DIRECT", "CARPLAY start WIRELESS WIFI_DIRECT"), starts)
    }

    @Test fun eventsFromAStoppedSessionAreIgnored() {
        val m = manager()
        m.connect("AA:BB", PhoneKind.ANDROID, "Pixel"); idle()
        val old = androidAuto.listener!!
        m.stop(); idle()
        old(AdapterEvent.Connected); idle()
        assertEquals(SessionState.Idle, m.state.value)
    }

    // --- Triggers (ported v1 regressions) -----------------------------------------------------

    @Test fun usbReattachDuringAUsbSessionIsLeftToTheEngine() {
        val m = manager()
        m.onUsbPhone(PhoneKind.IPHONE, "iPhone", Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)); idle()
        repeat(5) { m.onUsbPhone(PhoneKind.IPHONE, "iPhone", Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)) }
        idle()
        assertEquals(listOf("CARPLAY start USB null"), starts)
    }

    @Test fun usbAttachRightAfterTheDriverStoppedDoesNotRestart() {
        val m = manager()
        m.onUsbPhone(PhoneKind.ANDROID, "Pixel", null); idle()
        m.disconnect(); idle()
        m.onUsbPhone(PhoneKind.ANDROID, "Pixel", null); idle()
        assertEquals(1, starts.size)
        advance(SessionManager.REENUMERATION_WINDOW_MILLIS + 1)
        m.onUsbPhone(PhoneKind.ANDROID, "Pixel", null); idle()
        assertEquals(2, starts.size)
    }

    @Test fun bluetoothNeverInterruptsARunningSessionAndRespectsThePause() {
        val m = manager()
        m.connect("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        m.onBluetoothPhone("CC:DD", PhoneKind.ANDROID, "Pixel"); idle()
        assertEquals(1, starts.size)
        m.disconnect(); idle()
        m.onBluetoothPhone("CC:DD", PhoneKind.ANDROID, "Pixel"); idle()
        assertEquals(1, starts.size)
    }

    @Test fun usbSessionEndsWhenThePhoneStaysUnplugged() {
        val m = manager()
        m.onUsbPhone(PhoneKind.ANDROID, "Pixel", null); idle()
        m.onUsbDetached()
        advance(SessionManager.USB_GONE_GRACE_MILLIS + 1)
        assertEquals(SessionState.Idle, m.state.value)
        assertTrue(log.last() == "ANDROID_AUTO stop")
    }

    @Test fun usbSessionSurvivesAReenumerationDetach() {
        usbPresent = true
        val m = manager()
        m.onUsbPhone(PhoneKind.ANDROID, "Pixel", null); idle()
        m.onUsbDetached()
        advance(SessionManager.USB_GONE_GRACE_MILLIS + 1)
        assertTrue(m.state.value is SessionState.Active)
    }

    @Test fun anOrphanedSessionIsStoppedAtStartup() {
        androidAuto.session = true
        manager()
        idle()
        assertEquals(listOf("ANDROID_AUTO stop"), log)
    }

    @Test fun theDriverExitingPausesAutomaticStarts() {
        val m = manager()
        m.connect("AA:BB", PhoneKind.ANDROID, "Pixel"); idle()
        androidAuto.emit(AdapterEvent.Ended(byUser = true)); idle()
        assertEquals(SessionState.Idle, m.state.value)
        assertTrue(env.autoConnectPaused)
    }

    @Test fun aSecondUsbPhoneAsksBeforeTakingOver() {
        val m = manager()
        m.onUsbPhone(PhoneKind.IPHONE, "iPhone", null); idle()
        m.onUsbPhone(PhoneKind.ANDROID, "Pixel", null); idle()
        assertEquals("nothing replaced yet", 1, starts.size)
        assertEquals("Pixel", m.pendingSwitch.value?.phoneName)
        m.declineSwitch(); idle()
        assertEquals(null, m.pendingSwitch.value)
        assertEquals(1, starts.size)
        m.onUsbPhone(PhoneKind.ANDROID, "Pixel", null); idle()
        m.acceptSwitch(); idle()
        assertEquals(listOf("CARPLAY start USB null", "ANDROID_AUTO start USB null"), starts)
    }
}
