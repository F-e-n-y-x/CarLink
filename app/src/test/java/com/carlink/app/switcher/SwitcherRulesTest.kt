package com.carlink.app.switcher

import com.carlink.app.connection.FailureReason
import com.carlink.app.connection.ProgressStage
import com.carlink.app.connection.SessionRequest
import com.carlink.app.connection.SessionState
import com.carlink.app.connection.SessionState.Phase
import com.carlink.app.core.Engine
import com.carlink.app.core.PhoneKind
import com.carlink.app.core.Transport
import com.carlink.app.detect.PairedPhone
import com.carlink.app.settings.WirelessPath
import com.carlink.app.ui.switcher.switchCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The floating switcher's show/hide rule: session state × projection in front × permission × setting. */
class SwitcherRulesTest {
    private val carPlay = SessionRequest(Engine.CARPLAY, Transport.WIRELESS, "AA:BB:CC:00:00:01", "Sara’s iPhone")
    private val androidAuto = SessionRequest(Engine.ANDROID_AUTO, Transport.USB, null, "Pixel 8")

    private fun connected(request: SessionRequest) = SessionState.Active(request, WirelessPath.WIFI_DIRECT, 1, Phase.Connected)

    private fun show(
        session: SessionState,
        front: Set<Engine> = setOf(session.engine ?: Engine.CARPLAY),
        carLinkScreen: Boolean = false,
        permission: Boolean = true,
        enabled: Boolean = true,
        prompt: Boolean = false,
    ) = SwitcherRules.shouldShow(session, front, carLinkScreen, permission, enabled, prompt)

    @Test
    fun showsOverEachEnginesOwnProjectionWhenConnected() {
        assertTrue(show(connected(carPlay), setOf(Engine.CARPLAY)))
        assertTrue(show(connected(androidAuto), setOf(Engine.ANDROID_AUTO)))
    }

    @Test
    fun hiddenWhileNotConnected() {
        val phases = listOf(
            Phase.Starting,
            Phase.Progress(2, 4, ProgressStage.HANDSHAKE),
            Phase.Retrying(2, FailureReason.LINK_TOO_WEAK),
        )
        phases.forEach { phase ->
            assertFalse(phase.toString(), show(SessionState.Active(carPlay, WirelessPath.WIFI_DIRECT, 1, phase)))
        }
        assertFalse(show(SessionState.Idle, setOf(Engine.CARPLAY, Engine.ANDROID_AUTO)))
        assertFalse(show(SessionState.Failed(carPlay, null, FailureReason.ENGINE, null), setOf(Engine.CARPLAY)))
        assertFalse(show(SessionState.Blocked(carPlay, emptyList()), setOf(Engine.CARPLAY)))
    }

    @Test
    fun hiddenWhenTheProjectionIsNotInFront() {
        assertFalse(show(connected(carPlay), emptySet()))
        // The other engine's activity in front (a stale report) is not this session's picture.
        assertFalse(show(connected(carPlay), setOf(Engine.ANDROID_AUTO)))
        assertFalse(show(connected(androidAuto), setOf(Engine.CARPLAY)))
    }

    @Test
    fun carLinksOwnScreenWins() {
        assertFalse(show(connected(carPlay), setOf(Engine.CARPLAY), carLinkScreen = true))
    }

    @Test
    fun needsPermissionAndSetting() {
        assertFalse(show(connected(carPlay), permission = false))
        assertFalse(show(connected(carPlay), enabled = false))
        assertFalse(show(connected(carPlay), permission = false, enabled = false))
    }

    @Test
    fun stepsAsideForTheSwitchPrompt() {
        assertFalse(show(connected(carPlay), prompt = true))
    }

    @Test
    fun everyCombination() {
        for (connectedSession in listOf(true, false)) for (inFront in listOf(true, false))
            for (permission in listOf(true, false)) for (enabled in listOf(true, false)) {
                val session = if (connectedSession) connected(carPlay)
                else SessionState.Active(carPlay, null, 1, Phase.Starting)
                val expected = connectedSession && inFront && permission && enabled
                assertEquals("$connectedSession $inFront $permission $enabled", expected,
                    show(session, if (inFront) setOf(Engine.CARPLAY) else emptySet(), permission = permission, enabled = enabled))
            }
    }

    @Test
    fun switchPromptShowsWhileASwitchIsOfferedAndPermitted() {
        assertTrue(SwitcherRules.shouldShowSwitchPrompt(androidAuto, canDrawOverlays = true))
        assertFalse(SwitcherRules.shouldShowSwitchPrompt(androidAuto, canDrawOverlays = false))
        assertFalse(SwitcherRules.shouldShowSwitchPrompt(null, canDrawOverlays = true))
        assertEquals(30_000L, SwitcherRules.SWITCH_PROMPT_TIMEOUT_MS)
    }

    @Test
    fun switchCandidatesSkipTheProjectingPhoneAndUnknownKinds() {
        val iphone = PairedPhone("AA:BB:CC:00:00:01", "Sara’s iPhone", PhoneKind.IPHONE, connected = true)
        val pixel = PairedPhone("AA:BB:CC:00:00:02", "Pixel 8", PhoneKind.ANDROID, connected = false)
        val buds = PairedPhone("AA:BB:CC:00:00:03", "Buds", PhoneKind.UNKNOWN, connected = false)
        val all = listOf(iphone, pixel, buds)
        assertEquals(listOf(pixel), switchCandidates(all, "aa:bb:cc:00:00:01", iphone.name))
        // USB sessions have no address: the name identifies the projecting phone.
        assertEquals(listOf(iphone), switchCandidates(all, null, "Pixel 8"))
        assertEquals(listOf(iphone, pixel), switchCandidates(all, null, null))
    }
}
