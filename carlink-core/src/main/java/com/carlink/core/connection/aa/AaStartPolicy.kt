package com.carlink.core.connection.aa

/**
 * The wireless settings AapService arms itself with. `initWifiMode()` re-arms on its own only when
 * the mode or the native transport changes (DA§2.4), so a change of hotspot interface, name,
 * password or static BSSID on an already armed engine needs a forced re-arm. Kept in memory only.
 */
internal data class WirelessConfig(
    val mode: Int,
    val transport: Int,
    val hotspotInterface: String,
    val hotspotSsid: String,
    val hotspotPassword: String,
    val staticBssid: String,
)

/** What [AaCommandReceiver] does with a wireless start. */
internal enum class WirelessStartDecision {
    /** AapService is not running: start it (ACTION_START_WIRELESS), then poke. */
    START,
    /** Running, idle, armed with these settings: ACTION_START_WIRELESS (a no-op re-arm check), then poke. */
    ARM,
    /** Running and idle, but armed with other wireless settings: force a re-arm (ACTION_START_WIRELESS_SCAN), then poke. */
    REARM,
    /** Running but wedged in a way only a fresh service clears: stop it, then [START]. */
    RESTART,
    /** A session is connecting or up (`isBusy`): leave it alone. */
    ALREADY_ACTIVE,
}

/**
 * When to restart the Android Auto service on a wireless start (DA§10.5, DA§11.2 step 3).
 *
 * v1 stopped the service whenever `isConnected` was false. `isConnected` excludes `Connecting`
 * (CommManager.kt:190-196), so that killed attempts whose TCP session had already been accepted,
 * and every restart re-ran `onCreate` (car mode, receivers, a USB check and a new P2P group). Here
 * the session check is `isBusy` (Connecting included) and the service is restarted only when it is
 * wedged: the engine stood down because another owner held Wi-Fi Direct (WifiDirectManager's
 * foreign-group stand-down lasts until the manager is stopped). A handshake backoff is not a wedge:
 * the poke that follows every start clears it (`manualPoke` → `resetHandshakeBackoff`).
 */
internal object WirelessStartPolicy {
    fun decide(
        serviceRunning: Boolean,
        busy: Boolean,
        wedged: Boolean,
        armedWith: WirelessConfig?,
        current: WirelessConfig,
    ): WirelessStartDecision = when {
        !serviceRunning -> WirelessStartDecision.START
        busy -> WirelessStartDecision.ALREADY_ACTIVE
        wedged -> WirelessStartDecision.RESTART
        // Unknown (":aa" restarted under a running service) counts as changed: a forced re-arm is
        // cheap and never wrong, a stale one hands the phone an old network.
        armedWith != current -> WirelessStartDecision.REARM
        else -> WirelessStartDecision.ARM
    }
}

/** What to do with a Wi-Fi Direct group found up before a session starts or after it stops. */
internal enum class LeftoverGroupAction {
    /** No group. */
    NONE,
    /** Android Auto's own group (kept identity or the name DiAuto recorded): the engine reads or replaces it. */
    LEAVE_FOR_ENGINE,
    /** CarPlay's group (DiPlay's `DIRECT-dp<hash>-` namespace) left behind by the other engine: remove it. */
    REMOVE_CARPLAY_GROUP,
    /** Not CarLink's: leave it. The engine stands down and reports P2P_OWNED_BY_OTHER. */
    LEAVE_FOREIGN,
}

/**
 * Who owns a Wi-Fi Direct group (DA§10.11: no group removal on start unless another owner holds
 * it). v1 removed every group on every start and cleared DiAuto's ownership record, so the phone's
 * saved network never matched and a removeGroup could take another app's link down (it acts on the
 * whole radio). Now Android Auto's own group is left to the engine (PR #1006: read, not rebuilt),
 * CarPlay's leftover is removed because CarLink runs one engine at a time, and anything else is
 * left alone and reported.
 */
internal object LeftoverGroupPolicy {
    /** DiPlay's reinstall-stable namespace (vendor/diplay … network/P2pOwnership.kt). */
    private val CARPLAY_GROUP = Regex("^DIRECT-dp[0-9a-f]{12}-.*")

    fun decide(groupName: String?, isGroupOwner: Boolean, keptName: String?, recordedName: String?): LeftoverGroupAction = when {
        groupName.isNullOrBlank() -> LeftoverGroupAction.NONE
        isAndroidAutoGroup(groupName, isGroupOwner, keptName, recordedName) -> LeftoverGroupAction.LEAVE_FOR_ENGINE
        isGroupOwner && CARPLAY_GROUP.matches(groupName) -> LeftoverGroupAction.REMOVE_CARPLAY_GROUP
        else -> LeftoverGroupAction.LEAVE_FOREIGN
    }

    /** True for a group this device owns under Android Auto's kept or recorded name. */
    fun isAndroidAutoGroup(groupName: String?, isGroupOwner: Boolean, keptName: String?, recordedName: String?): Boolean =
        isGroupOwner && !groupName.isNullOrBlank() && (groupName == keptName || groupName == recordedName)
}
