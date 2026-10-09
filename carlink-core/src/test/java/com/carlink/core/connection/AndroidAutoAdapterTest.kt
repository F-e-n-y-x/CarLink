package com.carlink.app.connection

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import com.andrerinas.openheadunit.HostIntegration.EngineEvent
import com.carlink.app.connection.aa.AaProtocol as P
import com.carlink.app.connection.aa.AaSessionTracker
import com.carlink.app.core.Engine
import com.carlink.app.core.Transport
import com.carlink.app.settings.VehicleProfile
import com.carlink.app.settings.WirelessPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** [AndroidAutoAdapter] against simulated ":aa" broadcasts; commands are recorded, not run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidAutoAdapterTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val commands = mutableListOf<Intent>()
    private val events = mutableListOf<AdapterEvent>()
    private var alive = false
    private val adapters = mutableListOf<AndroidAutoAdapter>()

    private fun adapter(profile: VehicleProfile = VehicleProfile.GENERIC) = AndroidAutoAdapter(
        app, { profile }, { commands += it }, { alive }, SystemClock::elapsedRealtime,
        AaSessionTracker.Limits(), stopTimeoutMillis = 20_000,
    ).also { adapters += it }

    @After fun release() = adapters.forEach { it.release() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    private fun command(i: Intent) = i.getStringExtra(P.EXTRA_COMMAND)

    private fun wirelessPlan() = SessionPlan(Engine.ANDROID_AUTO, Transport.WIRELESS, WirelessPath.WIFI_DIRECT,
        "AA:BB:CC:DD:EE:FF", "Pixel 9")

    private fun state(connection: String, running: Boolean = true, firstFrame: Boolean = false) {
        app.sendBroadcast(Intent(P.ACTION_STATE).setPackage(app.packageName)
            .putExtra(P.EXTRA_CONNECTION, connection).putExtra(P.EXTRA_SERVICE_RUNNING, running)
            .putExtra(P.EXTRA_FIRST_FRAME, firstFrame))
        idle()
    }

    private fun event(kind: String, detail: String? = null) {
        app.sendBroadcast(Intent(P.ACTION_EVENT).setPackage(app.packageName)
            .putExtra(P.EXTRA_KIND, kind).putExtra(P.EXTRA_DETAIL, detail))
        idle()
    }

    private fun stopped(token: Long, group: Boolean? = true) {
        app.sendBroadcast(Intent(P.ACTION_STOPPED).setPackage(app.packageName)
            .putExtra(P.EXTRA_TOKEN, token).putExtra(P.EXTRA_SERVICE_STOPPED, true)
            .apply { if (group != null) putExtra(P.EXTRA_GROUP_RELEASED, group) })
        idle()
    }

    private fun started(profile: VehicleProfile = VehicleProfile.GENERIC) = adapter(profile).also {
        alive = true
        it.start(wirelessPlan()) { e -> events += e }
        idle()
    }

    @Test fun startSendsTheWirelessCommandWithPhoneAndProfile() {
        val a = started(VehicleProfile.BYD_DILINK)
        val start = commands.single()
        assertEquals(P.COMMAND_START_WIRELESS, command(start))
        assertEquals("AA:BB:CC:DD:EE:FF", start.getStringExtra(P.EXTRA_ADDRESS))
        assertEquals("BYD_DILINK", start.getStringExtra(P.EXTRA_VEHICLE_PROFILE))
        assertEquals(listOf(AdapterEvent.Progress(1, 4, ProgressStage.WAITING_FOR_PHONE)), events)
        assertTrue(a.hasSession())
        assertEquals(Engine.ANDROID_AUTO, a.engine)
    }

    @Test fun usbStartCarriesTheAttachedDevice() {
        val a = adapter()
        a.start(SessionPlan(Engine.ANDROID_AUTO, Transport.USB, null, null, "Pixel", usbAttach = Intent())) {}
        assertEquals(P.COMMAND_START_USB, command(commands.single()))
    }

    @Test fun engineReportsBecomeAdapterEvents() {
        started()
        event(EngineEvent.BT_CHANNEL_ACCEPTED.name, "address=AA:BB:CC:DD:EE:FF")
        state(P.CONNECTION_CONNECTING)
        state(P.CONNECTION_TRANSPORT_STARTED, firstFrame = true)
        assertEquals(listOf(
            AdapterEvent.Progress(1, 4, ProgressStage.WAITING_FOR_PHONE),
            AdapterEvent.Progress(2, 4, ProgressStage.HANDSHAKE),
            AdapterEvent.Progress(4, 4, ProgressStage.STARTING_PROJECTION),
            AdapterEvent.Connected,
        ), events)
    }

    @Test fun stopCompletesOnlyOnTheMatchingAcknowledgement() {
        val a = started()
        var stoppedCalls = 0
        a.stop { stoppedCalls++ }
        a.stop { stoppedCalls++ } // a second caller joins the same stop
        val stop = commands.filter { command(it) == P.COMMAND_STOP }.single()
        val token = stop.getLongExtra(P.EXTRA_TOKEN, -1)
        assertTrue(a.hasSession())
        stopped(token + 1)
        assertEquals(0, stoppedCalls)
        assertTrue(a.hasSession())
        stopped(token)
        assertEquals(2, stoppedCalls)
        assertFalse(a.hasSession())
    }

    @Test fun nothingIsReportedOnceStopping() {
        val a = started()
        events.clear()
        a.stop {}
        event(EngineEvent.WIFI_OFF.name)
        state(P.CONNECTION_CONNECTING)
        assertEquals(emptyList<AdapterEvent>(), events)
    }

    @Test fun stopWithNothingRunningCompletesAtOnce() {
        val a = adapter()
        var done = false
        a.stop { done = true }
        idle()
        assertTrue(done)
        assertTrue(commands.isEmpty())
    }

    @Test fun anUnansweredStopCompletesAfterItsTimeout() {
        val a = started()
        var done = false
        a.stop { done = true }
        advance(19_000)
        assertFalse(done)
        advance(1_500)
        assertTrue(done)
        assertFalse(a.hasSession())
    }

    @Test fun theEngineProcessDyingFailsTheSessionButKeepsItOpenUntilCleanedUp() {
        val a = started()
        advance(6_000)
        alive = false
        advance(4_000)
        assertEquals(FailureReason.ENGINE, (events.last() as AdapterEvent.Failed).reason)
        assertTrue("the group may outlive the process", a.hasSession())
        a.stop {}
        val token = commands.last { command(it) == P.COMMAND_STOP }.getLongExtra(P.EXTRA_TOKEN, -1)
        stopped(token)
        assertFalse(a.hasSession())
    }

    @Test fun anEngineFoundAtStartUpCountsAsASessionUntilItReportsIdle() {
        alive = true
        val a = adapter()
        assertTrue(a.hasSession())
        assertEquals(P.COMMAND_REPORT, command(commands.single()))
        state(P.CONNECTION_DISCONNECTED, running = false)
        assertFalse(a.hasSession())

        val running = adapter()
        state(P.CONNECTION_TRANSPORT_STARTED, running = true)
        assertTrue("an engine nobody started must be stopped first", running.hasSession())
    }

    @Test fun aSilentEngineProcessAtStartUpIsTreatedAsIdle() {
        alive = true
        val a = adapter()
        advance(5_500)
        assertFalse(a.hasSession())
    }

    @Test fun thePhoneIsWokenAgainWhileItDoesNotAnswer() {
        started()
        advance(46_000)
        val poke = commands.last()
        assertEquals(P.COMMAND_POKE, command(poke))
        assertEquals("AA:BB:CC:DD:EE:FF", poke.getStringExtra(P.EXTRA_ADDRESS))
        assertTrue(events.contains(AdapterEvent.Retrying(1, FailureReason.PHONE_DID_NOT_ANSWER)))
    }

    @Test(expected = IllegalStateException::class)
    fun startWhileASessionIsOpenIsABug() {
        val a = started()
        a.start(wirelessPlan()) {}
    }

    // ---- RELIABILITY_AUDIT K2 and the stall watchdog's recoveries ----

    @Test fun aUsbSessionEndingBeforeAnyFrameSendsTheAccessorySwitchAgain() {
        val a = adapter()
        alive = true
        a.start(SessionPlan(Engine.ANDROID_AUTO, Transport.USB, null, null, "S25", usbAttach = Intent())) { e -> events += e }
        idle()
        state(P.CONNECTION_CONNECTING)
        state(P.CONNECTION_DISCONNECTED)
        advance(1_100)
        assertEquals(P.COMMAND_RESWITCH_USB, command(commands.last()))
        assertEquals(FailureReason.USB, (events.last() as AdapterEvent.Retrying).reason)
        // No new session within the re-plug window: say so in seconds, not after 60 s.
        advance(AaSessionTracker.Limits().staleReplugMillis + 1_000)
        val failed = events.last() as AdapterEvent.Failed
        assertTrue(failed.detail!!.startsWith("unplug the phone"))
    }

    @Test fun theWatchdogCanNudgeAWirelessPhoneAndReswitchAUsbOne() {
        val wireless = started()
        assertTrue(wireless.recover(RecoveryAction.NUDGE))
        assertEquals(P.COMMAND_POKE, command(commands.last()))
        assertFalse(wireless.recover(RecoveryAction.RESWITCH_USB))
        assertFalse(wireless.recover(RecoveryAction.RETRY_STEP))
        wireless.stop {}
        assertFalse("not while stopping", wireless.recover(RecoveryAction.NUDGE))

        alive = false // a fresh adapter that finds no engine left running
        val usb = adapter()
        usb.start(SessionPlan(Engine.ANDROID_AUTO, Transport.USB, null, null, "S25", usbAttach = Intent())) {}
        assertTrue(usb.recover(RecoveryAction.RESWITCH_USB))
        assertEquals(P.COMMAND_RESWITCH_USB, command(commands.last()))
        assertFalse(usb.recover(RecoveryAction.NUDGE))
    }
}
