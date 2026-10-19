package com.carlink.core.connection

import android.app.Application
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Looper
import com.carlink.core.core.Engine
import com.carlink.core.core.LinkStage
import com.carlink.core.core.PhoneKind
import com.carlink.core.core.Transport
import com.carlink.core.settings.WirelessPath
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

        val recovered = mutableListOf<RecoveryAction>()
        var recoverWorks = true
        override fun recover(action: RecoveryAction): Boolean { recovered += action; return recoverWorks }

        fun completeStop() { pendingStops.toList().forEach { it() }; pendingStops.clear() }

        fun emit(event: AdapterEvent) = listener!!.invoke(event)
    }

    private val carPlay = FakeAdapter(Engine.CARPLAY)
    private val androidAuto = FakeAdapter(Engine.ANDROID_AUTO)

    private val env = object : SessionManager.Environment {
        override var autoConnectUsb = true
        override var autoConnectBluetooth = true
        override var autoConnectPaused = false
        override var wirelessUnavailable = false
        override fun preflight(request: SessionRequest, path: WirelessPath?) = problems(request, path)
        override fun wirelessPaths(request: SessionRequest) = paths
        override fun rememberWorkingPath(engine: Engine, phoneAddress: String, path: WirelessPath) { remembered += phoneAddress to path }
        override fun rememberPhone(phoneAddress: String, kind: PhoneKind) {}
        override fun usbPhonePresent(engine: Engine) = usbPresent
        override fun onEngaged(engine: Engine?) { engaged += engine }
        override fun returnHome() { log += "home" }
        override fun log(message: String) { notes += message }
        var warmUps = 0
        var warmUpDone: (() -> Unit)? = null
        var warmUpAvailable = false
        override fun warmUp(request: SessionRequest, done: () -> Unit): Boolean {
            if (!warmUpAvailable) return false
            warmUps++
            warmUpDone = done
            return true
        }
    }
    private val notes = mutableListOf<String>()
    private var profiles = ConnectionProfiles()

    private fun manager() = SessionManager(
        mapOf(Engine.CARPLAY to carPlay, Engine.ANDROID_AUTO to androidAuto), env,
        applySettings = { applied += it }, worker = { it.run() }, profiles = profiles,
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
        // It is offered instead, once.
        assertEquals("Pixel", m.pendingSwitch.value?.phoneName)
        assertEquals(Transport.WIRELESS, m.pendingSwitch.value?.transport)
        m.declineSwitch()
        m.onBluetoothPhone("CC:DD", PhoneKind.ANDROID, "Pixel"); idle()
        assertEquals(null, m.pendingSwitch.value)
        m.disconnect(); idle()
        m.onBluetoothPhone("CC:DD", PhoneKind.ANDROID, "Pixel"); idle()
        assertEquals(1, starts.size)
    }

    // --- A stereo whose Bluetooth is an external module ------------------------------------------

    @Test fun wirelessIsBlockedForBothEnginesBeforeAnyEngineStarts() {
        env.wirelessUnavailable = true
        env.warmUpAvailable = true
        val m = manager()
        for (kind in listOf(PhoneKind.IPHONE, PhoneKind.ANDROID)) {
            m.connect("AA:BB", kind, "Phone"); idle()
            val blocked = m.state.value as SessionState.Blocked
            assertEquals(kind.engine, blocked.request.engine)
            assertEquals(listOf(Problem.EXTERNAL_BLUETOOTH), blocked.problems)
            assertEquals(com.carlink.core.core.LinkProblem.EXTERNAL_BLUETOOTH, m.state.value.toLinkState().problem)
        }
        // No engine, no settings write (no Wi-Fi Direct group), no Bluetooth warm-up.
        assertTrue(starts.isEmpty())
        assertTrue(applied.isEmpty())
        assertEquals(0, env.warmUps)
        // Try again stays blocked; it does not loop through the engines.
        m.retry(); idle()
        assertTrue(starts.isEmpty())
        assertTrue(m.state.value is SessionState.Blocked)
    }

    @Test fun usbIsNeverBlockedOnAStereoWithAnExternalModule() {
        env.wirelessUnavailable = true
        val m = manager()
        m.onUsbPhone(PhoneKind.ANDROID, "Pixel", null); idle()
        assertEquals(listOf("ANDROID_AUTO start USB null"), starts)
        m.stop(); idle()
        m.connect(null, PhoneKind.IPHONE, "iPhone", Transport.USB); idle()
        assertEquals("CARPLAY start USB null", starts.last())
        assertTrue(m.state.value is SessionState.Active)
    }

    @Test fun automaticWirelessStartsAreSkippedOnAStereoWithAnExternalModule() {
        env.wirelessUnavailable = true
        val m = manager()
        m.onHomeShown("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        m.onBluetoothPhone("CC:DD", PhoneKind.ANDROID, "Pixel"); idle()
        assertTrue(starts.isEmpty())
        // Skipped, not blocked: Home keeps its ready state instead of showing a failure on its own.
        assertEquals(SessionState.Idle, m.state.value)
        // And a USB session gets no wireless switch offer.
        m.onUsbPhone(PhoneKind.ANDROID, "Pixel", null); idle()
        m.onBluetoothPhone("EE:FF", PhoneKind.IPHONE, "iPhone"); idle()
        assertEquals(null, m.pendingSwitch.value)
    }

    @Test fun theDriversOverrideLetsWirelessStartAgain() {
        // DeviceEnvironment: blocked only for the three external classifications, and never with the override on.
        for (stereo in com.carlink.core.detect.StereoBluetooth.entries) {
            val expected = stereo == com.carlink.core.detect.StereoBluetooth.ZLINK_DAEMON ||
                stereo == com.carlink.core.detect.StereoBluetooth.FYT_MODULE ||
                stereo == com.carlink.core.detect.StereoBluetooth.EXTERNAL_UNKNOWN
            assertEquals(stereo.name, expected, DeviceEnvironment.wirelessBlocked(stereo, moduleOn = false))
            assertEquals(stereo.name, false, DeviceEnvironment.wirelessBlocked(stereo, moduleOn = true))
        }
        env.wirelessUnavailable = DeviceEnvironment.wirelessBlocked(com.carlink.core.detect.StereoBluetooth.FYT_MODULE, moduleOn = true)
        val m = manager()
        m.connect("AA:BB", PhoneKind.ANDROID, "Pixel"); idle()
        assertEquals(listOf("ANDROID_AUTO start WIRELESS WIFI_DIRECT"), starts)
        // The engine may still refuse: its own report then shows the same problem.
        androidAuto.emit(AdapterEvent.Failed(FailureReason.EXTERNAL_BLUETOOTH, "this unit's Bluetooth is an external module")); idle()
        val failed = m.state.value as SessionState.Failed
        assertEquals(FailureReason.EXTERNAL_BLUETOOTH, failed.reason)
        assertEquals("not path-related: no second path", 1, starts.size)
        assertEquals(com.carlink.core.core.LinkProblem.EXTERNAL_BLUETOOTH, failed.toLinkState().problem)
    }

    @Test fun theModuleSettingPicksTheEngineRouteOnlyWhereAModuleWasFound() {
        val zbt = com.andrerinas.openheadunit.connection.ExternalBtTransportPolicy.Route.ZBT
        val blink = com.andrerinas.openheadunit.connection.ExternalBtTransportPolicy.Route.BLINK
        for (stereo in com.carlink.core.detect.StereoBluetooth.entries) {
            assertEquals(stereo.name, null, DeviceEnvironment.moduleRoute(stereo, moduleOn = false))
            val expected = when (stereo) {
                com.carlink.core.detect.StereoBluetooth.ZLINK_DAEMON, com.carlink.core.detect.StereoBluetooth.EXTERNAL_UNKNOWN -> zbt
                com.carlink.core.detect.StereoBluetooth.FYT_MODULE -> blink
                else -> null
            }
            assertEquals(stereo.name, expected, DeviceEnvironment.moduleRoute(stereo, moduleOn = true))
            // Every stereo the setting unblocks gets a module route; none is unblocked onto Android's own radio.
            if (stereo.blocksWireless) assertEquals(stereo.name, true, DeviceEnvironment.moduleRoute(stereo, true) != null)
        }
    }

    @Test fun anUnreachableModuleFailsOnceAndPointsToTheCable() {
        env.wirelessUnavailable = DeviceEnvironment.wirelessBlocked(com.carlink.core.detect.StereoBluetooth.FYT_MODULE, moduleOn = true)
        val m = manager()
        m.connect("AA:BB", PhoneKind.ANDROID, "Pixel"); idle()
        androidAuto.emit(AdapterEvent.Failed(FailureReason.EXTERNAL_MODULE, "the stereo's Bluetooth module could not be reached over BLINK")); idle()
        val failed = m.state.value as SessionState.Failed
        assertEquals(FailureReason.EXTERNAL_MODULE, failed.reason)
        assertEquals("not path-related: no second path", 1, starts.size)
        assertEquals(com.carlink.core.core.LinkProblem.EXTERNAL_MODULE, failed.toLinkState().problem)
    }

    @Test fun usbSessionEndsWhenThePhoneStaysUnplugged() {
        val m = manager()
        m.onUsbPhone(PhoneKind.ANDROID, "Pixel", null); idle()
        m.onUsbDetached()
        advance(SessionManager.USB_GONE_GRACE_MILLIS + 1)
        assertEquals(SessionState.Idle, m.state.value)
        assertEquals(listOf("ANDROID_AUTO stop", "home"), log.takeLast(2))
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

    @Test fun aRealPlugInRightAfterStoppingAWirelessSessionStarts() {
        val m = manager()
        m.connect("AA:BB", PhoneKind.ANDROID, "Pixel"); idle()
        m.disconnect(); idle()
        m.onUsbPhone(PhoneKind.IPHONE, "iPhone", null); idle()
        assertEquals(listOf("ANDROID_AUTO start WIRELESS WIFI_DIRECT", "CARPLAY start USB null"), starts)
    }

    @Test fun unpluggingAConnectedUsbPhoneReturnsHomeQuickly() {
        val m = manager()
        m.onUsbPhone(PhoneKind.IPHONE, "iPhone", null); idle()
        carPlay.emit(AdapterEvent.Connected); idle()
        m.onUsbDetached()
        advance(SessionManager.USB_GONE_CONNECTED_GRACE_MILLIS + 1)
        assertEquals(SessionState.Idle, m.state.value)
        assertEquals("home", log.last())
    }

    @Test fun homeShownConnectsTheLastPhoneOnceAndRespectsThePause() {
        val m = manager()
        m.onHomeShown("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        assertEquals(listOf("CARPLAY start WIRELESS WIFI_DIRECT"), starts)
        m.stop(); idle()
        m.onHomeShown("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        assertEquals("cooldown", 1, starts.size)
        advance(SessionManager.HOME_AUTO_START_COOLDOWN_MILLIS + 1)
        env.autoConnectPaused = true
        m.onHomeShown("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        assertEquals("paused by the driver", 1, starts.size)
    }

    @Test fun unpluggingAUsbPhoneNeverStartsTheLastWirelessPhone() {
        val m = manager()
        m.onUsbPhone(PhoneKind.ANDROID, "S25", null); idle()
        androidAuto.emit(AdapterEvent.Connected); idle()
        m.onUsbDetached()
        advance(SessionManager.USB_GONE_CONNECTED_GRACE_MILLIS + 1)
        m.onHomeShown("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        assertEquals(listOf("ANDROID_AUTO start USB null"), starts)
        assertTrue(env.autoConnectPaused)
    }

    // --- RELIABILITY_AUDIT 2026-10-09: truthful progress and the stall watchdog -----------------

    private fun active(m: SessionManager) = m.state.value as SessionState.Active
    private fun link(m: SessionManager) = m.state.value.toLinkState()

    @Test fun aUsbPhoneThatNeverAnswersIsSaidSoAfterTenSeconds() {
        // K4.4: the hint was wireless-only, so a locked phone or a stale accessory showed a busy bar.
        val m = manager()
        m.onUsbPhone(PhoneKind.ANDROID, "S25", null); idle()
        androidAuto.emit(AdapterEvent.Progress(1, 3, ProgressStage.WAITING_FOR_PHONE)); idle()
        advance(SessionManager.NOT_ANSWERING_MILLIS - 100)
        assertEquals(ProgressStage.WAITING_FOR_PHONE, (active(m).phase as SessionState.Phase.Progress).stage)
        advance(200)
        assertEquals(ProgressStage.PHONE_NOT_ANSWERING, (active(m).phase as SessionState.Phase.Progress).stage)
        assertEquals(DETAIL_PHONE_NOT_ANSWERING, link(m).detail)
        assertEquals(Notice.Kind.PHONE_NOT_ANSWERING, active(m).notice?.kind)
    }

    @Test fun aDialLoopCannotHideThatThePhoneIsNotAnswering() {
        // K4.2 / C8: Progress and Retrying every 7.7 s re-armed the 10 s timer forever.
        val m = manager()
        m.connect("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        carPlay.emit(AdapterEvent.Progress(1, 4, ProgressStage.WAITING_FOR_PHONE)); idle()
        advance(7_700)
        carPlay.emit(AdapterEvent.Retrying(1, FailureReason.BLUETOOTH, "the iPhone did not answer over Bluetooth")); idle()
        advance(2_500)
        assertEquals("still a retry, now with the silent phone named", LinkStage.WAITING, link(m).stage)
        assertEquals(DETAIL_PHONE_NOT_ANSWERING, link(m).detail)
        carPlay.emit(AdapterEvent.Progress(1, 4, ProgressStage.WAITING_FOR_PHONE)); idle()
        assertEquals(ProgressStage.PHONE_NOT_ANSWERING, (active(m).phase as SessionState.Phase.Progress).stage)
    }

    @Test fun aRetryKeepsItsRealStepAndReason() {
        // K4.1: Retrying was always drawn as step 2, "Phone answered".
        val m = manager()
        m.connect("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        carPlay.emit(AdapterEvent.Progress(1, 4, ProgressStage.WAITING_FOR_PHONE)); idle()
        carPlay.emit(AdapterEvent.Retrying(1, FailureReason.BLUETOOTH, "the iPhone did not answer over Bluetooth")); idle()
        assertEquals(1, link(m).step)
        assertEquals("Retrying: the iPhone did not answer over Bluetooth", link(m).detail)
        carPlay.emit(AdapterEvent.Progress(3, 4, ProgressStage.JOINING_WIFI)); idle()
        carPlay.emit(AdapterEvent.Retrying(2, FailureReason.PHONE_NEVER_REACHED_CAR)); idle()
        val retry = active(m).phase as SessionState.Phase.Retrying
        assertEquals(ProgressStage.JOINING_WIFI, retry.stage)
        assertEquals(3, link(m).step)
        assertEquals("Retrying: the iPhone did not reach the car over Wi-Fi", link(m).detail)
    }

    @Test fun aPictureThatNeverComesIsRetriedThenTheNextPathThenAFailure() {
        // K1 remaining risk: nothing timed out on "Picture".
        val m = manager()
        m.connect("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        carPlay.emit(AdapterEvent.Progress(4, 4, ProgressStage.STARTING_PROJECTION)); idle()
        advance(AdaptiveDeadlines.HINT_MILLIS + 1)
        assertEquals("the driver is told before anything is torn down", Notice.Kind.SLOW, active(m).notice?.kind)
        assertEquals(1, starts.size)
        advance(10_000)
        assertEquals(listOf("CARPLAY start WIRELESS WIFI_DIRECT", "CARPLAY start WIRELESS WIFI_DIRECT"), starts)
        assertEquals(RecoveryAction.RETRY_STEP, active(m).notice?.action)
        assertTrue(active(m).phase.let { it is SessionState.Phase.Retrying || it is SessionState.Phase.Starting })
        carPlay.emit(AdapterEvent.Progress(4, 4, ProgressStage.STARTING_PROJECTION)); idle()
        advance(20_001)
        assertEquals("CARPLAY start WIRELESS CAR_HOTSPOT", starts.last())
        carPlay.emit(AdapterEvent.Progress(4, 4, ProgressStage.STARTING_PROJECTION)); idle()
        advance(20_001)
        assertEquals(4, starts.size)
        carPlay.emit(AdapterEvent.Progress(4, 4, ProgressStage.STARTING_PROJECTION)); idle()
        advance(20_001)
        val failed = m.state.value as SessionState.Failed
        assertEquals(FailureReason.LINK_TOO_WEAK, failed.reason)
        assertEquals(4, starts.size)
        // Every stall is in the phone's record.
        val health = m.health.value.single()
        assertEquals(4, health.attempts)
        assertEquals(0, health.successes)
        assertEquals(AttemptOutcome.STALL_PICTURE, health.lastProblems.first().kind)
    }

    @Test fun aUsbAndroidAutoHandshakeStallIsReswitchedThenNeedsAReplug() {
        val m = manager()
        m.onUsbPhone(PhoneKind.ANDROID, "S25", null); idle()
        androidAuto.emit(AdapterEvent.Progress(2, 3, ProgressStage.HANDSHAKE)); idle()
        advance(10_001)
        assertEquals("told first", Notice.Kind.SLOW, active(m).notice?.kind)
        advance(2_000)
        assertEquals("the phone may be showing its prompt", listOf(RecoveryAction.NUDGE), androidAuto.recovered)
        advance(12_000)
        assertEquals(listOf(RecoveryAction.NUDGE, RecoveryAction.RESWITCH_USB), androidAuto.recovered)
        assertTrue(m.state.value is SessionState.Active)
        advance(12_000)
        val failed = m.state.value as SessionState.Failed
        assertEquals(FailureReason.USB, failed.reason)
        assertTrue(failed.detail!!.startsWith("unplug the phone and plug it in again"))
    }

    @Test fun aSilentWirelessPhoneIsWokenTwiceThenReportedWithAReason() {
        val m = manager()
        m.connect("AA:BB", PhoneKind.ANDROID, "Pixel"); idle()
        androidAuto.emit(AdapterEvent.Progress(1, 4, ProgressStage.WAITING_FOR_PHONE)); idle()
        advance(10_001)
        assertEquals(listOf(RecoveryAction.NUDGE), androidAuto.recovered)
        assertEquals(DETAIL_PHONE_NOT_ANSWERING, link(m).detail)
        advance(10_000)
        assertEquals(listOf(RecoveryAction.NUDGE, RecoveryAction.NUDGE), androidAuto.recovered)
        advance(10_000)
        val failed = m.state.value as SessionState.Failed
        assertEquals(FailureReason.PHONE_DID_NOT_ANSWER, failed.reason)
        assertTrue(failed.detail!!.contains("did not answer in 30 s"))
    }

    @Test fun deadlinesFollowThisPhonesOwnTimings() {
        // A phone whose picture always starts in 8 s gets 20 s (2.5 x 8), not the 15 s floor.
        val request = SessionRequest(Engine.CARPLAY, Transport.WIRELESS, "AA:BB", "iPhone")
        repeat(3) { profiles.recordStage(request, WirelessPath.WIFI_DIRECT, ProgressStage.STARTING_PROJECTION, 8_000) }
        assertEquals(20_000, profiles.deadline(request, WirelessPath.WIFI_DIRECT, ProgressStage.STARTING_PROJECTION))
        val fast = SessionRequest(Engine.CARPLAY, Transport.WIRELESS, "CC:DD", "iPhone 2")
        repeat(3) { profiles.recordStage(fast, WirelessPath.WIFI_DIRECT, ProgressStage.STARTING_PROJECTION, 1_000) }
        val m = manager()
        m.connect("CC:DD", PhoneKind.IPHONE, "iPhone 2"); idle()
        carPlay.emit(AdapterEvent.Progress(4, 4, ProgressStage.STARTING_PROJECTION)); idle()
        advance(15_001)
        assertEquals("the fast phone is retried at the 15 s floor", 2, starts.size)
    }

    @Test fun aConnectionTeachesTheProfileItsPhaseTimesAndHealth() {
        val m = manager()
        m.connect("AA:BB", PhoneKind.ANDROID, "Pixel"); idle()
        advance(1_000)
        androidAuto.emit(AdapterEvent.Progress(2, 4, ProgressStage.HANDSHAKE)); idle()
        advance(2_000)
        androidAuto.emit(AdapterEvent.Progress(4, 4, ProgressStage.STARTING_PROJECTION)); idle()
        advance(1_500)
        androidAuto.emit(AdapterEvent.Connected); idle()
        val health = m.health.value.single()
        assertEquals(1, health.successes)
        assertEquals(1f, health.successRate)
        assertEquals("WIFI_DIRECT", health.bestPath)
        val took = health.medianTimeToPictureMillis!!
        assertTrue("about 4.5 s, was $took", took in 4_400..4_700)
        val request = SessionRequest(Engine.ANDROID_AUTO, Transport.WIRELESS, "AA:BB", "Pixel")
        assertEquals(null, profiles.usualMillis(request, WirelessPath.WIFI_DIRECT, ProgressStage.HANDSHAKE))
    }

    @Test fun aSameEnginePlugDuringAWirelessAttemptSwitchesToUsbWithoutAsking() {
        // K14: plugging in is deliberate; asking cost 2.5-3 s and a tap.
        val m = manager()
        m.connect("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        m.onUsbPhone(PhoneKind.IPHONE, "iPhone", null); idle()
        assertEquals(null, m.pendingSwitch.value)
        assertEquals(listOf("CARPLAY start WIRELESS WIFI_DIRECT", "CARPLAY start USB null"), starts)
        // Once projecting wirelessly, the driver is still asked.
        m.connect("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        carPlay.emit(AdapterEvent.Connected); idle()
        m.onUsbPhone(PhoneKind.IPHONE, "iPhone", null); idle()
        assertEquals(Transport.USB, m.pendingSwitch.value?.transport)
        // The other engine's phone is always a question.
        m.declineSwitch()
        m.connect("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        m.onUsbPhone(PhoneKind.ANDROID, "S25", null); idle()
        assertEquals(Engine.ANDROID_AUTO, m.pendingSwitch.value?.engine)
    }

    @Test fun pathsThatKeepFailingForThisPhoneAreTriedLater() {
        val request = SessionRequest(Engine.ANDROID_AUTO, Transport.WIRELESS, "AA:BB", "Pixel")
        repeat(2) { profiles.recordFailure(request, WirelessPath.WIFI_DIRECT, FailureReason.PHONE_REFUSED_WIFI.name, ProgressStage.JOINING_WIFI, null) }
        // A Bluetooth failure is not the path's fault and changes nothing.
        val other = SessionRequest(Engine.ANDROID_AUTO, Transport.WIRELESS, "CC:DD", "Pixel 2")
        repeat(3) { profiles.recordFailure(other, WirelessPath.WIFI_DIRECT, FailureReason.BLUETOOTH.name, ProgressStage.WAITING_FOR_PHONE, null) }
        val m = manager()
        m.connect("AA:BB", PhoneKind.ANDROID, "Pixel"); idle()
        assertEquals("ANDROID_AUTO start WIRELESS CAR_HOTSPOT", starts.last())
        m.connect("CC:DD", PhoneKind.ANDROID, "Pixel 2"); idle()
        assertEquals("ANDROID_AUTO start WIRELESS WIFI_DIRECT", starts.last())
    }

    @Test fun theIphoneIsPagedWhileThePreviousEngineStops() {
        // Plan item 7: the page overlaps the stop; DiPlay dials only after it.
        env.warmUpAvailable = true
        androidAuto.stopsAtOnce = false
        val m = manager()
        m.connect("CC:DD", PhoneKind.ANDROID, "Pixel"); idle()
        m.connect("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        assertEquals(1, env.warmUps)
        androidAuto.completeStop(); idle()
        assertEquals("waits for the page", 1, starts.size)
        env.warmUpDone!!(); idle()
        assertEquals("CARPLAY start WIRELESS WIFI_DIRECT", starts.last())
        // From idle there is nothing to overlap.
        m.stop(); idle()
        m.connect("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        assertEquals(1, env.warmUps)
    }

    @Test fun aWarmUpThatNeverAnswersDelaysTheStartByAtMostItsCap() {
        env.warmUpAvailable = true
        val m = manager()
        m.connect("CC:DD", PhoneKind.ANDROID, "Pixel"); idle()
        androidAuto.stopsAtOnce = false
        m.connect("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        androidAuto.completeStop(); idle()
        advance(SessionManager.WARM_UP_MAX_MILLIS - 100)
        assertEquals(1, starts.size)
        advance(200)
        assertEquals("CARPLAY start WIRELESS WIFI_DIRECT", starts.last())
    }

    @Test fun eventsFromAnAttemptTheWatchdogReplacedAreIgnored() {
        val m = manager()
        m.connect("AA:BB", PhoneKind.IPHONE, "iPhone"); idle()
        carPlay.emit(AdapterEvent.Progress(4, 4, ProgressStage.STARTING_PROJECTION)); idle()
        val old = carPlay.listener!!
        advance(20_001)
        assertEquals(2, starts.size)
        old(AdapterEvent.Connected); idle()
        assertTrue(active(m).phase != SessionState.Phase.Connected)
    }

    @Test fun aUsbSessionThatHadAPictureEndsQuicklyWhenUnpluggedWhileRetrying() {
        // K15: a retrying session that once projected used the 3.5 s grace.
        val m = manager()
        m.onUsbPhone(PhoneKind.IPHONE, "iPhone", null); idle()
        carPlay.emit(AdapterEvent.Connected); idle()
        carPlay.emit(AdapterEvent.Retrying(1, FailureReason.USB)); idle()
        m.onUsbDetached()
        advance(SessionManager.USB_GONE_CONNECTED_GRACE_MILLIS + 1)
        assertEquals(SessionState.Idle, m.state.value)
    }

    @Test fun aLostPictureGetsFreshDeadlines() {
        val m = manager()
        m.connect("AA:BB", PhoneKind.ANDROID, "Pixel"); idle()
        androidAuto.emit(AdapterEvent.Progress(4, 4, ProgressStage.STARTING_PROJECTION)); idle()
        androidAuto.emit(AdapterEvent.Connected); idle()
        advance(120_000)
        androidAuto.emit(AdapterEvent.Retrying(1, FailureReason.LINK_TOO_WEAK)); idle()
        advance(1_000)
        assertTrue("no instant restart from stale timers", m.state.value is SessionState.Active)
        assertEquals(1, starts.size)
    }
}
