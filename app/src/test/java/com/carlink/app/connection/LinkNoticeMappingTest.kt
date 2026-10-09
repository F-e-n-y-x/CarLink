package com.carlink.app.connection

import com.carlink.app.ui.home.PhoneUsual
import com.carlink.app.ui.home.usualFor
import com.carlink.app.ui.setup.SetupItem
import com.carlink.app.ui.setup.SetupPermissions
import com.carlink.core.connection.CarPlayPreflight
import com.carlink.core.connection.ConnectionProfiles
import com.carlink.core.connection.DETAIL_PHONE_NOT_ANSWERING
import com.carlink.core.connection.FailureReason
import com.carlink.core.connection.Notice
import com.carlink.core.connection.PathHealth
import com.carlink.core.connection.PhoneHealth
import com.carlink.core.connection.ProgressStage
import com.carlink.core.connection.RecoveryAction
import com.carlink.core.connection.SessionRequest
import com.carlink.core.connection.SessionState
import com.carlink.core.connection.toLinkState
import com.carlink.core.core.Engine
import com.carlink.core.core.LinkNotice
import com.carlink.core.core.LinkNoticeKind
import com.carlink.core.core.LinkProblem
import com.carlink.core.core.LinkRecovery
import com.carlink.core.core.PhoneKind
import com.carlink.core.core.Transport
import com.carlink.core.detect.PairedPhone
import com.carlink.core.settings.WirelessPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [SessionState.Active.notice] reaches the screens as [LinkNotice]; the setup and Home helpers built on the session layer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LinkNoticeMappingTest {
    private val request = SessionRequest(Engine.CARPLAY, Transport.WIRELESS, "AA:BB:CC:00:00:01", "Sara’s iPhone")

    private fun active(phase: SessionState.Phase, notice: Notice? = null, path: WirelessPath? = WirelessPath.WIFI_DIRECT, attempt: Int = 1) =
        SessionState.Active(request, path, attempt, phase, notice)

    private val linking = SessionState.Phase.Progress(3, 4, ProgressStage.JOINING_WIFI)

    @Test fun onTimeHasNoNotice() {
        assertNull(active(linking).toLinkState().notice)
        assertNull(active(SessionState.Phase.Connected).toLinkState().notice)
    }

    @Test fun slowAndRecoveringBecomeTheirKinds() {
        assertEquals(LinkNotice(LinkNoticeKind.SLOW),
            active(linking, Notice(Notice.Kind.SLOW, ProgressStage.JOINING_WIFI, "Linking is taking longer")).toLinkState().notice)
        assertEquals(LinkNotice(LinkNoticeKind.RECOVERING, LinkRecovery.FRESH_WIFI_DIRECT),
            active(linking, Notice(Notice.Kind.RECOVERING, ProgressStage.JOINING_WIFI, "x", RecoveryAction.RESET_WIFI_DIRECT))
                .toLinkState().notice)
    }

    @Test fun nextPathNamesThePathNowTried() {
        val retry = SessionState.Phase.Retrying(2, FailureReason.PHONE_NEVER_REACHED_CAR, ProgressStage.JOINING_WIFI, "x")
        val state = active(retry, Notice(Notice.Kind.RECOVERING, ProgressStage.JOINING_WIFI, "x", RecoveryAction.NEXT_PATH),
            path = WirelessPath.CAR_HOTSPOT, attempt = 2)
        assertEquals(LinkRecovery.TRY_CAR_HOTSPOT, state.toLinkState().notice?.recovery)
        // A path-related failure that moved on without a stall notice reads the same way.
        assertEquals(LinkRecovery.TRY_CAR_HOTSPOT, active(retry, path = WirelessPath.CAR_HOTSPOT, attempt = 2).toLinkState().notice?.recovery)
    }

    @Test fun anEngineRetryCarriesItsReason() {
        val retry = SessionState.Phase.Retrying(1, FailureReason.BLUETOOTH, ProgressStage.HANDSHAKE, "x")
        assertEquals(LinkNotice(LinkNoticeKind.RETRYING, reason = LinkProblem.BLUETOOTH), active(retry).toLinkState().notice)
    }

    @Test fun aSilentPhoneIsNotAnswering() {
        val state = active(SessionState.Phase.Progress(1, 4, ProgressStage.PHONE_NOT_ANSWERING),
            Notice(Notice.Kind.PHONE_NOT_ANSWERING, ProgressStage.WAITING_FOR_PHONE, "x"))
        val link = state.toLinkState()
        assertEquals(LinkNoticeKind.NOT_ANSWERING, link.notice?.kind)
        assertEquals(DETAIL_PHONE_NOT_ANSWERING, link.detail)
    }

    @Test fun usualNeedsTwoSuccessesOnTheBestPath() {
        val phone = PairedPhone("aa:bb:cc:00:00:01", "Sara’s iPhone", PhoneKind.IPHONE, connected = false)
        fun health(successes: Int) = listOf(PhoneHealth(ConnectionProfiles.keyOf(request), Engine.CARPLAY, phone.name, successes,
            successes, 1f, 5_000, WirelessPath.WIFI_DIRECT.name,
            listOf(PathHealth(WirelessPath.WIFI_DIRECT.name, successes, successes, 5_000, true)), emptyList()))
        assertNull(usualFor(phone, health(1)))
        assertEquals(PhoneUsual(5_000, WirelessPath.WIFI_DIRECT), usualFor(phone, health(3)))
        assertNull(usualFor(phone.copy(kind = PhoneKind.UNKNOWN), health(3)))
    }

    @Test fun setupAsksForEverySessionPermission() {
        val runtime = SetupPermissions.runtimePermissions(37)
        val grouped = SetupPermissions.items(37, runtime).flatMap { it.second }
        assertTrue(runtime.all { it in grouped })
        assertTrue(CarPlayPreflight.ACCESS_LOCAL_NETWORK in runtime)
        val items = SetupPermissions.items(37, runtime).map { it.first }
        assertTrue(items.containsAll(listOf(SetupItem.OVERLAY, SetupItem.BATTERY, SetupItem.VPN)))
        assertTrue(SetupItem.BATTERY.optional && SetupItem.VPN.optional && !SetupItem.OVERLAY.optional)
    }
}
