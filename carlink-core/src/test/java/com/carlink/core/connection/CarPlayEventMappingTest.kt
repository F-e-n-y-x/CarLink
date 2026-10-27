package com.carlink.core.connection

import com.carlink.core.core.Transport
import com.shilapi.xcertplay.CarPlayHostIntegration.HostState
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.transport.BluetoothLinkCheck
import com.shilapi.xcertplay.transport.BluetoothRadioCheck
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import com.shilapi.xcertplay.orchestration.WirelessStartupTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Status → event mapping and failure classification (docs/dissection/DIPLAY.md F1, F10, F13, F15). */
class CarPlayEventMappingTest {
    private val hotspot = CarPlayStatus.HotspotReady("DIRECT-ab", "5GHz", 36, "02:00:00:00:00:01", "192.168.49.1", "p2p")

    private val wirelessStages: Map<CarPlayStatus, ProgressStage?> = mapOf(
        CarPlayStatus.DiscoveringMfi to ProgressStage.WAITING_FOR_PHONE,
        CarPlayStatus.WaitingForMfi to ProgressStage.WAITING_FOR_PHONE,
        CarPlayStatus.RequestingMfiPermission to ProgressStage.WAITING_FOR_PHONE,
        CarPlayStatus.MfiReady to ProgressStage.WAITING_FOR_PHONE,
        CarPlayStatus.StartingHotspot to ProgressStage.WAITING_FOR_PHONE,
        hotspot to ProgressStage.WAITING_FOR_PHONE,
        CarPlayStatus.WaitingForPairedIphone to ProgressStage.WAITING_FOR_PHONE,
        CarPlayStatus.AttachingNetwork to ProgressStage.WAITING_FOR_PHONE,
        CarPlayStatus.ConnectingBluetooth to ProgressStage.WAITING_FOR_PHONE, // dialling is not an answer
        CarPlayStatus.RunningWireless to ProgressStage.HANDSHAKE, // the iPhone answered over Bluetooth
        CarPlayStatus.WirelessActive to ProgressStage.STARTING_PROJECTION,
        CarPlayStatus.WirelessActiveFallback to ProgressStage.STARTING_PROJECTION,
        CarPlayStatus.ControlEnded to null,
        CarPlayStatus.Failed("x") to null,
    )

    private val usbStages: Map<CarPlayStatus, ProgressStage?> = mapOf(
        CarPlayStatus.DiscoveringIphone to ProgressStage.WAITING_FOR_PHONE,
        CarPlayStatus.WaitingForIphone to ProgressStage.WAITING_FOR_PHONE,
        CarPlayStatus.IphoneNotListed(nonHubDeviceListed = false) to ProgressStage.WAITING_FOR_PHONE,
        CarPlayStatus.RequestingIphonePermission to ProgressStage.WAITING_FOR_PHONE,
        CarPlayStatus.SelectingConfiguration to ProgressStage.HANDSHAKE,
        CarPlayStatus.WaitingForReenumeration to ProgressStage.HANDSHAKE,
        CarPlayStatus.OpeningDataPaths to ProgressStage.HANDSHAKE,
        CarPlayStatus.Pairing to ProgressStage.HANDSHAKE,
        CarPlayStatus.ConnectingControl to ProgressStage.HANDSHAKE,
        CarPlayStatus.AttachingNetwork to ProgressStage.STARTING_PROJECTION,
        CarPlayStatus.RunningControl to ProgressStage.STARTING_PROJECTION,
    )

    @Test fun everyStatusHasAStage() {
        wirelessStages.forEach { (status, stage) ->
            assertEquals(status.toString(), stage, CarPlayEventMapping.stageOf(status, Transport.WIRELESS))
        }
        usbStages.forEach { (status, stage) ->
            assertEquals(status.toString(), stage, CarPlayEventMapping.stageOf(status, Transport.USB))
        }
        // Together the tables name all 24 CarPlayStatus kinds (CarPlayController.kt:105-136).
        assertEquals(24, (wirelessStages.keys + usbStages.keys).map { it.javaClass }.toSet().size)
    }

    @Test fun progressStepsFollowTheTransport() {
        val wireless = CarPlaySessionTracker(Transport.WIRELESS)
        assertEquals(AdapterEvent.Progress(1, 4, ProgressStage.WAITING_FOR_PHONE), wireless.started())
        assertEquals(emptyList<AdapterEvent>(), wireless.onStatus(CarPlayStatus.StartingHotspot)) // same stage
        // Dialling the iPhone over Bluetooth is still waiting: only its answer moves the bar.
        assertEquals(emptyList<AdapterEvent>(), wireless.onStatus(CarPlayStatus.ConnectingBluetooth))
        assertEquals(listOf(AdapterEvent.Progress(2, 4, ProgressStage.HANDSHAKE)), wireless.onStatus(CarPlayStatus.RunningWireless))
        assertEquals(listOf(AdapterEvent.Progress(4, 4, ProgressStage.STARTING_PROJECTION)), wireless.onStatus(CarPlayStatus.WirelessActive))

        val usb = CarPlaySessionTracker(Transport.USB)
        assertEquals(AdapterEvent.Progress(1, 3, ProgressStage.WAITING_FOR_PHONE), usb.started())
        assertEquals(listOf(AdapterEvent.Progress(2, 3, ProgressStage.HANDSHAKE)), usb.onStatus(CarPlayStatus.Pairing))
        assertEquals(listOf(AdapterEvent.Progress(3, 3, ProgressStage.STARTING_PROJECTION)), usb.onStatus(CarPlayStatus.RunningControl))
    }

    @Test fun nullStatusIsNeverTheEndOfASession() {
        val tracker = CarPlaySessionTracker(Transport.WIRELESS)
        tracker.started()
        assertEquals(emptyList<AdapterEvent>(), tracker.onStatus(null))
        assertFalse(tracker.finished)
        assertEquals(listOf(AdapterEvent.Ended(byUser = true)), tracker.onHostState(HostState.Closed))
        assertTrue(tracker.finished)
    }

    @Test fun connectedOnlyAtTheFirstFrame() {
        val tracker = CarPlaySessionTracker(Transport.USB)
        tracker.started()
        tracker.onStatus(CarPlayStatus.RunningControl)
        assertEquals(listOf(AdapterEvent.Connected), tracker.onHostState(HostState.FirstFrame))
        // Statuses during projection add no progress.
        assertEquals(emptyList<AdapterEvent>(), tracker.onStatus(CarPlayStatus.RunningControl))
    }

    // --- F1: FIRST_TCP_TIMEOUT -----------------------------------------------------------------

    private val firstTcp = CarPlayStatus.Failed("No AirPlay TCP after CarPlay StartSession",
        startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT)

    @Test fun firstTcpTimeoutIsRetryingUntilDiPlayStops() {
        val tracker = CarPlaySessionTracker(Transport.WIRELESS)
        tracker.started()
        tracker.onStatus(CarPlayStatus.RunningWireless)
        for (attempt in 1..5) {
            val events = tracker.onHostState(HostState.Retrying(attempt, 5, 2_000L, firstTcp, "first tcp"))
            assertEquals(listOf(AdapterEvent.Retrying(attempt, FailureReason.PHONE_NEVER_REACHED_CAR)), events)
            assertEquals(emptyList<AdapterEvent>(), tracker.onStatus(firstTcp)) // the bus copy adds nothing
            tracker.onStatus(CarPlayStatus.RunningWireless)
        }
        val stopped = tracker.onHostState(HostState.RetriesStopped(firstTcp, WirelessStartupFailure.FIRST_TCP_TIMEOUT, 5, exhausted = true))
        assertEquals(listOf(AdapterEvent.Failed(FailureReason.PHONE_NEVER_REACHED_CAR, firstTcp.message)), stopped)
        assertTrue(FailureReason.PHONE_NEVER_REACHED_CAR.pathRelated)
        assertTrue(tracker.finished)
        assertEquals(WirelessStartupFailure.FIRST_TCP_TIMEOUT, tracker.lastFailure?.startupFailure)
    }

    @Test fun bluetoothStageFailuresAreNeverPathRelated() {
        val rfcomm = CarPlayStatus.Failed("read failed, socket might closed or timeout, read ret: -1")
        val tracker = CarPlaySessionTracker(Transport.WIRELESS)
        tracker.started()
        tracker.onStatus(CarPlayStatus.ConnectingBluetooth)
        val events = tracker.onHostState(HostState.Retrying(1, null, 2_000L, rfcomm, "rfcomm"))
        assertEquals(listOf(AdapterEvent.Retrying(1, FailureReason.BLUETOOTH, "the iPhone did not answer over Bluetooth")), events)
        assertFalse(FailureReason.BLUETOOTH.pathRelated)
        // An iAP2 bootstrap failure over RFCOMM is the Bluetooth stage as well.
        assertEquals(FailureReason.BLUETOOTH,
            CarPlayEventMapping.classify(CarPlayStatus.Failed("iAP2 link closed"), CarPlayStatus.RunningWireless, Transport.WIRELESS).reason)
    }

    @Test fun unboundedRetriesEndAfterTheBudget() {
        val rfcomm = CarPlayStatus.Failed("Bluetooth connect timed out")
        val tracker = CarPlaySessionTracker(Transport.WIRELESS, maxUnboundedRetries = 2, maxDialFailures = 10)
        val missed = "the iPhone did not answer over Bluetooth"
        tracker.started()
        tracker.onStatus(CarPlayStatus.ConnectingBluetooth)
        assertEquals(listOf(AdapterEvent.Retrying(1, FailureReason.BLUETOOTH, missed)), tracker.onHostState(HostState.Retrying(1, null, 2_000L, rfcomm, "r")))
        tracker.onStatus(CarPlayStatus.ConnectingBluetooth)
        assertEquals(listOf(AdapterEvent.Retrying(2, FailureReason.BLUETOOTH, missed)), tracker.onHostState(HostState.Retrying(2, null, 4_000L, rfcomm, "r")))
        tracker.onStatus(CarPlayStatus.ConnectingBluetooth)
        assertEquals(listOf(AdapterEvent.Failed(FailureReason.BLUETOOTH, rfcomm.message)),
            tracker.onHostState(HostState.Retrying(3, null, 8_000L, rfcomm, "r")))
    }

    @Test fun aPhoneThatLeavesAfterProjectingEndsTheSession() {
        val rfcomm = CarPlayStatus.Failed("Bluetooth connect timed out")
        val tracker = CarPlaySessionTracker(Transport.WIRELESS, maxUnboundedRetries = 1)
        tracker.started()
        tracker.onHostState(HostState.FirstFrame)
        assertEquals(listOf(AdapterEvent.Retrying(1, FailureReason.LINK_TOO_WEAK)),
            tracker.onHostState(HostState.Retrying(1, null, 2_000L, null, "AirPlay session ended")))
        tracker.onStatus(CarPlayStatus.ConnectingBluetooth)
        assertEquals(listOf(AdapterEvent.Ended(byUser = false)), tracker.onHostState(HostState.Retrying(2, null, 4_000L, rfcomm, "r")))
    }

    @Test fun hardFailuresAreReportedAtOnce() {
        val cases = listOf(
            Triple(CarPlayStatus.Failed("Bluetooth is not enabled"), CarPlayStatus.WaitingForPairedIphone, FailureReason.BLUETOOTH),
            // DiPlay 0.2.19 (BluetoothRadioCheck.RADIO_OFF), thrown before the Wi-Fi is changed.
            Triple(CarPlayStatus.Failed(BluetoothRadioCheck.RADIO_OFF), CarPlayStatus.MfiReady, FailureReason.BLUETOOTH),
            Triple(CarPlayStatus.Failed("The selected iPhone is no longer paired. Choose it again in DiPlay."),
                CarPlayStatus.WaitingForPairedIphone, FailureReason.BLUETOOTH),
            Triple(CarPlayStatus.Failed("iPhone USB permission was denied"), CarPlayStatus.RequestingIphonePermission, FailureReason.USB),
            Triple(CarPlayStatus.Failed("CH341 USB permission was denied"), CarPlayStatus.RequestingMfiPermission, FailureReason.AUTHENTICATION),
            Triple(CarPlayStatus.Failed("Could not establish EXISTING_WIFI hotspot: Multiple Wi-Fi networks are active; keep one existing Wi-Fi connection"),
                CarPlayStatus.StartingHotspot, FailureReason.WIFI_SETUP_FAILED),
            Triple(CarPlayStatus.Failed("Could not establish EXISTING_WIFI hotspot: Configured Wi-Fi does not match the connected network"),
                CarPlayStatus.StartingHotspot, FailureReason.WIFI_SETUP_FAILED),
        )
        for ((failure, stage, reason) in cases) {
            val tracker = CarPlaySessionTracker(Transport.WIRELESS)
            tracker.started()
            tracker.onStatus(stage)
            assertEquals(failure.message, listOf(AdapterEvent.Failed(reason, failure.message)),
                tracker.onHostState(HostState.Retrying(1, null, 2_000L, failure, "r")))
        }
    }

    @Test fun wifiResetRequiredIsTerminalWithoutAHostState() {
        val tracker = CarPlaySessionTracker(Transport.WIRELESS)
        tracker.started()
        tracker.onStatus(CarPlayStatus.StartingHotspot)
        val reset = CarPlayStatus.Failed("Wi-Fi Direct needs a reset", wifiResetRequired = true)
        assertEquals(listOf(AdapterEvent.Failed(FailureReason.WIFI_SETUP_FAILED, reset.message)), tracker.onStatus(reset))
        assertTrue(tracker.lastFailure!!.wifiResetRequired)
    }

    @Test fun hotspotConfigurationStopsAtOnce() {
        val off = CarPlayStatus.Failed("The car hotspot is off. Turn it on in the car settings and connect again.",
            startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION)
        val tracker = CarPlaySessionTracker(Transport.WIRELESS)
        tracker.started()
        tracker.onStatus(CarPlayStatus.StartingHotspot)
        assertEquals(listOf(AdapterEvent.Failed(FailureReason.WIFI_SETUP_FAILED, off.message)),
            tracker.onHostState(HostState.RetriesStopped(off, WirelessStartupFailure.HOTSPOT_CONFIGURATION, 0, exhausted = false)))
    }

    @Test fun namedCausesNoRetryCanChangeStopAtOnce() {
        fun classify(cause: ConnectionFailureCause, startup: WirelessStartupFailure? = null) = CarPlayEventMapping.classify(
            CarPlayStatus.Failed("boom", startupFailure = startup, knownCause = cause), CarPlayStatus.ConnectingBluetooth, Transport.WIRELESS)
        assertEquals(FailureReason.BLUETOOTH, classify(ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED).reason)
        assertTrue(classify(ConnectionFailureCause.IPHONE_NOT_CHOSEN).hard)
        val noInterface = classify(ConnectionFailureCause.HOTSPOT_INTERFACE_NOT_FOUND, WirelessStartupFailure.HOTSPOT_NOT_READY)
        assertEquals(FailureReason.WIFI_SETUP_FAILED, noInterface.reason)
        assertTrue(noInterface.hard)
        assertFalse(classify(ConnectionFailureCause.CAR_HOTSPOT_START_TIMED_OUT, WirelessStartupFailure.HOTSPOT_NOT_READY).hard)
    }

    @Test fun wifiDirectRefusedBesideTheCarHotspotStopsThatPath() {
        val refused = CarPlayStatus.Failed("Wi-Fi Direct refused beside the car hotspot",
            startupFailure = WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT)
        val failure = CarPlayEventMapping.classify(refused, CarPlayStatus.StartingHotspot, Transport.WIRELESS)
        assertEquals(FailureReason.WIFI_SETUP_FAILED, failure.reason)
        assertTrue(failure.hard)
        assertTrue(failure.reason.pathRelated)
    }

    @Test fun unusableHeadUnitBluetoothStopsAtOnce() {
        // DiPlay 0.2.19: no adapter, no socket manager, or links that reach no phone; never retried.
        val unusable = CarPlayStatus.Failed(
            "Android Bluetooth on this head unit ${BluetoothLinkCheck.NO_SOCKETS_MARK}: its Bluetooth service has no socket manager",
            startupFailure = WirelessStartupFailure.BLUETOOTH_UNUSABLE)
        val tracker = CarPlaySessionTracker(Transport.WIRELESS)
        tracker.started()
        tracker.onStatus(CarPlayStatus.ConnectingBluetooth)
        assertEquals(listOf(AdapterEvent.Failed(FailureReason.BLUETOOTH, unusable.message)),
            tracker.onHostState(HostState.RetriesStopped(unusable, WirelessStartupFailure.BLUETOOTH_UNUSABLE, 0, exhausted = false)))
        assertTrue(tracker.lastFailure!!.hard)
        assertEquals(WirelessStartupFailure.BLUETOOTH_UNUSABLE, tracker.lastFailure?.startupFailure)
    }

    @Test fun unusableBluetoothOnAStereoWithAnExternalModuleIsTheExternalBluetoothProblem() {
        val unusable = CarPlayStatus.Failed("Android Bluetooth on this head unit connects without reaching a phone",
            startupFailure = WirelessStartupFailure.BLUETOOTH_UNUSABLE)
        val tracker = CarPlaySessionTracker(Transport.WIRELESS, externalBluetooth = true)
        tracker.started()
        tracker.onStatus(CarPlayStatus.ConnectingBluetooth)
        assertEquals(listOf(AdapterEvent.Failed(FailureReason.EXTERNAL_BLUETOOTH, unusable.message)),
            tracker.onHostState(HostState.RetriesStopped(unusable, WirelessStartupFailure.BLUETOOTH_UNUSABLE, 0, exhausted = false)))
        assertTrue(tracker.lastFailure!!.hard)
        assertEquals(com.carlink.core.core.LinkProblem.EXTERNAL_BLUETOOTH, FailureReason.EXTERNAL_BLUETOOTH.toLinkProblem())
        assertEquals(com.carlink.core.core.LinkProblem.PHONE_WIFI_OFF, FailureReason.PHONE_WIFI_OFF.toLinkProblem())
        assertTrue(!FailureReason.PHONE_WIFI_OFF.pathRelated) // no other Wi-Fi path helps an iPhone with Wi-Fi off
        // Without the probe's signals it stays the ordinary Bluetooth problem.
        assertEquals(FailureReason.BLUETOOTH, CarPlayEventMapping.classify(unusable, CarPlayStatus.ConnectingBluetooth, Transport.WIRELESS).reason)
        // Other Bluetooth failures on such a stereo keep their own meaning.
        assertEquals(FailureReason.BLUETOOTH, CarPlayEventMapping.classify(CarPlayStatus.Failed("Bluetooth is not enabled"),
            CarPlayStatus.WaitingForPairedIphone, Transport.WIRELESS, externalBluetooth = true).reason)
    }

    @Test fun unusableBluetoothIsNotAPathProblem() {
        // Bluetooth starts every wireless path, so trying the next Wi-Fi path cannot help.
        val failure = CarPlayEventMapping.classify(
            CarPlayStatus.Failed("no adapter", startupFailure = WirelessStartupFailure.BLUETOOTH_UNUSABLE),
            CarPlayStatus.MfiReady, Transport.WIRELESS)
        assertEquals(FailureReason.BLUETOOTH, failure.reason)
        assertTrue(failure.hard)
        assertFalse(failure.reason.pathRelated)
    }

    @Test fun hotspotNotReadyIsAWifiSetupRetry() {
        val notReady = CarPlayStatus.Failed("hotspot not ready", startupFailure = WirelessStartupFailure.HOTSPOT_NOT_READY)
        val tracker = CarPlaySessionTracker(Transport.WIRELESS)
        tracker.started()
        assertEquals(listOf(AdapterEvent.Retrying(1, FailureReason.WIFI_SETUP_FAILED)),
            tracker.onHostState(HostState.Retrying(1, 5, 2_000L, notReady, "r")))
    }

    @Test fun hostBlockingStatesAreBlockedOrEngine() {
        fun first(state: HostState) = CarPlaySessionTracker(Transport.USB).run { started(); onHostState(state).single() }
        assertEquals(FailureReason.BLOCKED, (first(HostState.VpnConsentDenied) as AdapterEvent.Failed).reason)
        assertEquals(FailureReason.BLOCKED, (first(HostState.PermissionsDenied(listOf("p"))) as AdapterEvent.Failed).reason)
        assertEquals(FailureReason.ENGINE, (first(HostState.ForegroundServiceFailed("fgs")) as AdapterEvent.Failed).reason)
        assertEquals(FailureReason.AUTHENTICATION, (first(HostState.AuthenticationUnavailable) as AdapterEvent.Failed).reason)
    }

    @Test fun controlEndedBeforeProjectionIsAFailure() {
        val tracker = CarPlaySessionTracker(Transport.WIRELESS)
        tracker.started()
        tracker.onStatus(CarPlayStatus.RunningWireless)
        val event = tracker.onStatus(CarPlayStatus.ControlEnded).single() as AdapterEvent.Failed
        assertEquals(FailureReason.PHONE_DID_NOT_ANSWER, event.reason)

        val connected = CarPlaySessionTracker(Transport.WIRELESS)
        connected.started()
        connected.onHostState(HostState.FirstFrame)
        assertEquals(listOf(AdapterEvent.Ended(byUser = false)), connected.onStatus(CarPlayStatus.ControlEnded))
    }

    @Test fun nothingFollowsATerminalEvent() {
        val tracker = CarPlaySessionTracker(Transport.WIRELESS)
        tracker.started()
        tracker.onHostState(HostState.VpnConsentDenied)
        assertEquals(emptyList<AdapterEvent>(), tracker.onHostState(HostState.FirstFrame))
        assertEquals(emptyList<AdapterEvent>(), tracker.onStatus(CarPlayStatus.WirelessActive))
        assertEquals(emptyList<AdapterEvent>(), tracker.onHostState(HostState.Closed))
    }

    @Test fun classificationByStage() {
        fun reason(stage: CarPlayStatus?, transport: Transport = Transport.WIRELESS, message: String = "boom") =
            CarPlayEventMapping.classify(CarPlayStatus.Failed(message), stage, transport).reason
        assertEquals(FailureReason.AUTHENTICATION, reason(CarPlayStatus.WaitingForMfi))
        assertEquals(FailureReason.WIFI_SETUP_FAILED, reason(CarPlayStatus.StartingHotspot))
        assertEquals(FailureReason.WIFI_SETUP_FAILED, reason(hotspot))
        assertEquals(FailureReason.BLUETOOTH, reason(CarPlayStatus.ConnectingBluetooth))
        assertEquals(FailureReason.AUTHENTICATION, reason(CarPlayStatus.RunningWireless, message = "MFi authentication failed"))
        assertEquals(FailureReason.ENGINE, reason(CarPlayStatus.AttachingNetwork))
        assertEquals(FailureReason.USB, reason(CarPlayStatus.AttachingNetwork, Transport.USB))
        assertEquals(FailureReason.LINK_TOO_WEAK, reason(CarPlayStatus.WirelessActive, message = "Wireless iAP2 tunnel closed"))
        assertEquals(FailureReason.USB, reason(CarPlayStatus.Pairing, Transport.USB))
        assertEquals(FailureReason.ENGINE, reason(null))
        assertEquals(FailureReason.USB, CarPlayEventMapping.classify(null, CarPlayStatus.RunningControl, Transport.USB).reason)
        assertNull(CarPlayEventMapping.classify(null, null, Transport.WIRELESS).message)
    }

    // --- RELIABILITY_AUDIT 2026-10-09 ---------------------------------------------------------

    @Test fun twoUnansweredDialsInARowFailAsUnreachableOverBluetooth() {
        // K3 / C8: DiPlay dials forever (5.15 s page + 2.5 s restart each); two misses are enough.
        val miss = CarPlayStatus.Failed("read failed, socket might closed or timeout, read ret: -1")
        val tracker = CarPlaySessionTracker(Transport.WIRELESS)
        tracker.started()
        tracker.onStatus(CarPlayStatus.ConnectingBluetooth)
        assertEquals(FailureReason.BLUETOOTH,
            (tracker.onHostState(HostState.Retrying(1, null, 1_500L, miss, miss.message)).single() as AdapterEvent.Retrying).reason)
        tracker.onStatus(CarPlayStatus.ConnectingBluetooth)
        val failed = tracker.onHostState(HostState.Retrying(2, null, 1_500L, miss, miss.message)).single() as AdapterEvent.Failed
        assertEquals(FailureReason.BLUETOOTH, failed.reason)
        assertEquals(CarPlaySessionTracker.NOT_REACHABLE_OVER_BLUETOOTH, failed.detail)
        assertTrue(tracker.finished)
    }

    @Test fun anAnsweredDialResetsTheMissCount() {
        val miss = CarPlayStatus.Failed("read failed, socket might closed or timeout, read ret: -1")
        val tracker = CarPlaySessionTracker(Transport.WIRELESS)
        tracker.started()
        tracker.onStatus(CarPlayStatus.ConnectingBluetooth)
        tracker.onHostState(HostState.Retrying(1, null, 1_500L, miss, miss.message))
        tracker.onStatus(CarPlayStatus.RunningWireless) // the iPhone answered this time
        tracker.onHostState(HostState.Retrying(2, null, 2_000L, CarPlayStatus.Failed("iAP2 link closed"), "iap2"))
        tracker.onStatus(CarPlayStatus.ConnectingBluetooth)
        assertTrue(tracker.onHostState(HostState.Retrying(3, null, 1_500L, miss, miss.message)).single() is AdapterEvent.Retrying)
        assertFalse(tracker.finished)
    }

    @Test fun wirelessMilestonesMoveTheBarToLinkingAndPicture() {
        // K4 / C9: RunningWireless used to hold "Phone answered" for 13.3 s until the first frame.
        val tracker = CarPlaySessionTracker(Transport.WIRELESS)
        tracker.started()
        assertEquals(listOf(AdapterEvent.Progress(2, 4, ProgressStage.HANDSHAKE)), tracker.onStatus(CarPlayStatus.RunningWireless))
        assertEquals(emptyList<AdapterEvent>(), tracker.onMilestone(WirelessStartupTimeline.Phase.AUTHENTICATED))
        assertEquals(listOf(AdapterEvent.Progress(3, 4, ProgressStage.JOINING_WIFI)),
            tracker.onMilestone(WirelessStartupTimeline.Phase.WIFI_CONFIG))
        assertEquals(emptyList<AdapterEvent>(), tracker.onMilestone(WirelessStartupTimeline.Phase.START_SESSION))
        assertEquals(listOf(AdapterEvent.Progress(4, 4, ProgressStage.STARTING_PROJECTION)),
            tracker.onMilestone(WirelessStartupTimeline.Phase.TCP_ACCEPTED))
        // A status of an earlier kind never moves the bar back.
        assertEquals(emptyList<AdapterEvent>(), tracker.onStatus(CarPlayStatus.RunningWireless))
        assertEquals(emptyList<AdapterEvent>(), tracker.onStatus(CarPlayStatus.WirelessActive))
        assertEquals(listOf(AdapterEvent.Connected), tracker.onHostState(HostState.FirstFrame))
    }

    @Test fun milestonesAreIgnoredOverUsb() {
        val tracker = CarPlaySessionTracker(Transport.USB)
        tracker.started()
        assertEquals(emptyList<AdapterEvent>(), tracker.onMilestone(WirelessStartupTimeline.Phase.TCP_ACCEPTED))
    }

    @Test fun aRetryStartsTheBarOverFromWaiting() {
        val tracker = CarPlaySessionTracker(Transport.WIRELESS)
        tracker.started()
        tracker.onStatus(CarPlayStatus.RunningWireless)
        tracker.onMilestone(WirelessStartupTimeline.Phase.WIFI_CONFIG)
        tracker.onHostState(HostState.Retrying(1, null, 2_000L, CarPlayStatus.Failed("iAP2 link closed"), "iap2"))
        assertEquals(listOf(AdapterEvent.Progress(1, 4, ProgressStage.WAITING_FOR_PHONE)), tracker.onStatus(CarPlayStatus.StartingHotspot))
    }
}
