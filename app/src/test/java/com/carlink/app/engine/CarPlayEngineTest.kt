package com.carlink.app.engine

import android.app.Application
import android.os.Looper
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkProblem
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.Transport
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.DiPlaySessionService
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CarPlayEngineTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    /** Stands in for DiPlay's CarPlayStatusBus. */
    private class FakeBus : CarPlayEngine.CarPlayStatusSource {
        override var current: CarPlayStatus? = null
        private val listeners = mutableListOf<(CarPlayStatus?) -> Unit>()
        override fun addListener(listener: (CarPlayStatus?) -> Unit) {
            listeners += listener
            listener(current)
        }
        fun publish(status: CarPlayStatus?) {
            current = status
            listeners.forEach { it(status) }
        }
    }

    private val bus = FakeBus()
    private val states = mutableListOf<LinkState>()
    private var preflightProblem: LinkProblem? = null

    private fun engine() = CarPlayEngine(app, { states += it }, bus, { _, _, _ -> preflightProblem })
        .also { idle(); states.clear() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    private fun publish(status: CarPlayStatus?) { bus.publish(status); idle() }
    private fun startedActivity() = shadowOf(app).nextStartedActivity

    // --- Status mapping (every CarPlayStatus) ---------------------------------------------------

    private val hotspot = CarPlayStatus.HotspotReady("CarLink", "5GHz", 36, "02:00:00:00:00:01", "192.168.43.1", "byd")

    /** Expected stage and step for each status; step count is always 6. */
    private val expected: Map<CarPlayStatus, Pair<LinkStage, Int>> = mapOf(
        CarPlayStatus.DiscoveringMfi to (LinkStage.CONNECTING to 1),
        CarPlayStatus.WaitingForMfi to (LinkStage.WAITING to 1),
        CarPlayStatus.RequestingMfiPermission to (LinkStage.CONNECTING to 1),
        CarPlayStatus.MfiReady to (LinkStage.CONNECTING to 1),
        CarPlayStatus.StartingHotspot to (LinkStage.CONNECTING to 3),
        hotspot to (LinkStage.CONNECTING to 3),
        CarPlayStatus.WaitingForPairedIphone to (LinkStage.WAITING to 2),
        CarPlayStatus.ConnectingBluetooth to (LinkStage.CONNECTING to 3),
        CarPlayStatus.RunningWireless to (LinkStage.CONNECTING to 4),
        CarPlayStatus.WirelessActive to (LinkStage.CONNECTED to 6),
        CarPlayStatus.WirelessActiveFallback to (LinkStage.CONNECTED to 6),
        CarPlayStatus.DiscoveringIphone to (LinkStage.CONNECTING to 2),
        CarPlayStatus.WaitingForIphone to (LinkStage.WAITING to 2),
        CarPlayStatus.RequestingIphonePermission to (LinkStage.CONNECTING to 2),
        CarPlayStatus.WaitingForReenumeration to (LinkStage.CONNECTING to 3),
        CarPlayStatus.SelectingConfiguration to (LinkStage.CONNECTING to 3),
        CarPlayStatus.OpeningDataPaths to (LinkStage.CONNECTING to 3),
        CarPlayStatus.Pairing to (LinkStage.CONNECTING to 4),
        CarPlayStatus.ConnectingControl to (LinkStage.CONNECTING to 5),
        CarPlayStatus.AttachingNetwork to (LinkStage.CONNECTING to 4),
        CarPlayStatus.RunningControl to (LinkStage.CONNECTED to 6),
    )

    @Test fun everyStatusIsCovered() {
        // A status DiPlay adds later must get a deliberate mapping (and a row in this test).
        val subclasses = CarPlayStatus::class.java.declaredClasses
            .filter { CarPlayStatus::class.java.isAssignableFrom(it) }
            .map { it.simpleName }.toSet()
        val covered = expected.keys.map { it.javaClass.simpleName }.toSet() + setOf("ControlEnded", "Failed")
        assertEquals(subclasses, covered)
    }

    @Test fun everyProgressStatusMapsToItsStageAndStep() {
        val engine = engine()
        engine.start(Transport.USB, null, "Sara’s iPhone", null)
        states.clear()
        expected.forEach { (status, stageAndStep) ->
            publish(status)
            val state = states.last()
            assertEquals("$status", stageAndStep.first, state.stage)
            assertEquals("$status", stageAndStep.second, state.step)
            assertEquals("$status", 6, state.stepCount)
            assertEquals(Engine.CARPLAY, state.engine)
            assertEquals(Transport.USB, state.transport)
            assertEquals("Sara’s iPhone", state.phoneName)
        }
    }

    @Test fun failedIsAnEngineProblemWithDiPlaysMessage() {
        val engine = engine()
        engine.start(Transport.WIRELESS, null, "iPhone", null)
        publish(CarPlayStatus.Failed("iAP2 authentication failed"))
        val state = states.last()
        assertEquals(LinkStage.FAILED, state.stage)
        assertEquals(LinkProblem.ENGINE, state.problem)
        assertEquals("iAP2 authentication failed", state.detail)
        assertEquals(0, state.step)
    }

    @Test fun controlEndedAndClosedControllerAreIdle() {
        val engine = engine()
        engine.start(Transport.USB, null, "iPhone", null)
        publish(CarPlayStatus.RunningControl)
        publish(CarPlayStatus.ControlEnded)
        assertEquals(LinkState.Idle, states.last())
        publish(null)
        assertEquals(LinkState.Idle, states.last())
    }

    @Test fun currentReflectsTheBus() {
        bus.current = CarPlayStatus.RunningControl
        assertEquals(LinkStage.CONNECTED, engine().current().stage)
        bus.current = null
        assertEquals(LinkState.Idle, CarPlayEngine(app, {}, bus, { _, _, _ -> null }).current())
    }

    // --- Start, pre-flight and timeout ----------------------------------------------------------

    @Test fun startOpensOnlyTheCarPlayPicture() {
        engine().start(Transport.USB, null, "iPhone", null)
        assertEquals(LinkStage.WAITING, states.last().stage)
        assertEquals(CarPlayHostActivity::class.java.name, startedActivity().component?.className)
    }

    @Test fun preflightProblemFailsWithoutOpeningCarPlay() {
        preflightProblem = LinkProblem.CARPLAY_AUTH_MISSING
        engine().start(Transport.USB, null, "iPhone", null)
        assertEquals(LinkStage.FAILED, states.single().stage)
        assertEquals(LinkProblem.CARPLAY_AUTH_MISSING, states.single().problem)
        assertNull(startedActivity())
    }

    @Test fun silentPictureFailsAfterSixtySeconds() {
        engine().start(Transport.USB, null, "iPhone", null)
        advance(59_000)
        assertEquals(LinkStage.WAITING, states.last().stage)
        advance(2_000)
        assertEquals(LinkStage.FAILED, states.last().stage)
        assertEquals(LinkProblem.ENGINE, states.last().problem)
        // A late status still recovers the attempt.
        publish(CarPlayStatus.ConnectingControl)
        assertEquals(LinkStage.CONNECTING, states.last().stage)
    }

    @Test fun aStatusCancelsTheTimeout() {
        engine().start(Transport.USB, null, "iPhone", null)
        publish(CarPlayStatus.WaitingForIphone)
        advance(120_000)
        assertEquals(LinkStage.WAITING, states.last().stage)
        assertTrue(states.none { it.stage == LinkStage.FAILED })
    }

    // --- Stale statuses of a closing session are dropped ----------------------------------------

    @Test fun statusesOfASessionTheDriverStoppedAreDropped() {
        val engine = engine()
        engine.start(Transport.USB, null, "iPhone", null)
        publish(CarPlayStatus.RunningControl)
        engine.stop()
        assertEquals(LinkState.Idle, states.last())
        assertEquals(DiPlaySessionService.ACTION_STOP, shadowOf(app).nextStartedService?.action)
        states.clear()
        // Shutdown noise: none of it may re-adopt or fail anything.
        publish(CarPlayStatus.RunningControl)
        publish(CarPlayStatus.Failed("socket closed"))
        publish(CarPlayStatus.ControlEnded)
        assertTrue("$states", states.isEmpty())
    }

    @Test fun statusesAfterTheClosingWindowAreForwardedAgain() {
        val engine = engine()
        engine.start(Transport.USB, null, "iPhone", null)
        publish(CarPlayStatus.RunningControl)
        engine.stop()
        states.clear()
        advance(5_500)
        // DiPlay's own notification started a new session.
        publish(CarPlayStatus.WaitingForIphone)
        assertEquals(LinkStage.WAITING, states.single().stage)
    }

    @Test fun closedReportEndsTheClosingWindowAtOnce() {
        val engine = engine()
        engine.start(Transport.USB, null, "iPhone", null)
        publish(CarPlayStatus.RunningControl)
        engine.stop()
        publish(null)
        states.clear()
        publish(CarPlayStatus.WaitingForIphone)
        assertEquals(LinkStage.WAITING, states.single().stage)
    }

    @Test fun restartClosesTheRunningSessionAndIgnoresItsEnd() {
        val engine = engine()
        engine.start(Transport.WIRELESS, null, "iPhone", null)
        publish(CarPlayStatus.WirelessActive)
        startedActivity()
        states.clear()
        // The driver plugs the iPhone in: CarPlay restarts over USB.
        engine.start(Transport.USB, null, "iPhone", null)
        assertEquals(LinkStage.WAITING, states.single().stage)
        assertEquals(DiPlaySessionService.ACTION_STOP, shadowOf(app).nextStartedService?.action)
        assertNull("the picture opens only after the old session closed", startedActivity())
        // The old session's end must not end or fail the new attempt.
        publish(CarPlayStatus.Failed("connection reset"))
        publish(CarPlayStatus.ControlEnded)
        assertEquals(listOf(LinkStage.WAITING), states.map { it.stage })
        publish(null)
        assertEquals(CarPlayHostActivity::class.java.name, startedActivity()?.component?.className)
        assertEquals(listOf(LinkStage.WAITING), states.map { it.stage })
        publish(CarPlayStatus.WaitingForIphone)
        assertEquals(Transport.USB, states.last().transport)
    }

    @Test fun restartOpensThePictureAnywayIfTheOldSessionNeverReportsClosed() {
        val engine = engine()
        engine.start(Transport.WIRELESS, null, "iPhone", null)
        publish(CarPlayStatus.WirelessActive)
        startedActivity()
        engine.start(Transport.USB, null, "iPhone", null)
        assertNull(startedActivity())
        advance(5_500)
        assertEquals(CarPlayHostActivity::class.java.name, startedActivity()?.component?.className)
    }
}
