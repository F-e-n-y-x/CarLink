package com.carlink.app.engine

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Looper
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.PhoneKind
import com.carlink.app.core.Transport
import com.carlink.app.detect.KnownPhones
import com.carlink.app.settings.CarLinkPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Regression tests for [EngineCoordinator]'s rules, with fake engines. Each of these broke on the
 * driver's head unit once: CarPlay restarting on every USB re-enumeration, Bluetooth interrupting
 * a session, both engines engaged, DiAuto's key receivers stealing CarPlay's wheel keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class EngineCoordinatorTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    /** Every start/stop of both engines, in order, e.g. "CARPLAY start USB". */
    private val log = mutableListOf<String>()
    private val engines = mutableMapOf<Engine, FakeEngine>()
    private val settingsApplied = mutableListOf<Engine>()
    private var usbPhonePresent = false
    private var carPlayAtCreation = LinkState.Idle

    private inner class FakeEngine(val engine: Engine, val report: (LinkState) -> Unit) : EngineHandle {
        var refreshed = 0
        var lastUsbAttach: Intent? = null

        override fun start(transport: Transport, address: String?, phoneName: String?, usbAttach: Intent?) {
            log += "$engine start $transport"
            lastUsbAttach = usbAttach
            // The real engines report WAITING when they start.
            report(LinkState(LinkStage.WAITING, engine, transport, phoneName, 1, 6))
        }

        override fun stop() {
            log += "$engine stop"
            // The real engines report IDLE when stopped.
            report(LinkState.Idle)
        }

        override fun current(): LinkState = if (engine == Engine.CARPLAY) carPlayAtCreation else LinkState.Idle

        override fun refresh() {
            refreshed++
        }

        fun reports(stage: LinkStage, transport: Transport? = Transport.USB) =
            report(LinkState(stage, engine, transport, "Phone", 3, 6))
    }

    private val carPlay get() = engines.getValue(Engine.CARPLAY)
    private val androidAuto get() = engines.getValue(Engine.ANDROID_AUTO)
    private val starts get() = log.filter { "start" in it }

    @Before fun resetPreferences() {
        CarLinkPreferences(app).apply {
            autoConnectUsb = true
            autoConnectBluetooth = true
            autoConnectPaused = false
        }
    }

    private fun coordinator() = EngineCoordinator(
        app,
        engineFactory = { _, engine, onState -> FakeEngine(engine, onState).also { engines[engine] = it } },
        applySessionSettings = { _, engine, _ -> settingsApplied += engine },
        worker = { it.run() },
        usbPhonePresent = { usbPhonePresent },
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun usbAttach() = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)

    private fun receiverState(name: String) =
        app.packageManager.getComponentEnabledSetting(ComponentName(app, name))

    private fun assertAndroidAutoReceivers(enabled: Boolean) {
        val expected = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        EngineCoordinator.ANDROID_AUTO_KEY_RECEIVERS.forEach { assertEquals(it, expected, receiverState(it)) }
    }

    // --- 1. USB re-enumeration must not restart a USB session --------------------------------

    @Test fun usbPhoneStartsItsEngineOverUsb() {
        val c = coordinator()
        val attach = usbAttach()
        c.onUsbPhone(PhoneKind.IPHONE, "iPhone", attach)
        assertEquals(LinkStage.WAITING, c.state.value.stage)
        idle()
        assertEquals(listOf("CARPLAY start USB"), log)
        assertEquals(listOf(Engine.CARPLAY), settingsApplied)
        assertTrue("the attach reaches the engine", carPlay.lastUsbAttach === attach)
        assertEquals(Transport.USB, c.state.value.transport)
    }

    @Test fun repeatedIphoneAttachesNeverRestartCarPlay() {
        val c = coordinator()
        c.onUsbPhone(PhoneKind.IPHONE, "iPhone", usbAttach())
        idle()
        // WAITING (as started), then CONNECTING, then CONNECTED: the iPhone re-enumerates in each.
        repeat(3) { c.onUsbPhone(PhoneKind.IPHONE, "iPhone", usbAttach()); idle() }
        carPlay.reports(LinkStage.CONNECTING)
        repeat(3) { c.onUsbPhone(PhoneKind.IPHONE, "iPhone", usbAttach()); idle() }
        carPlay.reports(LinkStage.CONNECTED)
        repeat(3) { c.onUsbPhone(PhoneKind.IPHONE, "iPhone", usbAttach()); idle() }
        assertEquals(listOf("CARPLAY start USB"), log)
        assertEquals(LinkStage.CONNECTED, c.state.value.stage)
    }

    @Test fun repeatedAndroidAttachesNeverRestartAndroidAuto() {
        val c = coordinator()
        c.onUsbPhone(PhoneKind.ANDROID, "Pixel", usbAttach())
        idle()
        // The phone switching into accessory mode is a new attach.
        c.onUsbPhone(PhoneKind.ANDROID, "Pixel", usbAttach()); idle()
        androidAuto.reports(LinkStage.CONNECTING)
        c.onUsbPhone(PhoneKind.ANDROID, "Pixel", usbAttach()); idle()
        androidAuto.reports(LinkStage.CONNECTED)
        c.onUsbPhone(PhoneKind.ANDROID, "Pixel", usbAttach()); idle()
        assertEquals(listOf("ANDROID_AUTO start USB"), log)
    }

    @Test fun usbAttachAfterAFailureStartsAgain() {
        val c = coordinator()
        c.onUsbPhone(PhoneKind.IPHONE, "iPhone", usbAttach()); idle()
        carPlay.reports(LinkStage.FAILED)
        c.onUsbPhone(PhoneKind.IPHONE, "iPhone", usbAttach()); idle()
        assertEquals(listOf("CARPLAY start USB", "CARPLAY start USB"), log)
    }

    @Test fun otherEnginesPhoneSwitchesEnginesStoppingTheOtherFirst() {
        val c = coordinator()
        c.onUsbPhone(PhoneKind.IPHONE, "iPhone", usbAttach()); idle()
        carPlay.reports(LinkStage.CONNECTED)
        c.onUsbPhone(PhoneKind.ANDROID, "Pixel", usbAttach()); idle()
        assertEquals(listOf("CARPLAY start USB", "CARPLAY stop", "ANDROID_AUTO start USB"), log)
        assertEquals(Engine.ANDROID_AUTO, c.state.value.engine)
    }

    @Test fun usbReplacesAWirelessSessionOfTheSameEngine() {
        val c = coordinator()
        c.onBluetoothPhone("AA:BB:CC:DD:EE:FF", PhoneKind.IPHONE, "iPhone"); idle()
        carPlay.reports(LinkStage.CONNECTED, Transport.WIRELESS)
        c.onUsbPhone(PhoneKind.IPHONE, "iPhone", usbAttach()); idle()
        assertEquals(listOf("CARPLAY start WIRELESS", "CARPLAY start USB"), log)
    }

    @Test fun usbAutoStartOffOrUnknownDeviceStartsNothing() {
        val c = coordinator()
        c.onUsbPhone(PhoneKind.UNKNOWN, null, usbAttach()); idle()
        CarLinkPreferences(app).autoConnectUsb = false
        c.onUsbPhone(PhoneKind.IPHONE, "iPhone", usbAttach()); idle()
        assertTrue(log.isEmpty())
        assertEquals(LinkState.Idle, c.state.value)
    }

    @Test fun attachesWithinTenSecondsOfDisconnectAreIgnored() {
        val c = coordinator()
        c.onUsbPhone(PhoneKind.ANDROID, "Pixel", usbAttach()); idle()
        androidAuto.reports(LinkStage.CONNECTED)
        c.disconnect()
        // The phone leaves accessory mode: that re-attach is not the driver plugging in.
        c.onUsbPhone(PhoneKind.ANDROID, "Pixel", usbAttach()); idle()
        advance(9_000)
        c.onUsbPhone(PhoneKind.ANDROID, "Pixel", usbAttach()); idle()
        assertEquals(listOf("ANDROID_AUTO start USB", "ANDROID_AUTO stop"), log)
        advance(1_500)
        c.onUsbPhone(PhoneKind.ANDROID, "Pixel", usbAttach()); idle()
        assertEquals("a real plug-in after the window starts again",
            listOf("ANDROID_AUTO start USB", "ANDROID_AUTO stop", "ANDROID_AUTO start USB"), log)
    }

    @Test fun unpluggedPhoneEndsTheWiredSessionAfterTheGrace() {
        val c = coordinator()
        c.onUsbPhone(PhoneKind.IPHONE, "iPhone", usbAttach()); idle()
        carPlay.reports(LinkStage.CONNECTED)
        usbPhonePresent = false
        c.onUsbDetached()
        advance(5_000)
        assertEquals("still inside the re-enumeration grace", LinkStage.CONNECTED, c.state.value.stage)
        advance(1_500)
        assertEquals(LinkState.Idle, c.state.value)
        assertEquals(listOf("CARPLAY start USB", "CARPLAY stop"), log)
    }

    @Test fun detachDuringReenumerationKeepsTheSession() {
        val c = coordinator()
        c.onUsbPhone(PhoneKind.IPHONE, "iPhone", usbAttach()); idle()
        // The iPhone drops off the bus while switching configuration and comes back.
        c.onUsbDetached()
        advance(1_000)
        c.onUsbPhone(PhoneKind.IPHONE, "iPhone", usbAttach())
        advance(10_000)
        assertEquals(LinkStage.WAITING, c.state.value.stage)
        // Detached but the phone is still on the bus when the grace ends (another device left).
        usbPhonePresent = true
        c.onUsbDetached()
        advance(10_000)
        assertEquals(LinkStage.WAITING, c.state.value.stage)
        assertEquals(listOf("CARPLAY start USB"), log)
    }

    @Test fun detachDoesNotEndAWirelessSession() {
        val c = coordinator()
        c.onBluetoothPhone("AA:BB:CC:DD:EE:FF", PhoneKind.ANDROID, "Pixel"); idle()
        androidAuto.reports(LinkStage.CONNECTED, Transport.WIRELESS)
        c.onUsbDetached()
        advance(10_000)
        assertEquals(LinkStage.CONNECTED, c.state.value.stage)
        assertFalse(log.contains("ANDROID_AUTO stop"))
    }

    // --- 2. Automatic starts ------------------------------------------------------------------

    @Test fun bluetoothStartsWirelessWhenIdle() {
        val c = coordinator()
        c.onBluetoothPhone("AA:BB:CC:DD:EE:FF", PhoneKind.ANDROID, "Pixel"); idle()
        assertEquals(listOf("ANDROID_AUTO start WIRELESS"), log)
    }

    @Test fun bluetoothNeverInterruptsARunningSession() {
        val c = coordinator()
        c.onUsbPhone(PhoneKind.IPHONE, "iPhone", usbAttach()); idle()
        c.onBluetoothPhone("11:22:33:44:55:66", PhoneKind.ANDROID, "Pixel"); idle()
        c.onBluetoothPhone("AA:BB:CC:DD:EE:FF", PhoneKind.IPHONE, "iPhone"); idle()
        carPlay.reports(LinkStage.CONNECTING)
        c.onBluetoothPhone("11:22:33:44:55:66", PhoneKind.ANDROID, "Pixel"); idle()
        carPlay.reports(LinkStage.CONNECTED)
        c.onBluetoothPhone("11:22:33:44:55:66", PhoneKind.ANDROID, "Pixel"); idle()
        assertEquals(listOf("CARPLAY start USB"), log)
        assertEquals(Engine.CARPLAY, c.state.value.engine)
    }

    @Test fun bluetoothRespectsTheSettingAndUnknownPhones() {
        val c = coordinator()
        c.onBluetoothPhone("AA:BB:CC:DD:EE:FF", PhoneKind.UNKNOWN, "Galaxy"); idle()
        CarLinkPreferences(app).autoConnectBluetooth = false
        c.onBluetoothPhone("AA:BB:CC:DD:EE:FF", PhoneKind.ANDROID, "Pixel"); idle()
        assertTrue(log.isEmpty())
    }

    @Test fun disconnectPausesAutomaticStartsUntilTheDriverConnects() {
        val c = coordinator()
        val prefs = CarLinkPreferences(app)
        c.onBluetoothPhone("AA:BB:CC:DD:EE:FF", PhoneKind.IPHONE, "iPhone"); idle()
        c.disconnect()
        assertTrue(prefs.autoConnectPaused)
        assertEquals(LinkState.Idle, c.state.value)
        c.onBluetoothPhone("AA:BB:CC:DD:EE:FF", PhoneKind.IPHONE, "iPhone"); idle()
        assertEquals(listOf("CARPLAY start WIRELESS", "CARPLAY stop"), log)

        c.connectPhone("AA:BB:CC:DD:EE:FF", PhoneKind.IPHONE, "iPhone"); idle()
        assertFalse(prefs.autoConnectPaused)
        assertEquals(PhoneKind.IPHONE, KnownPhones(app).kindOf("AA:BB:CC:DD:EE:FF"))
        assertEquals(listOf("CARPLAY start WIRELESS", "CARPLAY stop", "CARPLAY start WIRELESS"), log)
    }

    @Test fun pauseSurvivesACoordinatorRestart() {
        coordinator().disconnect()
        val c = coordinator()
        c.onBluetoothPhone("AA:BB:CC:DD:EE:FF", PhoneKind.ANDROID, "Pixel"); idle()
        assertTrue(starts.isEmpty())
    }

    @Test fun bluetoothMayRetryAfterAFailure() {
        val c = coordinator()
        c.onBluetoothPhone("AA:BB:CC:DD:EE:FF", PhoneKind.ANDROID, "Pixel"); idle()
        androidAuto.reports(LinkStage.FAILED, Transport.WIRELESS)
        c.onBluetoothPhone("AA:BB:CC:DD:EE:FF", PhoneKind.ANDROID, "Pixel"); idle()
        assertEquals(2, starts.size)
    }

    @Test fun successfulSessionRemembersThePhonesKind() {
        val c = coordinator()
        c.onBluetoothPhone("AA:BB:CC:DD:EE:01", PhoneKind.ANDROID, "Pixel"); idle()
        assertEquals(null, KnownPhones(app).kindOf("AA:BB:CC:DD:EE:01"))
        androidAuto.reports(LinkStage.CONNECTED, Transport.WIRELESS)
        assertEquals(PhoneKind.ANDROID, KnownPhones(app).kindOf("AA:BB:CC:DD:EE:01"))
    }

    // --- 3. One engine at a time; DiAuto key receivers only during Android Auto ---------------

    @Test fun keyReceiversAreDisabledAtStartAndEnabledOnlyForAndroidAuto() {
        val c = coordinator()
        assertAndroidAutoReceivers(false)
        c.connectPhone("11:22:33:44:55:66", PhoneKind.ANDROID, "Pixel"); idle()
        assertAndroidAutoReceivers(true)
        c.connectPhone("AA:BB:CC:DD:EE:FF", PhoneKind.IPHONE, "iPhone"); idle()
        assertAndroidAutoReceivers(false)
        c.connectPhone("11:22:33:44:55:66", PhoneKind.ANDROID, "Pixel"); idle()
        assertAndroidAutoReceivers(true)
        c.disconnect()
        assertAndroidAutoReceivers(false)
    }

    @Test fun keyReceiversAreDisabledWhenAndroidAutoEndsOnItsOwn() {
        val c = coordinator()
        c.connectPhone(null, PhoneKind.ANDROID, "Pixel"); idle()
        assertAndroidAutoReceivers(true)
        androidAuto.reports(LinkStage.IDLE, null)
        assertEquals(LinkState.Idle, c.state.value)
        assertAndroidAutoReceivers(false)
    }

    @Test fun switchingEnginesStopsTheOtherBeforeStarting() {
        val c = coordinator()
        c.connectPhone(null, PhoneKind.ANDROID, "Pixel"); idle()
        c.connectPhone(null, PhoneKind.IPHONE, "iPhone"); idle()
        c.connectPhone(null, PhoneKind.ANDROID, "Pixel"); idle()
        assertEquals(listOf(
            "ANDROID_AUTO start WIRELESS",
            "ANDROID_AUTO stop", "CARPLAY start WIRELESS",
            "CARPLAY stop", "ANDROID_AUTO start WIRELESS",
        ), log)
    }

    @Test fun supersededAttemptNeverStartsItsEngine() {
        val c = coordinator()
        // Two requests before the main thread runs: only the last one may start.
        c.connectPhone(null, PhoneKind.ANDROID, "Pixel")
        c.connectPhone(null, PhoneKind.IPHONE, "iPhone")
        idle()
        assertEquals(listOf("CARPLAY start WIRELESS"), starts)
        assertEquals(Engine.CARPLAY, c.state.value.engine)
    }

    @Test fun disconnectBeforeTheEngineStartsCancelsTheStart() {
        val c = coordinator()
        c.connectPhone(null, PhoneKind.IPHONE, "iPhone")
        c.disconnect()
        idle()
        assertTrue(starts.isEmpty())
        assertEquals(LinkState.Idle, c.state.value)
    }

    @Test fun reportsFromTheEngineNotEngagedAreIgnored() {
        val c = coordinator()
        c.connectPhone(null, PhoneKind.IPHONE, "iPhone"); idle()
        androidAuto.reports(LinkStage.CONNECTED, Transport.WIRELESS)
        androidAuto.reports(LinkStage.IDLE, null)
        assertEquals(Engine.CARPLAY, c.state.value.engine)
        assertEquals(LinkStage.WAITING, c.state.value.stage)
        assertAndroidAutoReceivers(false)
    }

    @Test fun aSessionCarLinkDidNotStartIsAdoptedAndTheOtherEngineStopped() {
        val c = coordinator()
        // Android Auto still running in ":aa" after the main process restarted.
        androidAuto.reports(LinkStage.CONNECTED, Transport.WIRELESS)
        assertEquals(Engine.ANDROID_AUTO, c.state.value.engine)
        assertTrue(log.contains("CARPLAY stop"))
        assertAndroidAutoReceivers(true)
    }

    @Test fun runningCarPlayIsAdoptedAtCreationWithoutAskingAndroidAuto() {
        carPlayAtCreation = LinkState(LinkStage.CONNECTED, Engine.CARPLAY, Transport.WIRELESS, "iPhone", 6, 6)
        val c = coordinator()
        assertEquals(carPlayAtCreation, c.state.value)
        assertEquals(0, androidAuto.refreshed)
        // And it is engaged: a Bluetooth phone does not interrupt it.
        c.onBluetoothPhone("11:22:33:44:55:66", PhoneKind.ANDROID, "Pixel"); idle()
        assertTrue(starts.isEmpty())
    }

    @Test fun idleCarPlayAtCreationAsksAndroidAutoToResync() {
        coordinator()
        assertEquals(1, androidAuto.refreshed)
    }

    @Test fun stopAllWithNothingEngagedStopsBoth() {
        val c = coordinator()
        c.stopAll()
        assertEquals(setOf("CARPLAY stop", "ANDROID_AUTO stop"), log.toSet())
        assertEquals(LinkState.Idle, c.state.value)
    }
}
