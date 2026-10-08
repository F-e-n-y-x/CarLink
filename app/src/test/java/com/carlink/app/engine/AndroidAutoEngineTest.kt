package com.carlink.app.engine

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.os.Looper
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkProblem
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.Transport
import com.carlink.app.engine.AndroidAutoProtocol as P
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** [AndroidAutoEngine] against simulated ":aa" state reports; commands are recorded, not run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidAutoEngineTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val states = mutableListOf<LinkState>()
    private val commands = mutableListOf<String>()

    private fun engine() = AndroidAutoEngine(app, { states += it }) { commands += it.getStringExtra(P.EXTRA_COMMAND)!! }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun setProcessAlive(alive: Boolean) {
        val am = app.getSystemService(ActivityManager::class.java)
        shadowOf(am).setProcesses(if (alive) listOf(ActivityManager.RunningAppProcessInfo().apply {
            processName = app.packageName + P.ENGINE_PROCESS_SUFFIX
        }) else emptyList())
    }

    private fun report(connection: String, serviceRunning: Boolean = true, message: String? = null) {
        app.sendBroadcast(Intent(P.ACTION_STATE).setPackage(app.packageName)
            .putExtra(P.EXTRA_CONNECTION, connection)
            .putExtra(P.EXTRA_SERVICE_RUNNING, serviceRunning)
            .putExtra(P.EXTRA_MESSAGE, message))
        idle()
    }

    private fun started(transport: Transport = Transport.WIRELESS) = engine().also {
        setProcessAlive(true)
        it.start(transport, "AA:BB:CC:DD:EE:FF", "Pixel 9", null)
        states.clear()
    }

    @Test fun startSendsTheMatchingCommandAndWaits() {
        engine().start(Transport.WIRELESS, "AA:BB:CC:DD:EE:FF", "Pixel 9", null)
        engine().start(Transport.USB, null, "Pixel 9", null)
        assertEquals(listOf(P.COMMAND_START_WIRELESS, P.COMMAND_START_USB), commands)
        assertTrue(states.all { it.stage == LinkStage.WAITING && it.engine == Engine.ANDROID_AUTO })
    }

    @Test fun connectionNamesMapToStages() {
        started()
        report(P.CONNECTION_DISCONNECTED, serviceRunning = true)
        assertEquals(LinkStage.WAITING, states.last().stage)
        assertEquals(1, states.last().step)
        report(P.CONNECTION_CONNECTING)
        assertEquals(LinkStage.CONNECTING to 2, states.last().stage to states.last().step)
        report(P.CONNECTION_CONNECTED)
        assertEquals(LinkStage.CONNECTING to 3, states.last().stage to states.last().step)
        report(P.CONNECTION_STARTING_TRANSPORT)
        assertEquals(LinkStage.CONNECTING to 4, states.last().stage to states.last().step)
        report(P.CONNECTION_HANDSHAKE_COMPLETE)
        assertEquals(LinkStage.CONNECTING to 5, states.last().stage to states.last().step)
        report(P.CONNECTION_TRANSPORT_STARTED)
        assertEquals(LinkStage.CONNECTED to 6, states.last().stage to states.last().step)
        assertEquals(6, states.last().stepCount)
        assertEquals(Transport.WIRELESS, states.last().transport)
        assertEquals("Pixel 9", states.last().phoneName)
    }

    @Test fun errorIsAnEngineFailureWithTheMessage() {
        started()
        report(P.CONNECTION_ERROR, message = "SSL handshake failed")
        val state = states.last()
        assertEquals(LinkStage.FAILED, state.stage)
        assertEquals(LinkProblem.ENGINE, state.problem)
        assertEquals("SSL handshake failed", state.detail)
    }

    @Test fun notRunningDuringTheStartGraceIsStillWaiting() {
        // ":aa" stops a stuck service and clears Wi-Fi Direct before starting AapService.
        started()
        report(P.CONNECTION_DISCONNECTED, serviceRunning = false)
        assertEquals(LinkStage.WAITING, states.last().stage)
        advance(10_000)
        report(P.CONNECTION_DISCONNECTED, serviceRunning = false)
        assertEquals(LinkStage.WAITING, states.last().stage)
    }

    @Test fun notRunningAfterTheStartGraceEndsTheSession() {
        started()
        advance(15_500)
        report(P.CONNECTION_DISCONNECTED, serviceRunning = false)
        assertEquals(LinkState.Idle, states.last())
        // Disengaged: later reports are ignored.
        states.clear()
        report(P.CONNECTION_TRANSPORT_STARTED)
        assertTrue(states.isEmpty())
    }

    @Test fun reportsAreIgnoredWhenNotEngaged() {
        engine()
        report(P.CONNECTION_TRANSPORT_STARTED)
        assertTrue(states.isEmpty())
    }

    @Test fun pollingAsksForReportsWhileTheProcessLives() {
        started()
        commands.clear()
        advance(9_100)
        assertEquals(listOf(P.COMMAND_REPORT, P.COMMAND_REPORT, P.COMMAND_REPORT), commands)
    }

    @Test fun deadProcessEndsTheSessionOnlyAfterTheGraceAndTwoPolls() {
        started()
        commands.clear()
        setProcessAlive(false)
        // Inside the start grace a missing process is the start in progress.
        advance(15_000)
        assertTrue(states.none { it == LinkState.Idle })
        advance(6_100)
        assertEquals(LinkState.Idle, states.last())
        // Polls do not wake ":aa" just to hear nothing runs there.
        val reportsAfterDeath = commands.size
        advance(30_000)
        assertEquals(reportsAfterDeath, commands.size)
    }

    @Test fun stopReportsIdleAndStopsTheServiceOnlyIfAlive() {
        val engine = started()
        commands.clear()
        engine.stop()
        assertEquals(listOf(P.COMMAND_STOP), commands)
        assertEquals(LinkState.Idle, states.last())
        commands.clear()
        setProcessAlive(false)
        engine.stop()
        assertTrue("no command would start ':aa' again", commands.isEmpty())
    }

    @Test fun refreshAdoptsOnlyALiveEngine() {
        setProcessAlive(false)
        val engine = engine()
        engine.refresh()
        assertTrue(commands.isEmpty())
        report(P.CONNECTION_TRANSPORT_STARTED)
        assertTrue(states.isEmpty())
        setProcessAlive(true)
        engine.refresh()
        assertEquals(listOf(P.COMMAND_REPORT), commands)
        report(P.CONNECTION_TRANSPORT_STARTED)
        assertEquals(LinkStage.CONNECTED, states.last().stage)
    }
}
