package com.carlink.app.connection

import com.carlink.app.core.Transport
import com.shilapi.xcertplay.CarPlayHostIntegration.HostState
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.orchestration.CarPlayStatus

/** What CarLink concluded about one DiPlay failure. */
data class CarPlayFailure(
    val reason: FailureReason,
    /**
     * True when retrying the same session cannot help (Bluetooth off, iPhone not paired, USB
     * permission denied, Same Wi-Fi details that do not match the network…). DiPlay retries most
     * of these without limit, so they are reported as soon as they happen.
     */
    val hard: Boolean,
    /** DiPlay's own (English, technical) message, for logs and diagnostics. */
    val message: String?,
    /** DiPlay's wireless startup category, when it gave one. */
    val startupFailure: WirelessStartupFailure? = null,
    /** A stale Wi-Fi Direct group must be removed before the next attempt (`WifiDirectReset`). */
    val wifiResetRequired: Boolean = false,
    /** The last stage DiPlay reported before the failure. */
    val stage: String? = null,
)

/**
 * Pure translation of DiPlay's statuses and host states into [AdapterEvent]s, one session at a
 * time. Kept free of Android so every rule is unit tested. Evidence: docs/dissection/DIPLAY.md.
 */
object CarPlayEventMapping {
    private val WIRELESS_STAGES = listOf(
        ProgressStage.WAITING_FOR_PHONE, ProgressStage.HANDSHAKE, ProgressStage.JOINING_WIFI,
        ProgressStage.STARTING_PROJECTION,
    )
    private val USB_STAGES = listOf(ProgressStage.WAITING_FOR_PHONE, ProgressStage.HANDSHAKE, ProgressStage.STARTING_PROJECTION)

    fun stages(transport: Transport): List<ProgressStage> =
        if (transport == Transport.WIRELESS) WIRELESS_STAGES else USB_STAGES

    /**
     * The driver-facing stage of [status] (DIPLAY.md §2.2 and §3.1 for the order), or null for
     * the end states. MFi preparation and the car's own network bring-up happen before the phone
     * is contacted, so they count as waiting for the phone. RunningWireless spans the iAP2
     * bootstrap, `0x5703` (the Wi-Fi details) and the wait for the iPhone's first AirPlay TCP;
     * most of its time is the iPhone joining the network (§3.4 stage A/B), so it is JOINING_WIFI.
     */
    fun stageOf(status: CarPlayStatus, transport: Transport): ProgressStage? = when (status) {
        CarPlayStatus.DiscoveringMfi, CarPlayStatus.WaitingForMfi, CarPlayStatus.RequestingMfiPermission,
        CarPlayStatus.MfiReady, CarPlayStatus.StartingHotspot, is CarPlayStatus.HotspotReady,
        CarPlayStatus.WaitingForPairedIphone, CarPlayStatus.DiscoveringIphone, CarPlayStatus.WaitingForIphone,
        CarPlayStatus.RequestingIphonePermission -> ProgressStage.WAITING_FOR_PHONE
        // Wireless: binding the AirPlay listener before the phone is called. Wired: the NCM link.
        CarPlayStatus.AttachingNetwork ->
            if (transport == Transport.WIRELESS) ProgressStage.WAITING_FOR_PHONE else ProgressStage.STARTING_PROJECTION
        // Only the phone's own answer moves the bar (user, 2026-10-09: "is the progress fake?"):
        // dialling the iPhone over Bluetooth is still waiting for it; RunningWireless means its
        // iAP2 channel answered. Wired: the iPhone is on the cable and switching configuration.
        CarPlayStatus.ConnectingBluetooth -> ProgressStage.WAITING_FOR_PHONE
        CarPlayStatus.SelectingConfiguration, CarPlayStatus.WaitingForReenumeration,
        CarPlayStatus.OpeningDataPaths, CarPlayStatus.Pairing, CarPlayStatus.ConnectingControl -> ProgressStage.HANDSHAKE
        CarPlayStatus.RunningWireless -> ProgressStage.HANDSHAKE
        // Not Connected yet: DiPlay notes "session establishment alone also occurs on black-screen
        // failures; only a rendered frame proves projection is usable" (CarPlayController.kt
        // ~1655), and RunningControl starts the wired control loop before AirPlay (~2024).
        CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback, CarPlayStatus.RunningControl ->
            ProgressStage.STARTING_PROJECTION
        CarPlayStatus.ControlEnded, is CarPlayStatus.Failed -> null
    }

    /** DiPlay messages (CarPlayController.kt, ExistingWifiManager.kt) that no retry can fix. */
    private val HARD_BLUETOOTH = listOf(
        "Bluetooth is not enabled", "Bluetooth adapter is unavailable", "no longer paired",
        "No unambiguous bonded iPhone", "Multiple connected iPhones", "Multiple bonded iPhones",
    )
    private val HARD_SAME_WIFI = listOf(
        "Multiple Wi-Fi networks are active", "Configured Wi-Fi does not match", "Connected Wi-Fi is open",
        "Connected Wi-Fi is secured", "requires an open or WPA2",
    )

    /**
     * Classifies a failure from DiPlay's startup category, its message and the stage it happened
     * in. [failure] is null for losses of an established session (AirPlay ended, transport error).
     */
    fun classify(
        failure: CarPlayStatus.Failed?,
        lastStage: CarPlayStatus?,
        transport: Transport,
    ): CarPlayFailure {
        val stageName = lastStage?.javaClass?.simpleName
        if (failure == null) {
            val reason = if (transport == Transport.USB) FailureReason.USB else FailureReason.LINK_TOO_WEAK
            return CarPlayFailure(reason, hard = false, message = null, stage = stageName)
        }
        val message = failure.message
        fun result(reason: FailureReason, hard: Boolean) = CarPlayFailure(
            reason, hard, message, failure.startupFailure, failure.wifiResetRequired, stageName,
        )
        // A stale P2P group: DiPlay shows "Reset CarPlay Wi-Fi" and does not retry (§2.3).
        if (failure.wifiResetRequired) return result(FailureReason.WIFI_SETUP_FAILED, hard = true)
        when (failure.startupFailure) {
            // F1: the iPhone authenticated but never opened AirPlay TCP; reachability, a path problem.
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> return result(FailureReason.PHONE_NEVER_REACHED_CAR, hard = false)
            WirelessStartupFailure.HOTSPOT_NOT_READY -> return result(FailureReason.WIFI_SETUP_FAILED, hard = false)
            // Hotspot off or details missing: DiPlay stops at once (§3.3).
            WirelessStartupFailure.HOTSPOT_CONFIGURATION -> return result(FailureReason.WIFI_SETUP_FAILED, hard = true)
            null -> Unit
        }
        if (HARD_BLUETOOTH.any { message.contains(it, ignoreCase = true) }) return result(FailureReason.BLUETOOTH, hard = true)
        if (message.contains("MFi", ignoreCase = true) || message.contains("CH341", ignoreCase = true)) {
            return result(FailureReason.AUTHENTICATION, hard = message.contains("permission", ignoreCase = true))
        }
        if (message.contains("USB permission", ignoreCase = true) || message.contains("USB service is unavailable", ignoreCase = true)) {
            return result(FailureReason.USB, hard = true)
        }
        if (HARD_SAME_WIFI.any { message.contains(it, ignoreCase = true) }) return result(FailureReason.WIFI_SETUP_FAILED, hard = true)
        val reason = when (lastStage) {
            CarPlayStatus.DiscoveringMfi, CarPlayStatus.WaitingForMfi, CarPlayStatus.RequestingMfiPermission ->
                FailureReason.AUTHENTICATION
            // A failure after MfiReady and before StartingHotspot is the Wi-Fi bring-up.
            CarPlayStatus.MfiReady, CarPlayStatus.StartingHotspot, is CarPlayStatus.HotspotReady ->
                if (transport == Transport.WIRELESS) FailureReason.WIFI_SETUP_FAILED else FailureReason.USB
            CarPlayStatus.WaitingForPairedIphone, CarPlayStatus.ConnectingBluetooth -> FailureReason.BLUETOOTH
            // The iAP2 bootstrap runs over RFCOMM: a failure here is the Bluetooth stage (F1: never
            // a reason to leave the Wi-Fi path), unless authentication itself was refused.
            CarPlayStatus.RunningWireless ->
                if (message.contains("auth", ignoreCase = true) || message.contains("certificate", ignoreCase = true)) {
                    FailureReason.AUTHENTICATION
                } else FailureReason.BLUETOOTH
            CarPlayStatus.AttachingNetwork ->
                if (transport == Transport.WIRELESS) FailureReason.ENGINE else FailureReason.USB
            CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback -> FailureReason.LINK_TOO_WEAK
            CarPlayStatus.DiscoveringIphone, CarPlayStatus.WaitingForIphone, CarPlayStatus.RequestingIphonePermission,
            CarPlayStatus.SelectingConfiguration, CarPlayStatus.WaitingForReenumeration, CarPlayStatus.OpeningDataPaths,
            CarPlayStatus.Pairing, CarPlayStatus.ConnectingControl, CarPlayStatus.RunningControl -> FailureReason.USB
            null, CarPlayStatus.ControlEnded, is CarPlayStatus.Failed -> FailureReason.ENGINE
        }
        return result(reason, hard = false)
    }
}

/**
 * One CarPlay session's event state machine: feed it DiPlay's statuses and host states in the
 * order DiPlay delivers them (main thread), and it returns the [AdapterEvent]s to report.
 *
 * Order fact used here: the host's status reporter runs `reconnectAfterLoss` (which publishes
 * [HostState.Retrying] or [HostState.RetriesStopped]) before CarPlayStatusBus republishes the
 * same `Failed` (CarPlayController.onStatus), so a failure is decided from the host state.
 */
class CarPlaySessionTracker(
    private val transport: Transport,
    /**
     * DiPlay retries failures without a startup category (Bluetooth, USB, lost sessions) with a
     * 2–30 s backoff and no limit (CarPlayHostActivity.reconnectAfterLoss). CarLink stops
     * waiting after as many as DiPlay allows a startup failure (WirelessStartupPolicy).
     */
    private val maxUnboundedRetries: Int = WirelessStartupPolicy.MAX_STARTUP_RETRIES,
) {
    private val stages = CarPlayEventMapping.stages(transport)
    private var lastStage: CarPlayStatus? = null
    private var lastProgress: ProgressStage? = null
    private var projecting = false
    private var everConnected = false
    private var unboundedRetries = 0

    /** True once [AdapterEvent.Ended] or [AdapterEvent.Failed] was returned; nothing follows. */
    var finished = false
        private set

    /** The failure behind the last [AdapterEvent.Retrying] or [AdapterEvent.Failed]. */
    var lastFailure: CarPlayFailure? = null
        private set

    /** The first event of a session, before DiPlay reports anything. */
    fun started(): AdapterEvent = progress(ProgressStage.WAITING_FOR_PHONE)!!

    fun onStatus(status: CarPlayStatus?): List<AdapterEvent> {
        if (finished) return emptyList()
        return when (status) {
            // F13: null is published at the start of every internal restart and before a
            // controller exists. It never means "no session"; HostState.Closed does.
            null -> emptyList()
            is CarPlayStatus.Failed -> if (status.wifiResetRequired) {
                // No host state follows: DiPlay waits for "Reset CarPlay Wi-Fi" (§2.3).
                finish(failed(CarPlayEventMapping.classify(status, lastStage, transport)))
            } else emptyList() // decided by the HostState that preceded it
            // DiPlay does not retry ControlEnded (F10). Wireless, it only comes when the Bluetooth
            // bootstrap timed out without CarPlay going active (CarPlayController ~1433).
            CarPlayStatus.ControlEnded -> finish(
                if (everConnected) AdapterEvent.Ended(byUser = false)
                else failed(CarPlayFailure(FailureReason.PHONE_DID_NOT_ANSWER, hard = true,
                    message = "CarPlay control ended before projection", stage = lastStage?.javaClass?.simpleName)),
            )
            else -> {
                lastStage = status
                if (projecting) emptyList() else listOfNotNull(CarPlayEventMapping.stageOf(status, transport)?.let(::progress))
            }
        }
    }

    fun onHostState(state: HostState): List<AdapterEvent> {
        if (finished) return emptyList()
        return when (state) {
            is HostState.Retrying -> onRetrying(state)
            is HostState.RetriesStopped -> {
                // F1: only now is FIRST_TCP_TIMEOUT a failure (DiPlay's own budget is spent).
                val failure = CarPlayEventMapping.classify(state.failure, lastStage, transport)
                    .let { if (state.failure == null) it.copy(startupFailure = state.startupFailure) else it }
                finish(failed(failure))
            }
            HostState.VpnConsentDenied ->
                finish(failed(CarPlayFailure(FailureReason.BLOCKED, hard = true, message = "VPN consent denied")))
            is HostState.PermissionsDenied ->
                finish(failed(CarPlayFailure(FailureReason.BLOCKED, hard = true,
                    message = "Permissions denied: ${state.permissions.joinToString()}")))
            is HostState.ForegroundServiceFailed ->
                finish(failed(CarPlayFailure(FailureReason.ENGINE, hard = true, message = state.message)))
            HostState.AuthenticationUnavailable ->
                finish(failed(CarPlayFailure(FailureReason.AUTHENTICATION, hard = true, message = "CarPlay authentication unavailable")))
            HostState.FirstFrame -> {
                projecting = true
                everConnected = true
                unboundedRetries = 0
                lastProgress = null
                listOf(AdapterEvent.Connected)
            }
            // The host closed without CarLink asking: the notification's Disconnect or a removed
            // task, both the driver's doing (DiPlaySessionService).
            HostState.Closed -> finish(AdapterEvent.Ended(byUser = true))
        }
    }

    private fun onRetrying(state: HostState.Retrying): List<AdapterEvent> {
        projecting = false
        lastProgress = null
        val failure = CarPlayEventMapping.classify(state.failure, lastStage, transport)
        lastFailure = failure
        // Retrying statuses start over from the beginning; the stage of the failure is kept above.
        lastStage = null
        if (failure.hard) return finish(failed(failure))
        // DiPlay's startup budget bounds these and ends in RetriesStopped (F1).
        if (state.maxAttempts != null) return listOf(AdapterEvent.Retrying(state.attempt, failure.reason))
        unboundedRetries += 1
        if (unboundedRetries <= maxUnboundedRetries) return listOf(AdapterEvent.Retrying(unboundedRetries, failure.reason))
        // A phone that was projecting and is now unreachable has left (§4: DiPlay has no
        // auto-reconnect beyond its own loop); anything else is a failure.
        val phoneGone = everConnected && failure.reason in setOf(FailureReason.BLUETOOTH, FailureReason.USB, FailureReason.LINK_TOO_WEAK)
        return finish(if (phoneGone) AdapterEvent.Ended(byUser = false) else failed(failure))
    }

    private fun progress(stage: ProgressStage): AdapterEvent? {
        if (stage == lastProgress) return null
        lastProgress = stage
        return AdapterEvent.Progress(stages.indexOf(stage) + 1, stages.size, stage)
    }

    private fun failed(failure: CarPlayFailure): AdapterEvent {
        lastFailure = failure
        return AdapterEvent.Failed(failure.reason, failure.message)
    }

    private fun finish(event: AdapterEvent): List<AdapterEvent> {
        finished = true
        return listOf(event)
    }
}
