package com.carlink.core.connection.aa

import com.andrerinas.openheadunit.HostIntegration.EngineEvent
import com.carlink.core.connection.AdapterEvent
import com.carlink.core.connection.FailureReason
import com.carlink.core.connection.ProgressStage
import com.carlink.core.core.Transport
import com.carlink.core.connection.aa.AaProtocol as P
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Android Auto state/event → [AdapterEvent] mapping and the failure classification. */
class AaSessionTrackerTest {
    private var now = 1_000L
    private val limits = AaSessionTracker.Limits()

    private fun wireless() = AaSessionTracker(Transport.WIRELESS, limits).also { it.begin(now) }
    private fun usb() = AaSessionTracker(Transport.USB, limits).also { it.begin(now) }

    private fun AaSessionTracker.event(kind: EngineEvent, detail: String? = null) = onEvent(kind.name, detail, now)
    private fun AaSessionTracker.state(connection: String, firstFrame: Boolean = false, running: Boolean = true) =
        onState(connection, null, running, firstFrame, now)
    private fun AaSessionTracker.after(millis: Long): List<AdapterEvent> { now += millis; return onTick(now) }

    private fun List<AdapterEvent>.failure() = single() as AdapterEvent.Failed

    @Test fun beginReportsWaitingForThePhone() {
        val t = AaSessionTracker(Transport.WIRELESS, limits)
        assertEquals(listOf(AdapterEvent.Progress(1, 4, ProgressStage.WAITING_FOR_PHONE)), t.begin(now))
        val u = AaSessionTracker(Transport.USB, limits)
        assertEquals(listOf(AdapterEvent.Progress(1, 3, ProgressStage.WAITING_FOR_PHONE)), u.begin(now))
    }

    @Test fun wirelessStagesAdvanceOnceEachAndConnectOnTheFirstFrame() {
        val t = wireless()
        assertEquals(listOf(AdapterEvent.Progress(2, 4, ProgressStage.HANDSHAKE)), t.event(EngineEvent.BT_CHANNEL_ACCEPTED))
        assertEquals(listOf(AdapterEvent.Progress(3, 4, ProgressStage.JOINING_WIFI)), t.event(EngineEvent.CREDENTIALS_SENT, "transport=WIFI_DIRECT"))
        assertEquals(emptyList<AdapterEvent>(), t.event(EngineEvent.PHONE_WIFI_STATUS, "type=6;status=0;stage=SETTLING"))
        assertEquals(listOf(AdapterEvent.Progress(4, 4, ProgressStage.STARTING_PROJECTION)), t.state(P.CONNECTION_CONNECTING))
        assertEquals(emptyList<AdapterEvent>(), t.state(P.CONNECTION_STARTING_TRANSPORT))
        assertEquals(emptyList<AdapterEvent>(), t.state(P.CONNECTION_TRANSPORT_STARTED))
        assertEquals(listOf(AdapterEvent.Connected), t.event(EngineEvent.FIRST_VIDEO_FRAME))
        assertEquals("connected once", emptyList<AdapterEvent>(), t.state(P.CONNECTION_TRANSPORT_STARTED, firstFrame = true))
    }

    @Test fun aStateReportWithAFrameAlsoConnects() {
        val t = wireless()
        t.state(P.CONNECTION_CONNECTING)
        assertEquals(listOf(AdapterEvent.Connected), t.state(P.CONNECTION_TRANSPORT_STARTED, firstFrame = true))
    }

    @Test fun usbStagesUseThreeSteps() {
        val t = usb()
        assertEquals(listOf(AdapterEvent.Progress(2, 3, ProgressStage.HANDSHAKE)), t.state(P.CONNECTION_CONNECTING))
        assertEquals(listOf(AdapterEvent.Progress(3, 3, ProgressStage.STARTING_PROJECTION)), t.state(P.CONNECTION_HANDSHAKE_COMPLETE))
    }

    // ---- failure classification ----

    @Test fun aRefusedNetworkRetriesOnceThenFailsAsRefusedWifi() {
        val t = wireless()
        t.event(EngineEvent.CREDENTIALS_SENT)
        assertEquals(listOf(AdapterEvent.Retrying(1, FailureReason.PHONE_REFUSED_WIFI)),
            t.event(EngineEvent.PHONE_WIFI_STATUS, "type=6;status=-11;stage=SETTLING"))
        val f = t.event(EngineEvent.PHONE_WIFI_STATUS, "type=7;status=-3;stage=SETTLING").failure()
        assertEquals(FailureReason.PHONE_REFUSED_WIFI, f.reason)
        assertTrue(f.reason.pathRelated)
        assertTrue(t.terminal)
    }

    @Test fun aRejectedStartResponseIsARefusal() {
        val t = wireless()
        assertEquals(listOf(AdapterEvent.Retrying(1, FailureReason.PHONE_REFUSED_WIFI)),
            t.event(EngineEvent.PHONE_WIFI_STATUS, "type=7;status=-1;stage=AWAIT_INFO_REQUEST"))
    }

    @Test fun noAcceptHintsAt45sAndFailsAsDidNotAnswer() {
        val t = wireless()
        assertEquals(emptyList<AdapterEvent>(), t.after(44_000))
        assertEquals(listOf(AdapterEvent.Retrying(1, FailureReason.PHONE_DID_NOT_ANSWER)), t.after(1_000))
        assertEquals(emptyList<AdapterEvent>(), t.after(30_000))
        assertEquals(FailureReason.PHONE_DID_NOT_ANSWER, t.after(45_000).failure().reason)
    }

    @Test fun anAcceptStopsTheAnswerTimer() {
        val t = wireless()
        t.event(EngineEvent.BT_CHANNEL_ACCEPTED)
        assertEquals(emptyList<AdapterEvent>(), t.after(200_000))
        assertFalse(t.terminal)
    }

    @Test fun pokesAnsweredWithoutAcceptMapToDidNotAnswer() {
        val t = wireless()
        assertEquals(listOf(AdapterEvent.Retrying(1, FailureReason.PHONE_DID_NOT_ANSWER)), t.event(EngineEvent.POKE_ANSWERED_NO_ACCEPT, "pokes=3"))
        assertEquals(FailureReason.PHONE_DID_NOT_ANSWER, t.event(EngineEvent.POKE_ANSWERED_NO_ACCEPT, "pokes=6").failure().reason)
    }

    @Test fun settleTimeoutsAreRefusalsUnlessThePhoneJoined() {
        val refused = wireless()
        refused.event(EngineEvent.SETTLE_TIMEOUT, "seconds=45")
        assertEquals(FailureReason.PHONE_REFUSED_WIFI, refused.event(EngineEvent.SETTLE_TIMEOUT, "seconds=45").failure().reason)

        val joined = wireless()
        joined.event(EngineEvent.PHONE_WIFI_STATUS, "type=6;status=0;stage=SETTLING")
        assertEquals(listOf(AdapterEvent.Retrying(1, FailureReason.PHONE_NEVER_REACHED_CAR)), joined.event(EngineEvent.SETTLE_TIMEOUT, "seconds=60"))
    }

    @Test fun starvationAndReadTimeoutsAreAWeakLink() {
        val starved = wireless()
        assertEquals(FailureReason.LINK_TOO_WEAK, starved.event(EngineEvent.STARVATION_ADVICE, "sessions=3").failure().reason)

        val timedOut = wireless()
        timedOut.state(P.CONNECTION_TRANSPORT_STARTED, firstFrame = true)
        timedOut.event(EngineEvent.LINK_READ_TIMEOUT, "millis=15000")
        assertEquals(listOf(AdapterEvent.Retrying(1, FailureReason.LINK_TOO_WEAK)),
            timedOut.event(EngineEvent.SESSION_ENDED, "userExit=false;clean=false"))
    }

    @Test fun aSessionWithoutVideoThatDropsIsAWeakLink() {
        val t = wireless()
        t.state(P.CONNECTION_HANDSHAKE_COMPLETE)
        assertEquals(listOf(AdapterEvent.Retrying(1, FailureReason.LINK_TOO_WEAK)),
            t.event(EngineEvent.SESSION_ENDED, "userExit=false;clean=false"))
    }

    @Test fun repeatedLossesFail() {
        val t = wireless()
        repeat(2) {
            t.state(P.CONNECTION_TRANSPORT_STARTED, firstFrame = true)
            assertTrue(t.event(EngineEvent.SESSION_ENDED, "userExit=false;clean=false").single() is AdapterEvent.Retrying)
        }
        t.state(P.CONNECTION_TRANSPORT_STARTED, firstFrame = true)
        assertTrue(t.event(EngineEvent.SESSION_ENDED, "userExit=false;clean=false").single() is AdapterEvent.Failed)
    }

    @Test fun wifiOffAndLocationOffAreBlocked() {
        assertEquals(FailureReason.BLOCKED, wireless().event(EngineEvent.WIFI_OFF).failure().reason)
        assertEquals(FailureReason.BLOCKED, wireless().event(EngineEvent.BSSID_UNRESOLVED, "location=off").failure().reason)
        assertEquals(FailureReason.BLOCKED, wireless().event(EngineEvent.BLUETOOTH_UNAVAILABLE, "cause=off").failure().reason)
    }

    @Test fun p2pOwnedElsewhereIsAWifiSetupFailureAnotherPathCanAvoid() {
        val f = wireless().event(EngineEvent.P2P_OWNED_BY_OTHER).failure()
        assertEquals(FailureReason.WIFI_SETUP_FAILED, f.reason)
        assertTrue(f.reason.pathRelated)
    }

    @Test fun anUnreadableAddressWithLocationOnRetriesThenFailsAsWifiSetup() {
        val t = wireless()
        assertEquals(listOf(AdapterEvent.Retrying(1, FailureReason.WIFI_SETUP_FAILED)), t.event(EngineEvent.BSSID_UNRESOLVED, "location=on"))
        assertEquals(FailureReason.WIFI_SETUP_FAILED, t.event(EngineEvent.BSSID_UNRESOLVED, "location=on").failure().reason)
    }

    @Test fun bluetoothDeadEndsAreBluetoothFailures() {
        assertEquals(FailureReason.BLUETOOTH, wireless().event(EngineEvent.HANDSHAKE_BACKOFF, "failures=5").failure().reason)
        assertEquals(FailureReason.BLUETOOTH, wireless().event(EngineEvent.EXTERNAL_BLUETOOTH, "evidence=/dev/rf_serial").failure().reason)
    }

    @Test fun aDfsChannelIsNamedInTheLaterFailure() {
        val t = wireless()
        t.event(EngineEvent.DFS_CHANNEL, "frequency=5500")
        assertTrue(t.event(EngineEvent.WIFI_OFF).failure().detail!!.contains("5500 MHz"))
    }

    @Test fun usbFailures() {
        assertEquals(FailureReason.USB, usb().event(EngineEvent.USB_PERMISSION_DENIED, "device=x").failure().reason)
        assertEquals(FailureReason.USB, usb().onEvent(P.KIND_AOA_SWITCH_FAILED, null, now).failure().reason)
        assertEquals(FailureReason.PHONE_DID_NOT_ANSWER, usb().after(60_000).failure().reason)
    }

    @Test fun usbSessionsIgnoreTheBackgroundWirelessEngine() {
        val t = usb()
        for (kind in listOf(EngineEvent.WIFI_OFF, EngineEvent.P2P_OWNED_BY_OTHER, EngineEvent.POKE_ANSWERED_NO_ACCEPT,
                EngineEvent.HANDSHAKE_BACKOFF, EngineEvent.BLUETOOTH_UNAVAILABLE)) {
            assertEquals(kind.name, emptyList<AdapterEvent>(), t.event(kind))
        }
        assertFalse(t.terminal)
        assertEquals(emptyList<AdapterEvent>(), wireless().event(EngineEvent.USB_PERMISSION_DENIED))
    }

    @Test fun startRefusedAndProcessDeathAreEngineFailures() {
        assertEquals(FailureReason.ENGINE, wireless().onEvent(P.KIND_START_REFUSED, null, now).failure().reason)
        assertEquals(FailureReason.ENGINE, wireless().onProcessDied().failure().reason)
    }

    // ---- session end ----

    @Test fun userExitAndPhoneGoodbyeEndTheSession() {
        val exit = wireless()
        exit.state(P.CONNECTION_TRANSPORT_STARTED, firstFrame = true)
        assertEquals(listOf(AdapterEvent.Ended(byUser = true)), exit.event(EngineEvent.SESSION_ENDED, "userExit=true;clean=false"))

        val bye = wireless()
        bye.state(P.CONNECTION_TRANSPORT_STARTED, firstFrame = true)
        assertEquals(listOf(AdapterEvent.Ended(byUser = false)), bye.event(EngineEvent.SESSION_ENDED, "userExit=false;clean=true"))
    }

    @Test fun aDisconnectWithoutAnEndEventIsALossAfterTheDebounce() {
        val t = wireless()
        t.state(P.CONNECTION_TRANSPORT_STARTED, firstFrame = true)
        assertEquals(emptyList<AdapterEvent>(), t.state(P.CONNECTION_DISCONNECTED))
        assertEquals(emptyList<AdapterEvent>(), t.after(1_000))
        assertEquals(listOf(AdapterEvent.Retrying(1, FailureReason.ENGINE)), t.after(1_000))
    }

    @Test fun anEndEventAfterTheDisconnectStateDecides() {
        val t = wireless()
        t.state(P.CONNECTION_TRANSPORT_STARTED, firstFrame = true)
        t.state(P.CONNECTION_DISCONNECTED)
        assertEquals(listOf(AdapterEvent.Ended(byUser = true)), t.event(EngineEvent.SESSION_ENDED, "userExit=true;clean=false"))
        assertEquals("no second end", emptyList<AdapterEvent>(), t.after(5_000))
    }

    @Test fun anEndEventWithoutASessionIsIgnored() {
        val t = wireless()
        assertEquals(emptyList<AdapterEvent>(), t.event(EngineEvent.SESSION_ENDED, "userExit=false;clean=false"))
    }

    @Test fun theServiceStoppingUnderTheSessionFailsAfterTheStartGrace() {
        val t = wireless()
        assertEquals("starting", emptyList<AdapterEvent>(), t.state(P.CONNECTION_DISCONNECTED, running = false))
        t.state(P.CONNECTION_DISCONNECTED, running = true)
        assertEquals(FailureReason.ENGINE, t.state(P.CONNECTION_DISCONNECTED, running = false).failure().reason)
    }

    @Test fun nothingIsReportedAfterTheEnd() {
        val t = wireless()
        t.event(EngineEvent.WIFI_OFF)
        assertEquals(emptyList<AdapterEvent>(), t.event(EngineEvent.BT_CHANNEL_ACCEPTED))
        assertEquals(emptyList<AdapterEvent>(), t.after(500_000))
    }

    @Test fun repokesWhileWaitingAndShortlyAfterALoss() {
        val t = wireless()
        assertFalse(t.shouldRepoke(now + 44_000))
        assertTrue(t.shouldRepoke(now + 45_000))
        assertFalse("once per interval", t.shouldRepoke(now + 46_000))
        t.state(P.CONNECTION_TRANSPORT_STARTED, firstFrame = true)
        assertFalse(t.shouldRepoke(now + 200_000))
        t.event(EngineEvent.SESSION_ENDED, "userExit=false;clean=false")
        assertTrue(t.shouldRepoke(now + 3_000))
        assertFalse(usb().shouldRepoke(now + 100_000))
    }

    // ---- RELIABILITY_AUDIT K2: a stale USB accessory ----

    @Test fun aUsbSessionThatEndsBeforeAnyFrameIsReswitchedAtOnce() {
        val t = usb()
        t.state(P.CONNECTION_CONNECTING)
        // Handshake failed 1.4 s after the open ("Drained 549115 bytes of stale USB data").
        now += 1_400
        assertEquals(emptyList<AdapterEvent>(), t.onState(P.CONNECTION_ERROR, "Handshake failed", true, false, now))
        val retry = t.after(limits.noVideoEndDebounceMillis).single() as AdapterEvent.Retrying
        assertEquals(FailureReason.USB, retry.reason)
        assertTrue(retry.detail!!.contains("earlier Android Auto session"))
        assertTrue("the adapter sends the switch again", t.takeReswitchRequest())
        assertFalse("once", t.takeReswitchRequest())
        // The phone re-enumerates and the engine connects the fresh accessory.
        now += 2_000
        assertEquals(listOf(AdapterEvent.Progress(2, 3, ProgressStage.HANDSHAKE)), t.state(P.CONNECTION_CONNECTING))
        assertEquals(emptyList<AdapterEvent>(), t.after(10_000))
        assertEquals(listOf(AdapterEvent.Connected), t.state(P.CONNECTION_TRANSPORT_STARTED, firstFrame = true).filterIsInstance<AdapterEvent.Connected>())
    }

    @Test fun aPhoneThatIgnoresTheReswitchNeedsAReplugWithinFiveSeconds() {
        val t = usb()
        t.state(P.CONNECTION_CONNECTING)
        t.state(P.CONNECTION_DISCONNECTED)
        t.after(limits.noVideoEndDebounceMillis)
        assertEquals(emptyList<AdapterEvent>(), t.after(limits.staleReplugMillis - 1_000))
        val failed = t.after(1_000).failure()
        assertEquals(FailureReason.USB, failed.reason)
        assertTrue(failed.detail!!.startsWith("unplug the phone and plug it in again"))
    }

    @Test fun aSecondFailedHandshakeAsksForAReplugAtOnce() {
        val t = usb()
        t.state(P.CONNECTION_CONNECTING); t.state(P.CONNECTION_DISCONNECTED)
        t.after(limits.noVideoEndDebounceMillis)
        t.takeReswitchRequest()
        t.state(P.CONNECTION_CONNECTING); t.state(P.CONNECTION_DISCONNECTED)
        assertTrue(t.after(limits.noVideoEndDebounceMillis).failure().detail!!.startsWith("unplug"))
        assertFalse(t.takeReswitchRequest())
    }

    @Test fun anErrorStateAloneStillCountsAsAFailedUsbHandshake() {
        val t = usb()
        // The Connecting report was conflated away; only the error arrived.
        t.onState(P.CONNECTION_ERROR, "Handshake failed with exception", true, false, now)
        assertTrue(t.after(limits.noVideoEndDebounceMillis).single() is AdapterEvent.Retrying)
        assertTrue(t.takeReswitchRequest())
    }

    @Test fun aConnectedUsbSessionThatDropsIsStillALoss() {
        val t = usb()
        t.state(P.CONNECTION_TRANSPORT_STARTED, firstFrame = true)
        t.state(P.CONNECTION_DISCONNECTED)
        assertEquals(emptyList<AdapterEvent>(), t.after(limits.noVideoEndDebounceMillis))
        assertEquals(listOf(AdapterEvent.Retrying(1, FailureReason.USB)), t.after(limits.endDebounceMillis))
        assertFalse(t.takeReswitchRequest())
    }
}
