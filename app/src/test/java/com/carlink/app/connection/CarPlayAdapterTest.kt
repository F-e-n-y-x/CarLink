package com.carlink.app.connection

import android.app.Application
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Looper
import com.carlink.app.core.Engine
import com.carlink.app.core.Transport
import com.carlink.app.settings.WirelessPath
import com.shilapi.xcertplay.CarPlayHostIntegration.HostState
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.WirelessStartupTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CarPlayAdapterTest {

    /** DiPlay as the adapter sees it: a host that is up until its teardown completes. */
    private class FakeHooks : CarPlayEngineHooks {
        var session = false
        var opened = mutableListOf<Intent?>()
        var selected: Pair<String, String>? = null
        var wireless: Boolean? = null
        var openError: Throwable? = null
        val pendingStops = mutableListOf<() -> Unit>()
        private val statusListeners = mutableListOf<(CarPlayStatus?) -> Unit>()
        private val hostListeners = mutableListOf<(HostState) -> Unit>()

        override fun hasSession() = session
        override fun stopSession(completion: () -> Unit) {
            if (!session) completion() else pendingStops += completion
        }
        fun finishTeardown() {
            session = false
            pendingStops.toList().also { pendingStops.clear() }.forEach { it() }
        }
        override fun addStatusListener(listener: (CarPlayStatus?) -> Unit) { statusListeners += listener }
        override fun removeStatusListener(listener: (CarPlayStatus?) -> Unit) { statusListeners -= listener }
        override fun addHostStateListener(listener: (HostState) -> Unit) { hostListeners += listener }
        override fun removeHostStateListener(listener: (HostState) -> Unit) { hostListeners -= listener }
        override fun selectPhone(address: String, name: String) { selected = address to name }
        override fun setWirelessEnabled(enabled: Boolean) { wireless = enabled }
        override fun openHost(usbAttach: Intent?): Throwable? {
            opened += usbAttach
            if (openError == null) session = true
            return openError
        }
        var milestones: ((WirelessStartupTimeline.Phase) -> Unit)? = null
        override fun setMilestoneListener(listener: ((WirelessStartupTimeline.Phase) -> Unit)?) { milestones = listener }
        fun status(status: CarPlayStatus?) = statusListeners.toList().forEach { it(status) }
        fun host(state: HostState) = hostListeners.toList().forEach { it(state) }
        val listening get() = statusListeners.size + hostListeners.size
    }

    private val hooks = FakeHooks()
    private val adapter = CarPlayAdapter(hooks)
    private val events = mutableListOf<AdapterEvent>()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val wirelessPlan = SessionPlan(Engine.CARPLAY, Transport.WIRELESS, WirelessPath.SAME_WIFI,
        phoneAddress = "AA:BB:CC:DD:EE:FF", phoneName = "iPhone")

    @Test fun startSelectsThePhoneAndOpensTheHost() {
        adapter.start(wirelessPlan) { events += it }
        idle()
        assertEquals("AA:BB:CC:DD:EE:FF" to "iPhone", hooks.selected)
        assertEquals(true, hooks.wireless)
        assertEquals(listOf<Intent?>(null), hooks.opened)
        assertEquals(listOf<AdapterEvent>(AdapterEvent.Progress(1, 4, ProgressStage.WAITING_FOR_PHONE)), events)
        assertTrue(adapter.hasSession())
    }

    @Test fun usbPassesTheAttachIntentAndForcesWired() {
        val attach = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        adapter.start(SessionPlan(Engine.CARPLAY, Transport.USB, null, null, null, usbAttach = attach)) { events += it }
        idle()
        assertEquals(false, hooks.wireless)
        assertEquals(listOf<Intent?>(attach), hooks.opened)
        assertEquals(null, hooks.selected)
    }

    @Test fun nullStatusKeepsTheSession() {
        adapter.start(wirelessPlan) { events += it }
        hooks.status(CarPlayStatus.RunningWireless)
        hooks.status(null) // DiPlay's internal restart (F13)
        idle()
        assertEquals(AdapterEvent.Progress(2, 4, ProgressStage.HANDSHAKE), events.last())
        assertTrue(adapter.hasSession())
    }

    @Test fun firstTcpTimeoutRetriesThenFails() {
        adapter.start(wirelessPlan) { events += it }
        val failed = CarPlayStatus.Failed("No AirPlay TCP after CarPlay StartSession", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT)
        hooks.status(CarPlayStatus.RunningWireless)
        // Host state first, then the bus copy: the order CarPlayController.onStatus delivers them.
        hooks.host(HostState.Retrying(1, 5, 2_000L, failed, "r"))
        hooks.status(failed)
        idle()
        assertEquals(AdapterEvent.Retrying(1, FailureReason.PHONE_NEVER_REACHED_CAR), events.last())
        hooks.host(HostState.RetriesStopped(failed, WirelessStartupFailure.FIRST_TCP_TIMEOUT, 5, exhausted = true))
        idle()
        assertEquals(AdapterEvent.Failed(FailureReason.PHONE_NEVER_REACHED_CAR, failed.message), events.last())
        assertEquals(0, hooks.listening) // terminal: no more events
        assertTrue(adapter.hasSession()) // the host still shows its exhausted state until stop()
    }

    @Test fun connectedAtTheFirstFrameAndEndedWhenTheDriverDisconnects() {
        adapter.start(wirelessPlan) { events += it }
        hooks.status(CarPlayStatus.WirelessActive)
        hooks.host(HostState.FirstFrame)
        idle()
        assertEquals(AdapterEvent.Connected, events.last())
        hooks.session = false
        hooks.host(HostState.Closed) // DiPlay's notification Disconnect
        idle()
        assertEquals(AdapterEvent.Ended(byUser = true), events.last())
    }

    @Test fun stopCompletesOnlyAfterTeardown() {
        adapter.start(wirelessPlan) { events += it }
        idle()
        var stopped = false
        adapter.stop { stopped = true }
        idle()
        assertFalse(stopped)
        assertTrue(adapter.hasSession())
        hooks.host(HostState.Closed) // the teardown's own Closed is not an event after stop()
        hooks.finishTeardown()
        idle()
        assertTrue(stopped)
        assertFalse(adapter.hasSession())
        assertFalse(events.any { it is AdapterEvent.Ended })
    }

    @Test fun stopWithNothingRunningCompletesAtOnce() {
        var stopped = false
        adapter.stop { stopped = true }
        idle()
        assertTrue(stopped)
    }

    @Test fun startWaitsForAPreviousTeardown() {
        hooks.session = true // a previous session is still closing
        adapter.start(wirelessPlan) { events += it }
        idle()
        assertTrue(hooks.opened.isEmpty())
        hooks.finishTeardown()
        idle()
        assertEquals(1, hooks.opened.size)
    }

    @Test fun aHostThatCannotOpenFails() {
        hooks.openError = IllegalStateException("background start blocked")
        adapter.start(wirelessPlan) { events += it }
        idle()
        assertEquals(AdapterEvent.Failed(FailureReason.ENGINE, "background start blocked"), events.last())
    }

    @Test fun wirelessMilestonesBecomeProgressUntilTheSessionEnds() {
        // RELIABILITY_AUDIT K4: the Wi-Fi details and the first AirPlay TCP are steps 3 and 4.
        adapter.start(wirelessPlan) { events += it }
        hooks.status(CarPlayStatus.RunningWireless)
        hooks.milestones!!(WirelessStartupTimeline.Phase.WIFI_CONFIG)
        hooks.milestones!!(WirelessStartupTimeline.Phase.TCP_ACCEPTED)
        idle()
        assertEquals(listOf(ProgressStage.WAITING_FOR_PHONE, ProgressStage.HANDSHAKE, ProgressStage.JOINING_WIFI,
            ProgressStage.STARTING_PROJECTION), events.map { (it as AdapterEvent.Progress).stage })
        hooks.host(HostState.Closed)
        idle()
        assertEquals("no listener once the session is over", null, hooks.milestones)
    }
}
