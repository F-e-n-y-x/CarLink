package com.shilapi.xcertplay

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** CarLink: hasSession/stopSession cover the host's whole life (CarPlayHostIntegration). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarLinkHostSessionsTest {
    private val states = mutableListOf<CarPlayHostIntegration.HostState>()

    /** A host window whose teardown completes when the test says so. */
    private inner class FakeHost : HostSessions.Host {
        override var ended = false
        var pendingTeardown: (() -> Unit)? = null
        override fun end(done: () -> Unit) {
            ended = true
            HostSessions.teardownStarted()
            pendingTeardown = { HostSessions.teardownFinished(); done() }
        }
        override fun retryNow() = true
        fun finishTeardown() { pendingTeardown?.invoke(); pendingTeardown = null }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before fun listen() { CarPlayHostIntegration.onHostState = { states += it } }
    @After fun reset() { CarPlayHostIntegration.onHostState = null }

    @Test fun aHostWithoutControllerIsASessionUntilItsTeardownCompletes() {
        assertFalse(CarPlayHostIntegration.hasSession())
        val host = FakeHost()
        HostSessions.opened(host)
        assertTrue(CarPlayHostIntegration.hasSession())
        assertTrue(CarPlayHostIntegration.retryNow())

        var stopped = false
        CarPlayHostIntegration.stopSession { stopped = true }
        idle()
        assertTrue(host.ended)
        assertFalse(stopped)
        assertTrue(CarPlayHostIntegration.hasSession()) // teardown still running (F14)

        host.finishTeardown()
        idle()
        assertTrue(stopped)
        assertFalse(CarPlayHostIntegration.hasSession())
        assertEquals(listOf<CarPlayHostIntegration.HostState>(CarPlayHostIntegration.HostState.Closed), states)
    }

    @Test fun stopWithNothingRunningCompletesAtOnce() {
        var stopped = false
        CarPlayHostIntegration.stopSession { stopped = true }
        assertTrue(stopped)
        assertTrue(states.isEmpty())
    }

    @Test fun aWindowClosedByTheSystemEndsTheSession() {
        val host = FakeHost()
        HostSessions.opened(host)
        host.ended = true
        HostSessions.closed(host)
        assertFalse(CarPlayHostIntegration.hasSession())
        assertEquals(listOf<CarPlayHostIntegration.HostState>(CarPlayHostIntegration.HostState.Closed), states)
    }

    @Test fun stopRequestsDuringATeardownShareItsCompletion() {
        val host = FakeHost()
        HostSessions.opened(host)
        var first = false
        var second = false
        CarPlayHostIntegration.stopSession { first = true }
        CarPlayHostIntegration.stopSession { second = true }
        idle()
        host.finishTeardown()
        idle()
        assertTrue(first && second)
    }
}
