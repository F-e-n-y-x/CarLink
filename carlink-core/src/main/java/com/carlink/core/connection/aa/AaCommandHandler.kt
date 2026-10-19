package com.carlink.core.connection.aa

import android.hardware.usb.UsbDevice
import android.util.Log
import com.andrerinas.openheadunit.HostIntegration.EngineEvent
import com.andrerinas.openheadunit.aap.AapService
import com.carlink.core.settings.VehicleProfile

/**
 * What the ":aa" process remembers about the engine between commands: the wireless settings it was
 * last armed with and whether it stood down for another Wi-Fi Direct owner since. Lost with the
 * process, which reads as "unknown" (a forced re-arm), never as "fine".
 */
internal class AaEngineMonitor {
    @Volatile var armedWith: WirelessConfig? = null
        private set
    @Volatile var p2pOwnedByOther = false
        private set

    fun onEngineEvent(kind: String) {
        if (kind == EngineEvent.P2P_OWNED_BY_OTHER.name) p2pOwnedByOther = true
    }

    fun armed(config: WirelessConfig) {
        armedWith = config
        p2pOwnedByOther = false
    }

    fun reset() {
        armedWith = null
        p2pOwnedByOther = false
    }

    companion object {
        /** The process-wide instance (one engine per ":aa" process). */
        val shared = AaEngineMonitor()
    }
}

/**
 * The ":aa" side of a session: turns the adapter's commands into the AapService intents DiAuto's own
 * entry points send (DA§7.1), on one background thread at a time ([AaCommandReceiver] serialises).
 * Every wait is bounded. Pure over [AaEngineAccess], so it is unit tested without DiAuto.
 */
internal class AaCommandHandler(
    private val engine: AaEngineAccess,
    private val monitor: AaEngineMonitor,
    /** Reports a receiver-side or engine event to the main process. */
    private val emit: (kind: String, detail: String?) -> Unit,
    /** Acknowledges a stop: token, AapService gone, CarLink's group gone (null: unreadable). */
    private val ack: (token: Long, serviceStopped: Boolean, groupReleased: Boolean?) -> Unit,
    /** Sends the current CommManager state to the main process. */
    private val report: () -> Unit,
) {
    /**
     * Wireless start (DA§11.2): host flags and poke targets for this session, then
     * `ACTION_START_WIRELESS` followed by `ACTION_NATIVE_AA_POKE(mac)`. The service is restarted only
     * when [WirelessStartPolicy] finds it wedged; a session that is connecting or up is left alone.
     */
    fun startWireless(address: String?, profile: VehicleProfile) {
        engine.setUsbOnlySession(false)
        engine.setClaimHiddenBottomStrip(profile == VehicleProfile.BYD_DILINK)
        // Exactly the chosen phone (DA§10.5, §10.7): an empty list pokes every bonded device, and a
        // long one pokes absent phones and wakes unwanted ones every 15 s cycle.
        if (address != null) engine.setPokeTargets(address)
        val decision = WirelessStartPolicy.decide(
            serviceRunning = engine.isServiceRunning(),
            busy = engine.isBusy(),
            wedged = monitor.p2pOwnedByOther,
            armedWith = monitor.armedWith,
            current = engine.wirelessConfig(),
        )
        Log.i(TAG, "wireless start decision=$decision phone=${address != null}")
        emit(AaProtocol.KIND_START_DECISION, "decision=$decision")
        when (decision) {
            WirelessStartDecision.ALREADY_ACTIVE -> {
                report()
                return
            }
            WirelessStartDecision.RESTART -> {
                if (!stopService()) Log.w(TAG, "AapService did not stop within ${SERVICE_STOP_MILLIS}ms; starting anyway")
                clearCarPlayLeftover()
            }
            WirelessStartDecision.START -> clearCarPlayLeftover()
            WirelessStartDecision.ARM, WirelessStartDecision.REARM -> Unit
        }
        monitor.armed(engine.wirelessConfig())
        // REARM: initWifiMode(force = true). ACTION_START_WIRELESS_SCAN does only that in mode 3,
        // plus clearing the user-exit cooldown and the boot-loop pause (DA§2.2).
        val action = if (decision == WirelessStartDecision.REARM) AapService.ACTION_START_WIRELESS_SCAN
            else AapService.ACTION_START_WIRELESS
        if (!start(action)) {
            monitor.reset()
            emit(AaProtocol.KIND_START_REFUSED, "action=$action")
            return
        }
        if (address != null) start(AapService.ACTION_NATIVE_AA_POKE, address)
        report()
    }

    /** Pokes the chosen phone again, for an armed, idle engine (the manual poke is one 20 s hold). */
    fun poke(address: String) {
        if (!engine.isServiceRunning() || engine.isBusy()) return
        start(AapService.ACTION_NATIVE_AA_POKE, address)
    }

    /**
     * USB start (DA§11.4): CarLink performs the Android Open Accessory switch itself, with the
     * engine's own USB auto-switch off (`auto-connect-last-session=false`). AapService is started
     * first so its USB receiver sees the phone re-enumerate as an accessory and connects it.
     */
    fun startUsb(device: UsbDevice?, address: String?, profile: VehicleProfile) {
        // No P2P group, poke or wireless server while the phone is on the cable (RELIABILITY_AUDIT
        // K10: the engine pulled a plugged-in phone into wireless Android Auto).
        engine.setUsbOnlySession(true)
        engine.setClaimHiddenBottomStrip(profile == VehicleProfile.BYD_DILINK)
        if (address != null) engine.setPokeTargets(address)
        // A phone already in accessory mode that this process never switched is still in the session
        // of a CarLink that is gone (restart, update, crash): its handshake can only fail on the old
        // stream (2 s, tablet 2026-10-20). Reset it before the engine starts, then switch it fresh.
        if (device != null && engine.isAccessory(device) && !switchedHere) {
            Log.i(TAG, "phone already an accessory at start; resetting its USB link first")
            emit(AaProtocol.KIND_AOA_RESWITCH, "result=reset=${engine.resetUsb(device)};device=${engine.deviceName(device)}")
            if (!engine.isServiceRunning()) {
                if (!start(null)) return emit(AaProtocol.KIND_START_REFUSED, "action=start")
                waitUntil(SERVICE_START_MILLIS) { engine.isServiceRunning() }
            }
            report()
            switchAfterReset()
            return
        }
        if (device == null || engine.isAccessory(device)) {
            // Already an accessory (or re-enumerated): the engine's own check finds and connects it.
            if (!start(AapService.ACTION_CHECK_USB)) {
                emit(AaProtocol.KIND_START_REFUSED, "action=${AapService.ACTION_CHECK_USB}")
            }
            report()
            // A phone left in accessory mode by an earlier session may never answer; say so in
            // seconds instead of the generic USB timeout (71 s on the tablet, 2026-10-09).
            // CarLink resets it and switches it again instead of asking for a re-plug (tablet, 2026-10-20).
            if (device != null && !waitUntil(STALE_ACCESSORY_MILLIS) { engine.isBusy() }) {
                Log.i(TAG, "accessory phone silent; resetting its USB link")
                reswitchStaleAccessory()
                if (!waitUntil(STALE_ACCESSORY_MILLIS) { engine.isBusy() }) emit(AaProtocol.KIND_AOA_STALE, "device=${engine.deviceName(device)}")
            }
            return
        }
        if (!engine.isServiceRunning()) {
            if (!start(null)) {
                emit(AaProtocol.KIND_START_REFUSED, "action=start")
                return
            }
            waitUntil(SERVICE_START_MILLIS) { engine.isServiceRunning() }
        }
        report()
        // Switch at once (aasdk sends the accessory queries with no wait; settling cost 600 ms of every
        // plug). A phone still re-enumerating fails the switch, and switchToAccessory retries on its
        // current handle. The permission dialog goes to the settled device: it must outlive the dialog.
        val current = engine.currentDevice(device) ?: device
        if (engine.hasUsbPermission(current)) return switchToAccessory(current)
        // The first attach can come with unreadable strings (usb_device_open failed, serial null), so
        // Android cannot match the driver's "Always" choice; the re-attach a moment later matches it
        // and the system grants access itself. Check again on the settled device before asking:
        // asking anyway showed the permission dialog on every plug (S25, 2026-10-20).
        val settled = settle(current)
        if (engine.hasUsbPermission(settled)) switchToAccessory(settled) else engine.requestUsbPermission(settled)
    }

    /** The phone once it has stopped re-enumerating, up to [SETTLE_MAX_MILLIS]. */
    private fun settle(device: UsbDevice): UsbDevice {
        var current = engine.currentDevice(device) ?: device
        var stableSince = engine.now()
        val end = engine.now() + SETTLE_MAX_MILLIS
        while (engine.now() < end && engine.now() - stableSince < SETTLE_STABLE_MILLIS) {
            engine.sleep(POLL_MILLIS)
            val now = engine.currentDevice(current) ?: continue
            if (engine.deviceName(now) != engine.deviceName(current) || now.deviceName != current.deviceName) {
                current = now
                stableSince = engine.now()
            }
        }
        return current
    }

    /**
     * A phone left in accessory mode by a session whose host side died (process death, reinstall)
     * hands the next session the old stream, and the handshake fails ("Drained 549115 bytes of stale
     * USB data", RELIABILITY_AUDIT K2). A phone already in accessory mode ignores a second accessory
     * switch (S25, Android 17), so the port is reset: the phone drops the old session and re-enumerates,
     * and the fresh attach is switched as a new plug. Asked for by the main process on the first
     * handshake failure (DiAuto's own re-switch waits for a second one that never comes).
     */
    fun reswitchStaleAccessory() {
        val device = engine.accessoryDevice()
        val result = when {
            device == null -> "no-accessory"
            engine.isBusy() -> "busy" // a new session is already under way
            else -> "reset=" + engine.resetUsb(device)
        }
        Log.i(TAG, "stale accessory re-switch result=$result")
        emit(AaProtocol.KIND_AOA_RESWITCH, "result=$result" + (device?.let { ";device=${engine.deviceName(it)}" } ?: ""))
        if (result == "reset=true") switchAfterReset()
    }

    /**
     * The phone comes back in its normal USB mode; the engine leaves the switch to CarLink and the
     * session treats the re-attach as the engine's, so switch it here (tablet, 2026-10-20: nobody
     * did, and the session failed 3 s later). Some resets keep the phone in accessory mode with no
     * re-attach at all (03:17:21): the old session is gone either way, so a fresh handshake works.
     */
    private fun switchAfterReset() {
        waitUntil(RESET_RETURN_MILLIS) { engine.plainAndroidPhone() != null }
        val phone = engine.plainAndroidPhone() ?: engine.accessoryDevice()
        if (phone == null) {
            Log.w(TAG, "the phone did not come back after the USB reset")
            return
        }
        Log.i(TAG, "after the USB reset: ${if (engine.isAccessory(phone)) "still an accessory; new handshake" else "plain phone; switching"}")
        if (engine.hasUsbPermission(phone)) switchToAccessory(phone) else engine.requestUsbPermission(phone)
    }

    /** The answer to [AaEngineAccess.requestUsbPermission]. */
    fun onUsbPermission(device: UsbDevice?, granted: Boolean) {
        when {
            device == null -> emit(AaProtocol.KIND_USB_DEVICE_MISSING, null)
            granted -> switchToAccessory(engine.currentDevice(device) ?: device)
            else -> emit(EngineEvent.USB_PERMISSION_DENIED.name, "device=${engine.deviceName(device)}")
        }
    }

    /** This process switched a phone to accessory mode (its accessory is ours, not a stale one). */
    private var switchedHere = false

    private fun switchToAccessory(device: UsbDevice) {
        switchedHere = true
        if (engine.isAccessory(device)) {
            start(AapService.ACTION_CHECK_USB)
            return
        }
        var switched = engine.switchToAccessory(device)
        Log.i(TAG, "AOA switch result=$switched")
        if (!switched) {
            // Once more on the current handle: the phone may have re-enumerated meanwhile.
            engine.sleep(RETRY_SWITCH_MILLIS)
            val again = engine.currentDevice(device)
            when {
                again == null -> Unit
                engine.isAccessory(again) -> { start(AapService.ACTION_CHECK_USB); return }
                !engine.hasUsbPermission(again) -> { engine.requestUsbPermission(again); return }
                else -> switched = engine.switchToAccessory(again).also { Log.i(TAG, "AOA switch retry result=$it") }
            }
        }
        if (!switched) emit(AaProtocol.KIND_AOA_SWITCH_FAILED, "device=${engine.deviceName(device)}")
    }

    /**
     * Ends everything and acknowledges only once AapService is gone and Android Auto's Wi-Fi Direct
     * group is released, so the next engine finds the radio free (design/REBUILD_V2.md §4).
     * `static-bssid` goes back to "0" (DA§10.1): it overrides both credential chains.
     */
    fun stop(token: Long) {
        val wasRunning = engine.isServiceRunning()
        val serviceStopped = !wasRunning || stopService()
        // AapService.onDestroy → WifiDirectManager.stop() removes the group asynchronously; give
        // it its own time first, then remove a group that is still ours.
        val groupReleased = releaseAndroidAutoGroup(waitForEngine = wasRunning)
        engine.resetStaticBssid()
        engine.setClaimHiddenBottomStrip(false)
        engine.setUsbOnlySession(false)
        monitor.reset()
        Log.i(TAG, "stop done serviceStopped=$serviceStopped groupReleased=$groupReleased")
        ack(token, serviceStopped, groupReleased)
        report()
    }

    /**
     * Starts AapService. A new one starts only with CarLink in front: Android gives a foreground
     * service while-in-use only when it starts while the app is visible, and without it the
     * microphone type is refused (Android 14+) and audio focus and playback fail (Android 17,
     * apps targeting 37). A running service keeps what it got at its start.
     */
    private fun start(action: String?, mac: String? = null): Boolean {
        if (!engine.isServiceRunning() && !engine.inFront()) {
            engine.bringToFront()
            val inFront = waitUntil(FRONT_MILLIS) { engine.inFront() }
            Log.i(TAG, "CarLink brought to the front for the engine start: inFront=$inFront")
            emit(AaProtocol.KIND_BROUGHT_TO_FRONT, "inFront=$inFront")
        }
        return engine.startService(action, mac)
    }

    private fun stopService(): Boolean {
        engine.startService(AapService.ACTION_STOP_SERVICE)
        return waitUntil(SERVICE_STOP_MILLIS) { !engine.isServiceRunning() }
    }

    private fun releaseAndroidAutoGroup(waitForEngine: Boolean): Boolean? {
        fun ours(reading: P2pGroupReading) = LeftoverGroupPolicy.isAndroidAutoGroup(
            reading.name, reading.isOwner, engine.keptGroupName(), engine.recordedGroupName())
        var reading = engine.currentGroup()
        if (waitForEngine) {
            val end = engine.now() + GROUP_RELEASE_MILLIS
            while (reading.known && ours(reading) && engine.now() < end) {
                engine.sleep(POLL_MILLIS)
                reading = engine.currentGroup()
            }
        }
        if (!reading.known) return null
        if (!ours(reading)) return true
        Log.i(TAG, "removing Android Auto's group ${reading.name} left after the engine stopped")
        engine.removeGroup()
        reading = engine.currentGroup()
        return if (reading.known) !ours(reading) else null
    }

    /** CarPlay's group left on the radio would make DiAuto stand down (foreign owner). */
    private fun clearCarPlayLeftover() {
        val reading = engine.currentGroup()
        val action = LeftoverGroupPolicy.decide(reading.name, reading.isOwner, engine.keptGroupName(), engine.recordedGroupName())
        when (action) {
            LeftoverGroupAction.REMOVE_CARPLAY_GROUP -> {
                Log.i(TAG, "removing CarPlay's leftover Wi-Fi Direct group ${reading.name}")
                engine.removeGroup()
            }
            LeftoverGroupAction.REMOVE_OTHER_INSTALL -> {
                Log.i(TAG, "removing another CarLink install's leftover Wi-Fi Direct group ${reading.name}")
                engine.removeGroup()
            }
            LeftoverGroupAction.LEAVE_FOREIGN -> Log.w(TAG, "a Wi-Fi Direct group not owned by CarLink is up (${reading.name}); leaving it")
            LeftoverGroupAction.NONE, LeftoverGroupAction.LEAVE_FOR_ENGINE -> Unit
        }
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val end = engine.now() + timeoutMillis
        while (!condition()) {
            if (engine.now() >= end) return false
            engine.sleep(POLL_MILLIS)
        }
        return true
    }

    companion object {
        private const val TAG = "CarLink-AA2"
        const val SERVICE_STOP_MILLIS = 6_000L
        const val SERVICE_START_MILLIS = 3_000L
        const val GROUP_RELEASE_MILLIS = 4_000L
        const val POLL_MILLIS = 200L
        const val SETTLE_STABLE_MILLIS = 600L
        const val SETTLE_MAX_MILLIS = 2_000L
        const val RETRY_SWITCH_MILLIS = 700L
        const val STALE_ACCESSORY_MILLIS = 3_000L
        const val RESET_RETURN_MILLIS = 2_000L
        const val FRONT_MILLIS = 3_000L
    }
}
