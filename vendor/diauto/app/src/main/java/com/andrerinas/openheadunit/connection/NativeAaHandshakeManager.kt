package com.andrerinas.openheadunit.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.BluetoothWakePolicy
import com.andrerinas.openheadunit.aap.NativeCredentialsPolicy
import com.andrerinas.openheadunit.aap.NativeHandoffPolicy
import com.andrerinas.openheadunit.aap.NativeTransport
import com.andrerinas.openheadunit.aap.UnusableBssidAction
import com.andrerinas.openheadunit.aap.WppAction
import com.andrerinas.openheadunit.aap.WppEvent
import com.andrerinas.openheadunit.aap.WppFraming
import com.andrerinas.openheadunit.aap.WppHandshakeSession
import com.andrerinas.openheadunit.aap.WppMessageType
import com.andrerinas.openheadunit.aap.WppStage
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import android.os.Build
import android.os.SystemClock
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.*

/**
 * Manages the official Android Auto Wireless Bluetooth handshake.
 * This class implements the RFCOMM server protocol to exchange WiFi credentials with the phone.
 */
class NativeAaHandshakeManager(
    private val context: AapService,
    private val scope: CoroutineScope
) {
    companion object {
        private val AA_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
        private val HFP_UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
        // The phone-wake targets, and the rules for when a poke may run at all, live in
        // BluetoothWakePolicy — one of those records is also the one a phone call rides on.

        /** How long to wait for this head unit's own WiFi network to come up before giving up on
         *  a handshake. P2P group creation is the slow case. */
        private const val CREDENTIALS_WAIT_MS = 60_000L
        /** CarLink (PR #1029): how often a held Bluetooth channel reports itself; the phone pings it once a second. */
        private const val HOLD_SUMMARY_INTERVAL_MS = 60_000L

        /** How long to wait for the AAP TCP port to be bound before giving up on a handshake. */
        private const val PORT_WAIT_MS = 3_000L

        /**
         * How long to wait for the port after asking for the server to be (re)started.
         *
         * Deliberately short. [awaitWirelessServerListening] delays without pumping the session's
         * inbound channel, so everything spent here is time the phone's keepalives go unserviced;
         * the bind's own retry budget is under two seconds, so this only has to cover it.
         */
        private const val PORT_ENSURE_MS = 4_000L

        /** Which of [allServiceNames] are secondary Bluetooth radios, i.e. not [primaryServiceName]
         *  (dual-Bluetooth-radio head units). Pure and unit-testable: identity is by system
         *  service name, not MAC address, since BluetoothAdapter.getAddress() returns the fixed
         *  placeholder "02:00:00:00:00:00" for any non-privileged app on every device since
         *  Android 6.0 (API 23), so every real adapter instance looks identical by address alone. */
        internal fun filterSecondaryServiceNames(
            primaryServiceName: String,
            allServiceNames: List<String>
        ): List<String> {
            val primary = primaryServiceName.ifEmpty { "bluetooth_manager" }
            return allServiceNames.filter { it != primary }.distinct()
        }

        /**
         * The one-line explanation to log and show when this unit's Bluetooth is an external
         * module, or null when it isn't. Kept here so the handshake manager and the settings
         * compatibility probe say exactly the same thing.
         */
        fun externalBtDiagnostic(): String? = BluetoothHelper.externalBtEvidence?.let { evidence ->
            "NativeAA: external Bluetooth module detected ($evidence) — the phone is bonded to " +
                "the head unit's own Bluetooth chip, not the one Android exposes, so nothing we " +
                "write over RFCOMM reaches it. Bluetooth-based wireless cannot work on this unit; " +
                "use USB, or one of the WiFi modes that does not need the Bluetooth handshake."
        }

        /**
         * Whether to run the Bluetooth route anyway on a unit [externalBtDiagnostic] flagged.
         *
         * A manually configured secondary Bluetooth service is the user telling us which radio to
         * use, having found one automatic enumeration missed. That is exactly the case the
         * detection cannot see, so it must not be the one case we refuse to try — the diagnostic
         * still goes in the log either way.
         */
        fun externalBtOverridden(context: Context): Boolean =
            com.andrerinas.openheadunit.App.provide(context)
                .settings.manualSecondaryBluetoothServiceName.isNotEmpty()

        fun checkCompatibility(context: Context): Boolean {
            externalBtDiagnostic()?.let {
                AppLog.w(it)
                if (!externalBtOverridden(context)) return false
                AppLog.w("NativeAA: continuing anyway — a secondary Bluetooth service is configured manually.")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) 
                    != PackageManager.PERMISSION_GRANTED) {
                    AppLog.w("NativeAA: Compatibility Check skipped - Missing BLUETOOTH_CONNECT")
                    return false
                }
            }
            val adapter = BluetoothHelper.getBluetoothAdapter(context) ?: return false
            if (!adapter.isEnabled) return false
            return try {
                val socket = adapter.listenUsingRfcommWithServiceRecord("Compatibility Check", AA_UUID)
                socket.close()
                AppLog.i("NativeAA: Compatibility Check SUCCESS")
                true
            } catch (e: Exception) {
                AppLog.w("NativeAA: Compatibility Check FAILED: ${e.message}")
                false
            }
        }
    }

    private val settings = com.andrerinas.openheadunit.App.provide(context).settings
    private val commManager = com.andrerinas.openheadunit.App.provide(context).commManager
    private var aaServerSocket: BluetoothServerSocket? = null
    private var hfpServerSocket: BluetoothServerSocket? = null
    // Extra RFCOMM listeners opened on secondary Bluetooth radios (dual-Bluetooth head units).
    // Split by UUID so a successful handoff can close just the AA listeners (see
    // closeAaListeners()) without taking down the HFP ones too.
    private val extraAaServerSockets = java.util.Collections.synchronizedList(mutableListOf<BluetoothServerSocket>())
    private val extraHfpServerSockets = java.util.Collections.synchronizedList(mutableListOf<BluetoothServerSocket>())
    private var isRunning = false
    // Set by closeAaListeners() so the AA accept loops can tell "we closed this on purpose
    // after a successful handoff" apart from a real socket error, for logging only.
    @Volatile private var aaListenersClosedForSession = false
    // Whether the "already have a hands-free link, not poking" line has been said at info level for
    // the current run of skips. Cleared as soon as a poke does go ahead, so a later skip says so
    // again rather than hiding behind a line from minutes earlier.
    @Volatile private var handsFreeSkipLogged = false
    // CarLink (open-headunit c86332be): whether this app stands in for a primary radio with no
    // hands-free stack of its own. Answered once in start(); the poke needs the same answer the HFP
    // listener acted on, because driving a link from here would compete with a real stack.
    @Volatile private var standingInForHfp = false

    /**
     * The credentials to hand the phone, as one value.
     *
     * Four separate fields were written by WifiDirectManager's delivery thread and read by the
     * handshake coroutine with no synchronisation, which allowed two failures. A read could see the
     * SSID and passphrase of one group beside the BSSID of another, and Gearhead joins with a
     * WifiNetworkSpecifier matching SSID *and* BSSID under a full mask, so it rejects the pair with
     * no clue as to why. And the null check and the `!!` that followed it were separate reads, so an
     * invalidate landing between them threw a KotlinNullPointerException that surfaced as
     * "Handshake error: null" and named nothing.
     *
     * One immutable snapshot behind one volatile reference: a reader gets all four fields from the
     * same group or none of them, and reads them once.
     */
    private data class WifiCredentials(
        val ssid: String,
        val psk: String,
        val ip: String,
        val bssid: String,
    )

    @Volatile
    private var credentials: WifiCredentials? = null
    private var pokeJob: Job? = null
    // Last (ssid, ip, bssid) triggerPoke() restarted for - dedupes redundant restarts when
    // WifiDirectManager redelivers the same credentials, which was starving the poke before it
    // could ever finish.
    private var lastPokeTriggerCredentials: Triple<String, String, String>? = null
    // elapsedRealtime() when handleHandshake() started, or 0 when no exchange is running; lets
    // WifiDirectManager's join watchdog know a real exchange is in progress.
    //
    // [BUG_FIX] A stamp rather than a boolean, because handleHandshake() cannot be relied on to
    // clear it: on stacks where closing the socket does not unblock the wait for Type 2, the
    // coroutine never reaches its finally. Seen as three failed handshakes and zero "BT Handshake
    // socket closed." lines, with the old boolean stuck true for the rest of the process. See
    // NativeHandoffPolicy.isHandshaking.
    @Volatile private var handshakeStartedAt = 0L
    // True for the duration of a single pokeDevice() attempt (its socket.connect() call itself
    // can fire an OS-level ACL_CONNECTED broadcast before any real handshake starts) - see
    // isAttemptInFlight().
    @Volatile private var pokeAttemptInFlight = false
    // elapsedRealtime() when the last WifiInfoResponse (Type 3) went out, or 0 when no handoff is
    // settling. The phone spends the next several seconds associating, doing WPS and getting a
    // DHCP lease; see isHandoffSettling() and NativeHandoffPolicy.
    @Volatile private var handoffSettlingSince = 0L
    // The socket of the handshake currently being served. Kept so a phone that gives up and
    // reconnects over Bluetooth during a settle supersedes the stale one instead of running a
    // second handleHandshake() alongside it.
    @Volatile private var activeHandshakeSocket: HandshakeLink? = null // CarLink: HandshakeLink seam
    // CarLink: the external module route's carrier while it runs (ExternalBtTransportPolicy).
    @Volatile private var moduleCarrier: ExternalModuleCarrier? = null
    // The coroutine serving [activeHandshakeSocket]. Closing a superseded handshake's socket only
    // ends it on stacks where close() interrupts a pending read; some do not, and it runs on for
    // minutes. Cancelling cannot break a blocking JNI read either, but it does end every real
    // suspension point in the handshake. Do both; whichever the stack honours wins.
    @Volatile private var activeHandshakeJob: Job? = null
    // Name of the primary Bluetooth radio we listen and poke on, captured in start(). A field
    // rather than a local so the diagnostic below can name the radio the phone is ignoring.
    @Volatile private var localRadioName: String = "?"
    // [BUG_FIX] How many wake pokes the phone has answered without ever opening the AA channel,
    // and whether it ever has. The pair exists because "poke succeeds, nothing comes back" makes a
    // broken unit's log identical to a healthy one waiting for the user, while the phone is in
    // fact reconnecting every 12 s to the unit's own OEM Bluetooth module, which advertises the
    // same service record. See NativeHandoffPolicy.shouldWarnPhoneNeverCallsBack.
    @Volatile private var pokesSinceLastAccept = 0
    @Volatile private var everAcceptedAaConnection = false
    // [BUG_FIX] Handshakes that timed out waiting for Type 2, back to back. Where close() does not
    // interrupt a pending read each one strands a Dispatchers.IO thread forever, so this bounds
    // how many we are willing to strand. See NativeHandoffPolicy.shouldServeHandshake.
    @Volatile private var consecutiveHandshakeFailures = 0
    // Whether the "not serving handshakes" warning has already been logged for the current
    // backoff, so a phone retrying every ~12 s does not repeat the long explanation each time.
    @Volatile private var loggedHandshakeBackoff = false

    /** CarLink: the system Location switch, which Android ties to reading the Wi-Fi Direct MAC (DA§1.3). */
    private fun locationEnabled(): Boolean = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) { true }

    /** Polls until the AAP TCP port is bound, or [timeoutMs] passes. */
    private suspend fun awaitWirelessServerListening(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (context.isWirelessServerListening()) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(250)
        }
    }

    /**
     * Updates the WiFi credentials that will be sent to the phone during the next handshake.
     */
    fun updateWifiCredentials(ssid: String, psk: String, ip: String, bssid: String) {
        AppLog.i("NativeAA: Credentials updated. SSID=$ssid, IP=$ip, BSSID=$bssid")
        credentials = WifiCredentials(ssid = ssid, psk = psk, ip = ip, bssid = bssid)
    }

    /** Clears cached credentials so an in-progress wait doesn't hand out stale ones for a group
     *  that's about to be torn down. */
    fun invalidateCredentials() {
        credentials = null
    }

    // isRunning alone isn't enough once closeAaListeners() can close the AA_UUID listener while
    // leaving the manager otherwise running (HFP stays up) — callers like AutoStartReceiver's
    // BT-reconnect re-arm need to know whether a connection can actually be accepted right now,
    // not just whether the manager was start()ed. See the "Re-arm on Bluetooth reconnect" fix
    // this restores the invariant for: isActive() must mean "genuinely able to accept," not
    // "believed to be running."
    fun isActive(): Boolean = isRunning && !aaListenersClosedForSession

    fun isHandshakeInFlight(): Boolean =
        NativeHandoffPolicy.isHandshaking(handshakeStartedAt, SystemClock.elapsedRealtime())

    /**
     * True between delivering the WiFi credentials (Type 3) and the phone's TCP session actually
     * landing — the window in which it is still associating, doing WPS and getting a DHCP lease.
     *
     * [isHandshakeInFlight] deliberately goes false the instant Type 3 is written, because the
     * *credential exchange* is done at that point. The phone's work is not: measured joining the
     * group 0.73 s after Type 3 and still without an IP 2.4 s later. Anything that must not disturb
     * the phone mid-join — the wake poke, the BT auto-start re-arm, WifiDirectManager's join
     * watchdog — has to check this, not isHandshakeInFlight().
     */
    fun isHandoffSettling(): Boolean =
        NativeHandoffPolicy.isSettling(handoffSettlingSince, SystemClock.elapsedRealtime())

    // True while either a wake-up poke's socket.connect() or a real handshake is in progress, or
    // a delivered handoff is still settling. AutoStartReceiver's own poke can generate the
    // ACL_CONNECTED broadcast that re-triggers AapService's BT auto-start re-arm; callers
    // deciding whether it's safe to force-reinit should check this instead of isActive() alone.
    fun isAttemptInFlight(): Boolean = isHandshakeInFlight() || pokeAttemptInFlight || isHandoffSettling()

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return

        // Leave isRunning false, like the "adapter disabled" case below: isActive() callers must
        // see this as genuinely stopped. Nothing here is retryable, but a listener that was never
        // opened must not be reported as up.
        // CarLink: the route decision (ExternalBtTransportPolicy) replaces the blanket refusal.
        when (val route = ExternalBtTransportPolicy.routeFor(context)) {
            ExternalBtTransportPolicy.Route.ZBT, ExternalBtTransportPolicy.Route.BLINK -> return startOverModule(route)
            ExternalBtTransportPolicy.Route.BLOCKED -> { externalBtDiagnostic()?.let { AppLog.e(it) }; return }
            ExternalBtTransportPolicy.Route.NORMAL -> externalBtDiagnostic()?.let {
                AppLog.w("$it\nNativeAA: starting anyway — a secondary Bluetooth service is configured manually.")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED) {
                AppLog.e("NativeAA: Missing BLUETOOTH_CONNECT permission. Handshake server cannot start.")
                com.andrerinas.openheadunit.HostIntegration.engineEvent(context, // CarLink
                    com.andrerinas.openheadunit.HostIntegration.EngineEvent.BLUETOOTH_UNAVAILABLE, "cause=permission")
                return
            }
        }

        val adapter = BluetoothHelper.getBluetoothAdapter(context)
        if (adapter == null || !adapter.isEnabled) {
            // Leave isRunning false — isActive() callers (e.g. AapService's BT auto-start
            // re-arm check) need to see this as genuinely stopped so they retry later,
            // instead of believing the listener sockets are up when nothing was ever opened.
            AppLog.e("NativeAA: Bluetooth adapter not available or disabled")
            com.andrerinas.openheadunit.HostIntegration.engineEvent(context, // CarLink
                com.andrerinas.openheadunit.HostIntegration.EngineEvent.BLUETOOTH_UNAVAILABLE, "cause=off")
            return
        }

        isRunning = true
        aaListenersClosedForSession = false
        // Local Bluetooth radio name; logged on every accept so a dual-radio head unit's logs
        // show which radio the phone actually reached (compare with the HU name in the phone's
        // log). Uses adapter.name, not adapter.address: getAddress() returns the fixed masked
        // placeholder "02:00:00:00:00:00" for any non-privileged app since Android 6.0 (API 23),
        // but getName() returns the real radio name (confirmed on-device: e.g. "Navegadortz2").
        localRadioName = try { adapter.name ?: "?" } catch (e: Exception) { "?" }
        AppLog.i("NativeAA: Starting Bluetooth Handshake Servers (primary radio [$localRadioName])...")

        // Start AA RFCOMM Server
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-RfcommServer")) {
            try {
                aaServerSocket = adapter.listenUsingRfcommWithServiceRecord("AA BT Listener", AA_UUID)
                AppLog.i("NativeAA: ACTIVELY LISTENING on Android Auto UUID ($AA_UUID) on radio [$localRadioName]... Waiting for phone to connect back!")
                while (isRunning && isActive) {
                    val socket = aaServerSocket?.accept()
                    if (socket != null) {
                        AppLog.i("NativeAA: Connection accepted from ${socket.remoteDevice.name} (${socket.remoteDevice.address}) on local radio [$localRadioName]")
                        if (refuseWhileBackedOff(socket)) continue
                        // [FIX] Launch handshake in a separate coroutine so the server can accept the next connection!
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Handshake-${socket.remoteDevice.address}")) {
                            handleHandshake(BluetoothSocketLink(socket), localRadioName) // CarLink: HandshakeLink seam
                        }
                    }
                }
            } catch (e: Exception) {
                if (aaListenersClosedForSession) {
                    AppLog.d("NativeAA: AA Server socket closed after successful handoff.")
                } else if (isRunning) {
                    AppLog.e("NativeAA: AA Server socket error: ${e.message}", e)
                } else {
                    AppLog.d("NativeAA: AA Server socket closed cleanly.")
                }
            }
        }

        // Start HFP RFCOMM Server (Required by some phones to detect HU)
        // CarLink (open-headunit c86332be): only where the radio advertises no Hands-Free of its own.
        standingInForHfp = shouldRegisterDummyHfp(adapter, localRadioName)
        if (standingInForHfp) scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpServer")) {
            try {
                // CarLink (PR #1029): a local, so a record withdrawn for the session ends this loop
                // instead of leaving it spinning on a nulled field.
                val server = openHfpRecord(adapter, localRadioName) ?: return@launch // CarLink (c3b58e61)
                hfpServerSocket = server
                while (isRunning && isActive) {
                    val socket = server.accept()
                    if (socket != null) {
                        logHfpAccept(socket, localRadioName)
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpResponder-${socket.remoteDevice.address}")) {
                            // CarLink (c86332be): we publish Hands-Free, so the opening exchange is ours to start.
                            serveHfp(socket, "radio [$localRadioName]", "stand-in",
                                initiate = shouldInitiateSlc(standingInForHfp, socket.remoteDevice), closeWhenDone = true)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning && !aaListenersClosedForSession) {
                    AppLog.e("NativeAA: HFP Server socket error: ${e.message}", e)
                } else {
                    AppLog.d("NativeAA: HFP Server socket closed cleanly.")
                }
            }
        }

        // Some head units have two Bluetooth radios (e.g. "K706" and "CAR8032"). The phone may
        // be bonded to whichever one isn't the primary, so it never reaches the listener above.
        // Match radios by system service name, not MAC address: BluetoothAdapter.getAddress()
        // returns the fixed placeholder "02:00:00:00:00:00" for any non-privileged app since
        // Android 6.0 (API 23), on every device - primary and secondary always look identical
        // by address alone.
        val handles = try {
            BluetoothHelper.getAllBluetoothAdapterHandles(context)
        } catch (e: Exception) { emptyList() }

        // Manual fallback: some ROMs' second radio isn't discoverable via
        // ServiceManager.listServices() at all (blocked, or named without "bluetooth"), so
        // automatic enumeration never finds it. Let the user force it by exact system service
        // name instead.
        val manualServiceName = settings.manualSecondaryBluetoothServiceName
        val allHandles = if (manualServiceName.isNotEmpty() && handles.none { it.serviceName == manualServiceName }) {
            val manualHandle = try { BluetoothHelper.getAdapterHandleForService(context, manualServiceName) } catch (e: Exception) { null }
            if (manualHandle != null) {
                AppLog.i("NativeAA: Manual secondary Bluetooth service '$manualServiceName' resolved successfully.")
                handles + manualHandle
            } else {
                AppLog.w("NativeAA: Manual secondary Bluetooth service '$manualServiceName' could not be resolved to a working adapter.")
                handles
            }
        } else handles

        val secondaryNames = filterSecondaryServiceNames(
            settings.bluetoothManagerServiceName,
            allHandles.map { it.serviceName }
        ).toSet()
        val secondaries = allHandles.filter { it.serviceName in secondaryNames }
        if (secondaries.isNotEmpty()) {
            AppLog.i("NativeAA: Opening AA listeners on ${secondaries.size} secondary Bluetooth radio(s) for dual-radio head units: ${secondaries.joinToString { it.serviceName }}")
            secondaries.forEach { launchExtraServers(it.serviceName, it.adapter) }
        }
    }

    /**
     * Open supplementary AA + HFP RFCOMM listeners on a secondary Bluetooth radio, so a phone
     * bonded to that radio (dual-Bluetooth head units) can still reach us. Experimental, and
     * fully guarded so a bad radio cannot affect the primary listener.
     */
    private fun launchExtraServers(serviceName: String, extra: BluetoothAdapter) {
        // extra.name, not extra.address - see the comment on localRadioName in start(); the
        // address is always the masked placeholder, the name is the real, useful identifier.
        val radioName = try { extra.name ?: "?" } catch (e: Exception) { "?" }
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-RfcommServer-2")) {
            try {
                val server = extra.listenUsingRfcommWithServiceRecord("AA BT Listener", AA_UUID)
                extraAaServerSockets.add(server)
                AppLog.i("NativeAA: ACTIVELY LISTENING on Android Auto UUID on secondary radio '$serviceName' [$radioName]")
                while (isRunning && isActive) {
                    val socket = server.accept()
                    if (socket != null) {
                        AppLog.i("NativeAA: Connection accepted (secondary radio '$serviceName' [$radioName]) from ${socket.remoteDevice.name} (${socket.remoteDevice.address})")
                        if (refuseWhileBackedOff(socket)) continue
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Handshake-${socket.remoteDevice.address}")) {
                            handleHandshake(BluetoothSocketLink(socket), radioName) // CarLink: HandshakeLink seam
                        }
                    }
                }
            } catch (e: Exception) {
                if (aaListenersClosedForSession) AppLog.d("NativeAA: Secondary AA server closed after successful handoff ['$serviceName' $radioName].")
                else if (isRunning) AppLog.e("NativeAA: Secondary AA server error ['$serviceName' $radioName]: ${e.message}", e)
                else AppLog.d("NativeAA: Secondary AA server closed cleanly ['$serviceName' $radioName].")
            }
        }
        // CarLink (c86332be): this radio's own answer; standingInForHfp speaks only for the primary.
        val standingInOnExtra = shouldRegisterDummyHfp(extra, "'$serviceName' $radioName")
        if (standingInOnExtra) scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpServer-2")) {
            try {
                val server = openHfpRecord(extra, "'$serviceName' $radioName") ?: return@launch // CarLink (c3b58e61)
                extraHfpServerSockets.add(server)
                while (isRunning && isActive) {
                    val socket = server.accept()
                    if (socket != null) {
                        logHfpAccept(socket, "$serviceName $radioName")
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpResponder-${socket.remoteDevice.address}")) {
                            serveHfp(socket, "radio [$serviceName $radioName]", "stand-in", // CarLink (c86332be)
                                initiate = shouldInitiateSlc(standingInOnExtra, socket.remoteDevice), closeWhenDone = true)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) AppLog.e("NativeAA: Secondary HFP server error ['$serviceName' $radioName]: ${e.message}", e)
                else AppLog.d("NativeAA: Secondary HFP server closed cleanly ['$serviceName' $radioName].")
            }
        }
    }

    /**
     * Stop accepting AA_UUID connections once the handoff lands, because the phone's own channel is
     * held for the session (CarLink: ported from open-headunit PR #1029). Closing that channel
     * instead makes Android Auto re-dial every few seconds while a Bluetooth profile keeps this unit
     * present. The primary radio's stand-in HFP record goes too, so a phone cannot attach its
     * hands-free link (and its calls) to a record that cannot carry them while projection runs.
     * Both reopen the next time start() runs, which AapService does when the session ends.
     */
    private fun closeAaListeners() {
        aaListenersClosedForSession = true
        try { aaServerSocket?.close() } catch (e: Exception) {}
        hfpServerSocket?.let { // CarLink (PR #1029)
            AppLog.i("NativeAA: session is live, so the stand-in hands-free record is withdrawn until it ends.")
            try { it.close() } catch (e: Exception) {}
            hfpServerSocket = null
        }
        synchronized(extraAaServerSockets) {
            extraAaServerSockets.forEach { try { it.close() } catch (e: Exception) {} }
            extraAaServerSockets.clear()
        }
    }

    /**
     * Says what an accepted hands-free connection means, not only that it happened. On its own it
     * reads like success; it is the phone attaching its hands-free link to this app rather than to
     * the head unit's own Bluetooth stack, and the responder below can never carry call audio —
     * it answers OK to everything and negotiates neither a codec nor a SCO link.
     *
     * Whether it ever fires is still open: five rig rounds and every reporter log so far, no
     * accepts. Address as well as name, because getName() is null for an unbonded device.
     */
    private fun logHfpAccept(socket: BluetoothSocket, radio: String) {
        val device = socket.remoteDevice
        AppLog.i("NativeAA: HFP connection accepted from ${device.name ?: "unnamed"} (${device.address}) " +
                "on radio [$radio] — the phone's hands-free link now terminates in this app, " +
                "which cannot carry call audio. If calls are not heard on this unit, look here first.")
    }

    /**
     * CarLink: ported from open-headunit c86332be (`serveHfpSocket`; the loop is [HfpLink]). Serves
     * one hands-free channel, answering the phone and, when [initiate], opening the service level
     * connection ourselves; replaces the old responder, which waited to be spoken to and so left a
     * phone that dialled us (or that we poked) at "connecting". [via] names the channel in
     * `WIRELESS_TIMING`. [closeWhenDone] is false for the wake poke, whose caller owns that socket.
     */
    private suspend fun serveHfp(
        socket: BluetoothSocket,
        label: String,
        via: String,
        initiate: Boolean,
        closeWhenDone: Boolean,
    ) = withContext(Dispatchers.IO) {
        val progress = HfpLink.Progress()
        val startedAt = SystemClock.elapsedRealtime()
        fun elapsed() = SystemClock.elapsedRealtime() - startedAt
        try {
            AppLog.i("NativeAA: HFP responder active for $label (opens the link: $initiate)")
            if (initiate) com.andrerinas.openheadunit.aap.WirelessTiming.mark(
                com.andrerinas.openheadunit.aap.WirelessTimeline.Phase.HFP_SLC_START, "via=$via")
            HfpLink.serve(
                socket.inputStream, socket.outputStream, initiate,
                isOpen = { isRunning && socket.isConnected },
                progress = progress,
                log = { AppLog.d("NativeAA: HFP $it ($label)") },
            ) {
                com.andrerinas.openheadunit.aap.WirelessTiming.mark(
                    com.andrerinas.openheadunit.aap.WirelessTimeline.Phase.HFP_SLC_DONE,
                    "via=$via steps=${progress.commandsSent} ms=${elapsed()}")
                // Warning, not info: this is the moment the cost lands, and a reporter whose calls
                // stop being heard needs it in a log exported at the default level.
                AppLog.w("NativeAA: hands-free service level connection established ($label) after " +
                    "${progress.commandsSent} AT commands in ${elapsed()}ms. The phone now treats this head " +
                    "unit as its hands-free device, and this app cannot carry call audio.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.d("NativeAA: HFP responder error ($label): ${e.message}")
        } finally {
            HfpServiceRecordPolicy.standInRefusalReason(initiate, progress.stage)?.let { why ->
                com.andrerinas.openheadunit.aap.WirelessTiming.mark(
                    com.andrerinas.openheadunit.aap.WirelessTimeline.Phase.HFP_SLC_FAILED,
                    "via=$via stage=${progress.stage} steps=${progress.commandsSent} ms=${elapsed()}")
                AppLog.i("NativeAA: the hands-free connection ($label) did not complete in ${elapsed()}ms: " +
                    "$why. Android Auto will not start wireless setup without one.")
            }
            if (closeWhenDone) {
                try { socket.close() } catch (e: Exception) {}
                AppLog.i("NativeAA: HFP socket for ${socket.remoteDevice.address} closed.")
            }
        }
    }

    /**
     * CarLink: ported from open-headunit c86332be. Whether to publish the stand-in Hands-Free record
     * on [adapter]: not where the radio already advertises Hands-Free (the real stack answers calls,
     * this app cannot). getUuids() is not public API, so it is read reflectively, and a refusal means
     * "register it", as DiAuto always did. [HfpServiceRecordPolicy] holds the rule.
     */
    private fun shouldRegisterDummyHfp(adapter: BluetoothAdapter, radio: String): Boolean {
        val uuids = try {
            when (val raw = adapter.javaClass.getMethod("getUuids").invoke(adapter)) {
                is Array<*> -> raw.mapNotNull { it?.toString() }
                is List<*> -> raw.mapNotNull { it?.toString() }
                else -> null
            }
        } catch (e: Throwable) {
            AppLog.d("NativeAA: could not read radio [$radio]'s service UUIDs: ${e.message}")
            null
        }
        val register = HfpServiceRecordPolicy.shouldRegisterDummyHfp(uuids)
        if (!register) {
            AppLog.i("NativeAA: radio [$radio] already advertises Hands-Free, so the stand-in HFP record " +
                "is not registered and the wake poke does not open a hands-free link - the real stack answers calls, this app cannot.")
        } else {
            val why = if (uuids == null) "its records could not be read" else "it advertises no Hands-Free"
            AppLog.i("NativeAA: radio [$radio] gets the stand-in HFP record, because $why.")
        }
        return register
    }

    /**
     * CarLink: ported from open-headunit c3b58e61. The stand-in Hands-Free record, with a refusal
     * given a few more tries (one stack refused it 20 ms after the Android Auto record registered).
     * Null when this radio will not publish one.
     */
    private suspend fun openHfpRecord(adapter: BluetoothAdapter, radio: String): BluetoothServerSocket? {
        var attempt = 1
        while (isRunning) {
            try {
                return adapter.listenUsingRfcommWithServiceRecord("Hands-Free Unit", HFP_UUID)
            } catch (e: Exception) {
                if (!isRunning) return null
                if (!HfpServiceRecordPolicy.registrationRefused(attempt)) {
                    AppLog.w("NativeAA: radio [$radio] refused the stand-in Hands-Free record (${e.message}); asking again in ${HfpServiceRecordPolicy.REGISTRATION_RETRY_GAP_MS}ms.")
                    delay(HfpServiceRecordPolicy.REGISTRATION_RETRY_GAP_MS)
                    attempt++
                } else {
                    AppLog.e("NativeAA: radio [$radio] would not publish a Hands-Free record in $attempt attempts (${e.message}). Android Auto will not start wireless setup against a head unit with no hands-free profile.", e)
                    return null
                }
            }
        }
        return null
    }

    /** CarLink: an iPhone or iPad, by its cached records or name ([BluetoothWakePolicy.looksLikeApple]). */
    private fun isApple(device: BluetoothDevice): Boolean = BluetoothWakePolicy.looksLikeApple(
        try { device.uuids?.map { it.uuid } } catch (e: Exception) { null },
        try { device.name } catch (e: Exception) { null },
    )

    /**
     * CarLink: ported from open-headunit c86332be (`shouldInitiateSlc`), for a phone that opened a
     * channel to the stand-in record. A readable live hands-free link stands it down (the phone's own
     * hands-free device answers it), and CarLink completes the link only for the phone this session
     * pokes, never for an iPhone ([BluetoothWakePolicy.mayOpenHandsFreeFor]).
     */
    private fun shouldInitiateSlc(standingIn: Boolean, device: BluetoothDevice): Boolean {
        val address = try { device.address } catch (e: Exception) { null }
        val link = BluetoothWakePolicy.HandsFreeLink.of(BluetoothHelper.handsFreeLinkState(context, address))
        val open = HfpServiceRecordPolicy.shouldOpenServiceLevelConnection(standingIn, link) &&
            BluetoothWakePolicy.mayOpenHandsFreeFor(address, settings.autoStartBluetoothDeviceMacs, isApple(device))
        if (!open) AppLog.i("NativeAA: answering $address on the stand-in record without opening a hands-free " +
            "link (stand-in=$standingIn, link=$link, poke targets=${settings.autoStartBluetoothDeviceMacs.size}).")
        return open
    }

    /**
     * Read a device's pairing state, keeping "not paired" and "could not tell" apart.
     *
     * `getBondState()` answers `BOND_NONE` when the Bluetooth service is unavailable rather than
     * saying it does not know, so an adapter that is off would otherwise look exactly like a phone
     * the user unpaired. Everything that cannot be established reads as
     * [BluetoothWakePolicy.BondReading.UNREADABLE], and the policy decides what each is worth.
     */
    private fun bondReadingFor(device: BluetoothDevice): BluetoothWakePolicy.BondReading {
        val adapter = try {
            BluetoothHelper.getBluetoothAdapter(context)
        } catch (e: Exception) {
            null
        } ?: return BluetoothWakePolicy.BondReading.UNREADABLE
        val enabled = try { adapter.isEnabled } catch (e: Exception) { false }
        if (!enabled) return BluetoothWakePolicy.BondReading.UNREADABLE
        val state = try {
            device.bondState
        } catch (e: Exception) {
            return BluetoothWakePolicy.BondReading.UNREADABLE
        }
        return if (state == BluetoothDevice.BOND_BONDED) BluetoothWakePolicy.BondReading.BONDED
        else BluetoothWakePolicy.BondReading.NOT_BONDED
    }

    /** As [bondReadingFor], for a MAC that has not been resolved to a device yet. */
    private fun bondReadingFor(adapter: BluetoothAdapter, mac: String): BluetoothWakePolicy.BondReading {
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            // Not a Bluetooth address. It can never become one, so this is the one reading that is
            // safe to forget without the adapter having said anything.
            return BluetoothWakePolicy.BondReading.MALFORMED
        } catch (e: Exception) {
            return BluetoothWakePolicy.BondReading.UNREADABLE
        }
        return bondReadingFor(device)
    }

    /**
     * Say once per run of skips that the poke stood down. Info first so it survives a log exported
     * at the default level, then debug: the retry loop asks again every ~30 s, and a line per
     * half-minute for a whole session buries everything around it.
     */
    private fun noteHandsFreePokeSkip(device: BluetoothDevice) {
        val message = "NativeAA: Not poking ${device.name ?: "unnamed"} (${device.address}) — this " +
                "head unit already holds a Bluetooth hands-free link, which a poke would take over " +
                "and leave disconnected. That link is itself the connection a poke exists to create."
        if (!handsFreeSkipLogged) {
            handsFreeSkipLogged = true
            AppLog.i(message)
        } else {
            AppLog.d(message)
        }
    }

    /**
     * Tries each of [BluetoothWakePolicy.POKE_TARGETS] in turn, holding whichever connects for
     * [holdMs]. Returns true if any of them did, false without opening anything if either guard
     * below stands the poke down. Both poke entry points come through here, so one check covers
     * the retry loop and the manual poke alike.
     */
    private suspend fun pokeDevice(device: BluetoothDevice, holdMs: Long): Boolean {
        // CarLink: a USB-only session never pokes, and an iPhone is never poked: it is CarPlay's,
        // and the hold below would take its hands-free link.
        if (com.andrerinas.openheadunit.HostIntegration.usbOnlySession) {
            AppLog.i("NativeAA: Not poking ${device.address} — this is a USB-only session.")
            return false
        }
        if (isApple(device)) {
            AppLog.i("NativeAA: Not poking ${device.name ?: "unnamed"} (${device.address}) — it is an Apple device.")
            return false
        }
        // A poke that connects takes the phone's single hands-free slot, and this unit's own client
        // is dropped to make room. See BluetoothWakePolicy for the measurement.
        val handsFreeLink = BluetoothWakePolicy.HandsFreeLink.of(BluetoothHelper.handsFreeLinkState(context, device.address)) // CarLink: per phone
        if (!BluetoothWakePolicy.shouldPoke(handsFreeLink)) {
            noteHandsFreePokeSkip(device)
            return false
        }
        handsFreeSkipLogged = false

        // connect() against an unpaired device makes the OS solicit pairing as a side effect, and
        // the user meant "wake my phone", not "ask to pair with it again".
        if (!BluetoothWakePolicy.mayPoke(bondReadingFor(device))) {
            AppLog.w("NativeAA: Not poking ${device.name ?: "unnamed"} (${device.address}) — it is not " +
                    "currently paired with this head unit, and connecting to an unpaired device would " +
                    "ask the user to pair rather than wake anything.")
            return false
        }

        pokeAttemptInFlight = true
        try {
            for (uuid in BluetoothWakePolicy.POKE_TARGETS) {
                val profile = BluetoothWakePolicy.profileName(uuid)
                var socket: BluetoothSocket? = null
                try {
                    socket = device.createRfcommSocketToServiceRecord(uuid)
                    AppLog.i("NativeAA: Calling socket.connect() for ${device.name} via $profile ($uuid)...")
                    com.andrerinas.openheadunit.aap.WirelessTiming.mark(com.andrerinas.openheadunit.aap.WirelessTimeline.Phase.POKE_START, "profile=$profile credentials=${credentials != null}") // CarLink
                    socket.connect()
                    com.andrerinas.openheadunit.aap.WirelessTiming.mark(com.andrerinas.openheadunit.aap.WirelessTimeline.Phase.POKE_CONNECTED, "profile=$profile") // CarLink
                    // Named, not just the UUID: which record we ended up on is the first thing to
                    // check when a reporter's calls come out of the phone instead of the car.
                    AppLog.i("NativeAA: Successfully poked ${device.name} via $profile. Holding ${holdMs}ms...")
                    // Counted before the hold, so a poke that is cancelled mid-hold still counts:
                    // the phone answered, which is the whole point of the count.
                    pokesSinceLastAccept++
                    holdPoke(socket, device, profile, uuid, holdMs, handsFreeLink) // CarLink (c86332be)
                    return true
                } catch (e: CancellationException) {
                    // Rethrow instead of falling through to the next UUID: a cancelled poke (e.g.
                    // handleHandshake()'s pokeJob?.cancel() once a real handshake lands) must stop
                    // immediately, not fire another real, blocking socket.connect() on the same
                    // physical radio right as the critical WifiStartRequest send is about to happen.
                    throw e
                } catch (e: Exception) {
                    // Address as well as name: getName() is null for an unbonded device, and a log line
                    // reading "to null" names nothing at all for the reader of a bug report.
                    // CarLink: info with the exception type, so a field log says why the phone was not woken.
                    AppLog.i("NativeAA: Poke via $profile to ${device.name ?: "unnamed"} (${device.address}) failed: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    try { socket?.close() } catch (e: Exception) {}
                }
            }
            return false
        } finally {
            pokeAttemptInFlight = false
        }
    }

    /**
     * CarLink: ported from open-headunit c86332be. Holds a connected poke for [holdMs], speaking
     * hands-free over it where that is what it reached. The phone moves its hands-free profile to
     * "connecting" on our connection and then waits for us to open the exchange; a silent hold times
     * out there, and Android Auto 17.8 will not start wireless setup without a connected profile
     * (`waitForHeadUnitConnected timeout`, `WIRELESS_SETUP_FAILED_TO_START_NO_HFP_FROM_HU_PRESENCE`).
     * The targets, the hold and the retry cadence are unchanged. [handsFreeLink] is the reading the
     * poke guard just let through.
     */
    private suspend fun holdPoke(
        socket: BluetoothSocket,
        device: BluetoothDevice,
        profile: String,
        uuid: UUID,
        holdMs: Long,
        handsFreeLink: BluetoothWakePolicy.HandsFreeLink,
    ) {
        if (!HfpServiceRecordPolicy.shouldOpenServiceLevelConnection(standingInForHfp, handsFreeLink) ||
            !BluetoothWakePolicy.carriesServiceLevelConnection(uuid)) {
            AppLog.i("NativeAA: holding the $profile poke without a hands-free link (stand-in=$standingInForHfp).")
            delay(holdMs)
            return
        }
        coroutineScope {
            // A child of this poke, so cancelling the poke unwinds it before pokeDevice() closes the socket.
            val slc = launch(CoroutineName("NativeAa-HfpSlc-${device.address}")) {
                serveHfp(socket, "$profile poke to ${device.address}", profile, initiate = true, closeWhenDone = false)
            }
            try {
                delay(holdMs)
            } finally {
                slc.cancel()
            }
        }
    }

    /**
     * Wakes up the phone by attempting a brief connection to an HFP/HSP profile, signaling it
     * to start looking for the head unit. Retried every 15s (matching the retry cadence of both
     * nisargjhaveri/WirelessAndroidAutoDongle and mossyhub/openautolink) until a real handshake
     * starts or another session (USB/etc.) takes over, instead of giving up after a single pass.
     *
     * Never runs while a handoff is settling: AapService re-invokes this on every credential
     * re-delivery, and the phone *joining our group* is itself a P2P connection change, hence a
     * re-delivery. That put a real RFCOMM connect() in the middle of the phone's DHCP exchange.
     */
    fun triggerPoke() {
        moduleCarrier?.let { it.requestWake(); return } // CarLink: the module wakes the phone, not this radio
        if (isHandoffSettling()) {
            // Info, not debug: this line is the evidence the suppression is working, and reporter
            // logs default to INFO.
            AppLog.i("NativeAA: Handoff still settling — not starting a poke that would compete with the phone's WiFi association.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                AppLog.w("NativeAA: Missing BLUETOOTH_CONNECT. Cannot triggerPoke.")
                return
            }
        }
        val adapter = BluetoothHelper.getBluetoothAdapter(context) ?: return

        // Named pokeKey, not credentials: the coroutine below tests the *property* for readiness, and
        // a local called credentials would shadow it with a Triple that is never null.
        val snapshot = credentials
        val pokeKey = Triple(snapshot?.ssid ?: "", snapshot?.ip ?: "", snapshot?.bssid ?: "")
        // CarLink (upstream b5617bd5): credentials arriving under a loop that started without them
        // no longer restart it; the loop re-reads them every pass.
        if (!PokeLoopPolicy.shouldRestartLoop(pokeJob?.isActive == true, lastPokeTriggerCredentials, pokeKey)) {
            AppLog.d("NativeAA: a wake poke is already running for these credentials - not restarting it.")
            lastPokeTriggerCredentials = pokeKey
            return
        }
        lastPokeTriggerCredentials = pokeKey

        pokeJob?.cancel()
        pokeJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Wakeup")) {
            // CarLink (upstream b5617bd5): the listeners are what the woken phone dials back on, so
            // wait for them rather than for a fixed two seconds that was only a guess at the same.
            var waitedMs = 0L
            while (!this@NativeAaHandshakeManager.isActive() && waitedMs < PokeLoopPolicy.LISTENERS_READY_WAIT_MS && isActive) {
                delay(PokeLoopPolicy.LISTENERS_READY_POLL_MS)
                waitedMs += PokeLoopPolicy.LISTENERS_READY_POLL_MS
            }
            AppLog.d("NativeAA: wake poke starting (listeners ready after ${waitedMs}ms).")

            while (isRunning && isActive) {
                val settling = isHandoffSettling()
                val handshaking = isHandshakeInFlight()
                val sessionUp = commManager.isConnected ||
                    commManager.connectionState.value is CommManager.ConnectionState.Connecting
                if (!NativeHandoffPolicy.shouldPoke(settling, handshaking, sessionUp)) {
                    AppLog.i(
                        "NativeAA: Stopping poke retry loop " +
                            "(settling=$settling, handshake=$handshaking, session=$sessionUp)."
                    )
                    break
                }

                val lastMacs = settings.autoStartBluetoothDeviceMacs
                val devicesToPoke = if (lastMacs.isNotEmpty()) {
                    // Two questions, two answers. Skipping a poke is retried seconds later;
                    // forgetting a MAC is permanent, so it needs evidence the device is really gone
                    // rather than an adapter that happened to be off. Both rules are in the policy.
                    val bonded = mutableListOf<BluetoothDevice>()
                    val staleMacs = mutableSetOf<String>()
                    lastMacs.forEach { mac ->
                        val reading = bondReadingFor(adapter, mac)
                        if (BluetoothWakePolicy.mayPoke(reading)) {
                            try { bonded.add(adapter.getRemoteDevice(mac)) } catch (e: Exception) {}
                        }
                        if (BluetoothWakePolicy.shouldForget(reading)) staleMacs.add(mac)
                    }
                    if (staleMacs.isNotEmpty()) {
                        AppLog.w("NativeAA: Dropping Auto Start BT MAC(s) no longer paired: $staleMacs")
                        val remaining = lastMacs - staleMacs
                        settings.autoStartBluetoothDeviceMacs = remaining
                        com.andrerinas.openheadunit.utils.Settings.syncAutoStartBtMacsToDeviceStorage(context, remaining)
                    }
                    bonded
                } else {
                    AppLog.w("NativeAA: No 'Auto Start BT Device' selected in settings. Poking all paired devices as fallback...")
                    adapter.bondedDevices.toList()
                }

                if (devicesToPoke.isEmpty()) {
                    AppLog.w("NativeAA: No paired Bluetooth devices found to poke.")
                    return@launch
                }

                for (device in devicesToPoke) {
                    if (!isRunning || !isActive || isHandshakeInFlight() || isHandoffSettling()) break
                    if (commManager.isConnected) {
                        AppLog.i("NativeAA: USB/other session became active mid-poke. Stopping poke loop.")
                        break
                    }

                    // Pre-flight: Ensure WiFi credentials (SSID/IP) are ready before connecting RFCOMM to phone.
                    // If RFCOMM connects before WiFi credentials exist, the phone times out after 10s waiting for WifiStartRequest.
                    if (credentials == null) {
                        AppLog.i("NativeAA: WiFi credentials not ready before poke. Requesting WiFi refresh...")
                        (context as? AapService)?.triggerWifiDirectRefresh()
                        var waitedMs = 0
                        while (credentials == null && waitedMs < 4000 && isRunning && isActive) {
                            delay(200)
                            waitedMs += 200
                        }
                    }

                    AppLog.i("NativeAA: Attempting active poke to device: ${device.name} (${device.address})...")
                    pokeDevice(device, holdMs = 15000)
                }

                // [BUG_FIX] Say out loud that the phone answers but never calls back. Untold, that
                // unit's log is indistinguishable from a healthy one waiting for the user, which is
                // what hid the real cause — Android Auto bound to the head unit's own OEM Bluetooth
                // module, still advertising the AA service record after its OEM app stopped
                // answering. Warning, not info, so it survives a log exported at the default
                // level.
                if (NativeHandoffPolicy.shouldWarnPhoneNeverCallsBack(
                        pokesSinceLastAccept, everAcceptedAaConnection
                    )
                ) {
                    com.andrerinas.openheadunit.HostIntegration.engineEvent(context, // CarLink
                        com.andrerinas.openheadunit.HostIntegration.EngineEvent.POKE_ANSWERED_NO_ACCEPT,
                        "pokes=$pokesSinceLastAccept")
                    AppLog.w(
                        "NativeAA: The phone has answered $pokesSinceLastAccept wake pokes but has " +
                            "never opened the Android Auto channel on radio [$localRadioName]. Its " +
                            "Android Auto is most likely bound to a different Bluetooth device that " +
                            "also advertises the Android Auto service — typically this head unit's " +
                            "own OEM/factory Bluetooth module (a second name alongside this one in " +
                            "the phone's paired list), or another car. Remove that device from the " +
                            "phone's Bluetooth paired list and retry. If this phone has never " +
                            "projected wirelessly to any head unit, check that it supports wireless " +
                            "Android Auto first."
                    )
                }

                delay(15000) // retry cadence, matches both reference implementations' 15-20s interval
            }
        }
    }

    /**
     * Start a manual poke (wakeup) for a specific Bluetooth device.
     */
    fun manualPoke(address: String) {
        moduleCarrier?.let { resetHandshakeBackoff(); it.requestWake(); return } // CarLink: as triggerPoke
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED) {
                AppLog.w("NativeAA: Missing BLUETOOTH_CONNECT. Cannot manualPoke.")
                return
            }
        }
        val adapter = BluetoothHelper.getBluetoothAdapter(context) ?: return
        try {
            val device = adapter.getRemoteDevice(address)
            AppLog.i("NativeAA: Manual poke requested for ${device.name} ($address)")
            // The user asking to try again is the way out of a handshake backoff — it is the only
            // gesture the UI offers, and it means they want another attempt whatever we concluded.
            resetHandshakeBackoff()
            
            pokeJob?.cancel()
            // CarLink (upstream PR #958): the manual job takes the loop's slot, so forget what the
            // loop last poked for; the credential delivery then starts the retry loop in its place.
            lastPokeTriggerCredentials = null
            pokeJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-ManualWakeup")) {
                // Pre-flight: Ensure WiFi credentials (SSID/IP) are ready before connecting RFCOMM to phone.
                if (credentials == null) {
                    AppLog.i("NativeAA: WiFi credentials not ready before manual poke. Requesting WiFi refresh...")
                    (context as? AapService)?.triggerWifiDirectRefresh()
                    var waitedMs = 0
                    while (credentials == null && waitedMs < 4000 && isRunning && isActive) {
                        delay(200)
                        waitedMs += 200
                    }
                    AppLog.i("NativeAA: Pre-poke credential wait completed. SSID=${credentials?.ssid}, IP=${credentials?.ip} (waited ${waitedMs}ms)")
                }

                AppLog.i("NativeAA: Attempting manual poke to ${device.name}...")
                pokeDevice(device, holdMs = 20000)
                AppLog.i("NativeAA: Manual poke to ${device.name} finished.")
            }
        } catch (e: Exception) {
            AppLog.e("NativeAA: Manual poke error", e)
        }
    }

    /**
     * Drop an incoming Android Auto connection instead of serving it, once too many handshakes in
     * a row have timed out waiting for the phone's Type 2. Returns true if the socket was refused.
     *
     * Each timed-out handshake strands a thread that cannot be reclaimed (see
     * [NativeHandoffPolicy.shouldServeHandshake]), so past the limit the only useful thing to do
     * is stop starting new ones. The phone will keep reconnecting; closing immediately costs it
     * nothing beyond the retry it was going to make anyway.
     */
    private fun refuseWhileBackedOff(socket: BluetoothSocket): Boolean {
        if (NativeHandoffPolicy.shouldServeHandshake(consecutiveHandshakeFailures)) return false
        if (!loggedHandshakeBackoff) {
            loggedHandshakeBackoff = true
            com.andrerinas.openheadunit.HostIntegration.engineEvent(context, // CarLink
                com.andrerinas.openheadunit.HostIntegration.EngineEvent.HANDSHAKE_BACKOFF,
                "failures=$consecutiveHandshakeFailures")
            AppLog.w(
                "NativeAA: $consecutiveHandshakeFailures handshakes in a row ended with no answer from the phone, " +
                    "so this connection is being dropped instead of served. Each attempt costs a thread that " +
                    "cannot be recovered, and this head unit's Bluetooth is not delivering our messages. " +
                    "Use the manual poke button, or switch Android Auto mode off and on, to try again."
            )
        } else {
            AppLog.d("NativeAA: Dropping Android Auto connection — still backed off after $consecutiveHandshakeFailures failed handshakes.")
        }
        try { socket.close() } catch (e: Exception) {}
        return true
    }

    /** Clears the handshake backoff. Called wherever the user or the system asks for a fresh try. */
    private fun resetHandshakeBackoff() {
        consecutiveHandshakeFailures = 0
        loggedHandshakeBackoff = false
    }

    /**
     * Runs [block] only while [socket] is still the handshake this manager is serving.
     *
     * Every write a handshake makes to shared manager state goes through this. Losing ownership
     * does not stop a superseded handshake — where close() cannot interrupt a pending read it runs
     * on for minutes — and its late writes would clear the live session's settling stamp, cancel
     * its poke, close listeners it still needs, or wipe a backoff it had legitimately earned.
     */
    private inline fun ifOwner(socket: HandshakeLink, block: () -> Unit) { // CarLink: HandshakeLink seam
        if (activeHandshakeSocket === socket) block()
    }

    // CarLink: HandshakeLink seam — [socket] is any phone link: this unit's RFCOMM socket or an external module.
    private suspend fun handleHandshake(socket: HandshakeLink, localRadio: String? = null) = withContext(Dispatchers.IO) {
        // The phone reached us. Recorded here rather than at either accept site so both the
        // primary and the secondary-radio loops are covered by one statement.
        everAcceptedAaConnection = true
        pokesSinceLastAccept = 0

        // The wake poke is deliberately left running here. It used to be cancelled on entry, on
        // the reasoning that a real AA_UUID connection means the poke has done its job and is now
        // just competing for radio time — but cancelling it closes the HFP/HSP socket, and a
        // phone-side Gearhead log shows the phone reacting within milliseconds:
        //   GH.BtConnectionTracker: profile connection removed
        //   GH.CurrentCarTracker:   current car bluetooth connection is lost / is gone
        //   ...WIRELESS_SETUP_CAR_BLUETOOTH_DISAPPEAR
        // and then, when its own first-message timer expires 12 s later, refusing to retry:
        //   GH.WIRELESS.SETUP: WiFi Projection Protocol cannot start as HU is not present.
        // Real head units hold the profile link across the exchange, so hold it too, until the
        // credentials are actually delivered (see the Type 3 branch) or this handshake ends.

        // The listener stays open across the settling window, so the phone can reconnect over
        // Bluetooth while an earlier handoff is still settling. That reconnect means the earlier
        // one failed: retire it rather than serving both from the same manager state.
        val previousSocket = activeHandshakeSocket
        val previousJob = activeHandshakeJob
        // Ownership is claimed *before* the previous session is torn down, not after: cancelling
        // it makes its finally block run on another thread at a moment we do not control, and the
        // only thing keeping that block off this handshake's state is the ifOwner fence. Take
        // ownership first and the fence is already closed when the old one unwinds.
        activeHandshakeSocket = socket
        activeHandshakeJob = coroutineContext[Job]
        // Stamped after claiming ownership above, so a superseded handshake's cleanup — which
        // only fires when it still owns activeHandshakeSocket — can't wipe this one's stamp.
        handshakeStartedAt = SystemClock.elapsedRealtime()
        if (previousSocket != null && previousSocket !== socket) {
            AppLog.i("NativeAA: A new handshake arrived while one was still settling — closing the previous session.")
            handoffSettlingSince = 0L
            // Cancel *and* close, in that order: see activeHandshakeJob. Cancelling first means
            // the old coroutine cannot mistake the close for a phone-side drop and act on it.
            previousJob?.cancel()
            try { previousSocket.close() } catch (_: Exception) {}
        }
        // Whether this handshake put anything on the wire at all, and whether the phone answered
        // any of it. Together with abortedLocally they decide, once in the fenced finally below,
        // whether this attempt counts against consecutiveHandshakeFailures.
        var spokeToPhone = false
        var abortedLocally = false
        // Captured once, so a settings change mid-exchange cannot split it across two rulesets.
        val transport = NativeTransport.fromSetting(settings.nativeApTransport)
        // CarLink (PR #1029): hold this Bluetooth channel for the whole session once it lands.
        val session = WppHandshakeSession(settings.nativeWifiVersionExchange, holdsChannel = true)
        // Everything the phone sends, in order. Replaces the single bounded read this used to do:
        // types 6 and 7 arrive *after* the credentials go out, so a one-shot read could never see
        // them, and the phone is free to interject a ping at any point in between.
        val inbound = Channel<ProtobufMessage>(Channel.UNLIMITED)
        var readerJob: Job? = null
        try {
            val peerName = socket.peerName // CarLink: HandshakeLink seam
            val peerAddress = socket.peerAddress
            AppLog.i("NativeAA: Handling handshake for $peerName ($peerAddress) on local radio [${localRadio ?: "?"}]")

            if (commManager.isConnected ||
                commManager.connectionState.value is CommManager.ConnectionState.Connecting) {
                AppLog.i("NativeAA: USB/other session already active. Aborting BT handshake so phone does not start a parallel wireless attempt.")
                abortedLocally = true
                try { socket.close() } catch (_: Exception) {}
                return@withContext
            }
            com.andrerinas.openheadunit.HostIntegration.engineEvent(context, // CarLink
                com.andrerinas.openheadunit.HostIntegration.EngineEvent.BT_CHANNEL_ACCEPTED, "address=$peerAddress")

            val macs = settings.autoStartBluetoothDeviceMacs
            // Never add back a phone the user took off the list (#35).
            if (socket.persistPeerForAutoStart && peerAddress != null && !macs.contains(peerAddress) && // CarLink
                peerAddress !in settings.autoStartBluetoothDeviceMacsRemoved) {
                AppLog.i("NativeAA: Saving $peerAddress ($peerName) to the list of auto-start devices.")
                val newMacs = macs + peerAddress
                settings.autoStartBluetoothDeviceMacs = newMacs
                settings.autoStartBluetoothDeviceName = peerName ?: "Unknown Device"
                com.andrerinas.openheadunit.utils.Settings.syncAutoStartBtMacsToDeviceStorage(context, newMacs)
            }

            val input = DataInputStream(socket.input) // CarLink: HandshakeLink seam
            val output = socket.output

            // [BUG_FIX] There is no BluetoothSocket.setSoTimeout(), and the old workaround —
            // close the socket to unblock readFully() — only works where close() interrupts a
            // pending read. Where it does not, the handshake never unwinds and takes the wake poke
            // and the P2P join watchdog down with it for the rest of the session. Time out the
            // *wait* instead: read on a coroutine of its own and take messages from a channel,
            // which resumes on schedule whether or not the read ever returns. The reader itself is
            // still unreclaimable on such a stack; consecutiveHandshakeFailures bounds that.
            readerJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Reader-$peerAddress")) {
                try {
                    while (isActive) inbound.send(readProtobuf(input))
                } catch (e: Exception) {
                    AppLog.d("NativeAA: Bluetooth reader ended: ${e.message}")
                } finally {
                    inbound.close()
                }
            }

            // --- everything below drives the WppHandshakeSession state machine ---

            var stageEnteredAt = SystemClock.elapsedRealtime()
            var readerClosed = false
            // Filled in once the credentials resolve, before any action can need them.
            var credSsid = ""
            var credPsk = ""
            var credIp = ""
            var credBssid = ""
            // Whether the credentials went out with no BSSID at all. A join failure means something
            // different when they did — see the Fail action below.
            var bssidOmitted = false
            // CarLink (PR #1029): the held channel's bookkeeping, for its periodic summary and its
            // release line.
            var holdingSince = 0L
            var pingsWhileHolding = 0
            var lastHoldSummaryAt = 0L
            // CarLink (ZbtRetransmitPolicy port): when the opening message last went out.
            var lastOpenerSentAt = 0L

            suspend fun runAction(action: WppAction, source: ProtobufMessage?) {
                when (action) {
                    WppAction.SendVersionRequest -> {
                        AppLog.i("NativeAA: [TX] Sending WifiVersionRequest (Type 4) v${WppHandshakeSession.WPP_VERSION_MAJOR}.${WppHandshakeSession.WPP_VERSION_MINOR}")
                        sendWifiVersionRequest(output)
                        lastOpenerSentAt = SystemClock.elapsedRealtime() // CarLink
                        spokeToPhone = true
                    }
                    WppAction.SendStartRequest -> {
                        AppLog.i("NativeAA: [TX] Sending WifiStartRequest (Type 1)")
                        sendWifiStartRequest(output, credIp, 5288)
                        com.andrerinas.openheadunit.aap.WirelessTiming.mark(com.andrerinas.openheadunit.aap.WirelessTimeline.Phase.WIFI_START_REQUEST) // CarLink
                        lastOpenerSentAt = SystemClock.elapsedRealtime() // CarLink
                        spokeToPhone = true
                    }
                    WppAction.SendInfoResponse -> {
                        AppLog.i("NativeAA: Phone ready for WiFi association. Delivering credentials...")
                        AppLog.i("NativeAA: [TX] Sending WifiInfoResponse (Type 3) with full credentials in 1000ms...")
                        delay(1000) // [FIX] Increased delay to give phone more processing time
                        // [BUG_FIX] A phone still joined to our network from the last session
                        // does not wait for these: it opens the projection session right after
                        // WifiStartRequest, inside the pause above. Credentials that arrive after
                        // that make it join again and open a second session, which replaces the
                        // first one. Each replaced session ends the next one the same way, so the
                        // car reconnects in a loop for as long as the phone stays on our network.
                        if (commManager.isConnected ||
                            commManager.connectionState.value is CommManager.ConnectionState.Connecting) {
                            AppLog.i("NativeAA: The phone opened its session before the credentials went out; not sending them.")
                        } else {
                            sendWifiSecurityResponse(output, credSsid, credPsk, credBssid, transport)
                            // CarLink: only when they went out (DiAuto 0.3.12 skips them for a
                            // phone that already opened its session).
                            com.andrerinas.openheadunit.HostIntegration.engineEvent(context,
                                com.andrerinas.openheadunit.HostIntegration.EngineEvent.CREDENTIALS_SENT, "transport=$transport")
                        }
                        AppLog.i("NativeAA: Handshake completed successfully on Bluetooth side.")
                        ifOwner(socket) {
                            // The exchange is done; the phone's work is not — it still has to
                            // associate, run WPS and get a DHCP lease. See isHandoffSettling().
                            handshakeStartedAt = 0L
                            handoffSettlingSince = SystemClock.elapsedRealtime()
                            // Nothing left for the poke to wake, and it holds an RFCOMM channel on
                            // the radio the phone is about to associate over — Bluetooth work
                            // across the join strands it on "Obtaining IP".
                            pokeJob?.cancel()
                        }
                    }
                    WppAction.SendPingResponse -> {
                        // Echo the request's own bytes: whatever the phone put in a keepalive,
                        // handing it straight back cannot fail on a schema guess.
                        // CarLink (PR #1029): counted, not logged, while holding (one a second).
                        if (session.stage == WppStage.HOLDING) pingsWhileHolding++
                        else AppLog.d("NativeAA: [TX] Echoing WifiPingResponse (Type 9)")
                        sendProtobuf(output, source?.payload ?: ByteArray(0), WppMessageType.PING_RESPONSE,
                            quiet = session.stage == WppStage.HOLDING)
                    }
                    WppAction.ExtendSettle -> {
                        AppLog.i("NativeAA: Phone reports it is still joining — extending the settling window.")
                        // Re-stamp rather than only extending our own deadline: isHandoffSettling()
                        // is what keeps the poke off the radio during the join, and it measures
                        // from this stamp. The session caps the total.
                        ifOwner(socket) { handoffSettlingSince = SystemClock.elapsedRealtime() }
                    }
                    WppAction.CompleteSuccess -> {
                        // CarLink (PR #1029): the channel is held, not released.
                        AppLog.i("NativeAA: WiFi session landed. Holding the Bluetooth channel for the session and answering the phone's pings, as a head unit does.")
                        holdingSince = SystemClock.elapsedRealtime()
                        lastHoldSummaryAt = holdingSince
                        ifOwner(socket) {
                            handshakeStartedAt = 0L
                            handoffSettlingSince = 0L
                            // The held channel is the only one this session needs. See closeAaListeners().
                            closeAaListeners()
                        }
                    }
                    is WppAction.Release -> { // CarLink (PR #1029)
                        val heldS = (SystemClock.elapsedRealtime() - holdingSince) / 1000
                        if (action.peerClosed) {
                            AppLog.w("NativeAA: the phone closed the held Bluetooth channel after ${heldS}s and $pingsWhileHolding pings; the Android Auto listeners reopen when this session ends.")
                        } else {
                            AppLog.i("NativeAA: the session ended; releasing the held Bluetooth channel after ${heldS}s and $pingsWhileHolding pings.")
                        }
                    }
                    is WppAction.Fail -> {
                        AppLog.w("NativeAA: Handshake failed — ${action.reason}.")
                        if (action.wifiJoinFailed && transport == NativeTransport.HOTSPOT) {
                            ifOwner(socket) {
                                // Refresh before ResumePoke, so the next handshake cannot reuse
                                // an IP/BSSID selected while the car hotspot was still coming up.
                                context.refreshHotspotCredentialsAfterJoinFailure()
                            }
                        }
                        // Measured against a current Gearhead: it joins with a WifiNetworkSpecifier,
                        // which matches SSID *and* BSSID under a full ff:ff:ff:ff:ff:ff mask, and
                        // refuses credentials carrying no BSSID outright. So on this route a join
                        // failure right after we omitted the field is that omission, not the
                        // network — and the retry will fail the same way until an address exists.
                        if (bssidOmitted) {
                            AppLog.e(
                                "NativeAA: These credentials carried no BSSID, which this phone may " +
                                    "have refused for that reason alone. Read the access point's MAC " +
                                    "and set it as the static BSSID in Advanced settings."
                            )
                        }
                    }
                    WppAction.ResumePoke -> ifOwner(socket) {
                        // Clear the settling stamp first: triggerPoke() refuses to start while a
                        // handoff is settling, which is the whole point of that guard.
                        handoffSettlingSince = 0L
                        triggerPoke()
                    }
                }
            }

            suspend fun feed(event: WppEvent, source: ProtobufMessage? = null) {
                val before = session.stage
                val actions = session.on(event)
                for (action in actions) runAction(action, source)
                if (session.stage != before) {
                    // Stamped after the actions, so the 1 s pause before Type 3 is not charged to
                    // the settling window it opens.
                    stageEnteredAt = SystemClock.elapsedRealtime()
                    AppLog.d("NativeAA: Handshake stage $before -> ${session.stage}")
                }
            }

            /** Waits up to [budgetMs] for one message, then services timers. */
            suspend fun tick(budgetMs: Long) {
                if (readerClosed) {
                    delay(budgetMs)
                } else {
                    // Polled with tryReceive() rather than awaited with a timeout around
                    // receive(): cancelling a suspended receive can consume the element it was
                    // about to hand over, and losing the phone's Type 2 that way would stall the
                    // handshake until its stage deadline for no visible reason. tryReceive()
                    // cannot lose anything; 25 ms of latency costs nothing here.
                    val deadline = SystemClock.elapsedRealtime() + budgetMs
                    var msg: ProtobufMessage? = null
                    while (true) {
                        val result = inbound.tryReceive()
                        val received = result.getOrNull()
                        if (received != null) { msg = received; break }
                        if (result.isClosed) {
                            readerClosed = true
                            // Not a failure in itself: aa-proxy-rs treats a reset mid-bootstrap as
                            // retriable, and a phone that has our credentials may legitimately
                            // drop Bluetooth while it associates. Stage deadlines still bound us.
                            AppLog.d("NativeAA: Bluetooth read channel closed by the phone or the socket.")
                            break
                        }
                        if (SystemClock.elapsedRealtime() >= deadline) break
                        delay(25)
                    }
                    if (msg != null) {
                        // CarLink: the held channel's once-a-second pings at debug (PR #1029 port).
                        if (session.stage == WppStage.HOLDING && msg.type == WppMessageType.PING_REQUEST) {
                            AppLog.d("NativeAA: [RX] Received Type ${msg.type} (Payload size: ${msg.payload.size})")
                        } else {
                            AppLog.i("NativeAA: [RX] Received Type ${msg.type} (Payload size: ${msg.payload.size})")
                        }
                        logReceivedDetail(msg)
                        // The phone answered, so the channel carries data in at least one
                        // direction. Whatever the type turns out to be, this was not a silent unit.
                        ifOwner(socket) { resetHandshakeBackoff() }
                        val status = parseStatus(msg)
                        if (status != null && session.stage != WppStage.HOLDING &&
                            (msg.type == WppMessageType.CONNECT_STATUS || msg.type == WppMessageType.START_RESPONSE)) {
                            // CarLink: the phone's own verdict on the network it was handed.
                            com.andrerinas.openheadunit.HostIntegration.engineEvent(context,
                                com.andrerinas.openheadunit.HostIntegration.EngineEvent.PHONE_WIFI_STATUS,
                                "type=${msg.type};status=$status;stage=${session.stage}")
                        }
                        feed(WppEvent.MessageReceived(msg.type, status), msg)
                        if (session.isTerminal()) return
                    }
                }
                if (session.stage == WppStage.HOLDING) { // CarLink (PR #1029)
                    val now = SystemClock.elapsedRealtime()
                    val sessionAlive = commManager.isConnected ||
                        commManager.connectionState.value is CommManager.ConnectionState.Connecting
                    when {
                        readerClosed -> feed(WppEvent.PeerClosed)
                        !sessionAlive -> feed(WppEvent.SessionEnded)
                        now - lastHoldSummaryAt >= HOLD_SUMMARY_INTERVAL_MS -> {
                            lastHoldSummaryAt = now
                            AppLog.i("NativeAA: [HOLD] Bluetooth channel held ${(now - holdingSince) / 1000}s, $pingsWhileHolding pings answered.")
                        }
                    }
                    return
                }
                if (session.stage == WppStage.SETTLING &&
                    (commManager.isConnected ||
                        commManager.connectionState.value is CommManager.ConnectionState.Connecting)) {
                    feed(WppEvent.TcpSessionUp)
                    return
                }
                val limit = session.currentStageTimeoutMs() ?: return
                if (SystemClock.elapsedRealtime() - stageEnteredAt < limit) return
                if (session.stage == WppStage.SETTLING) {
                    // The handoff never completed, so there is no session for a reconnect to
                    // collide with — the reconnect storm closeAaListeners() guards against can't
                    // happen here. Leave the listener up so the phone's own retry can be accepted
                    // (start() early-returns while isRunning, so a close here would strand us
                    // until AapService stopped and restarted the manager), and restart the poke,
                    // which was cancelled once the credentials went out.
                    AppLog.w("NativeAA: No WiFi session within ${limit / 1000}s of delivering credentials — keeping the AA listener open and resuming the wake poke.")
                    com.andrerinas.openheadunit.HostIntegration.engineEvent(context, // CarLink
                        com.andrerinas.openheadunit.HostIntegration.EngineEvent.SETTLE_TIMEOUT, "seconds=${limit / 1000}")
                    feed(WppEvent.SettleTimeout)
                } else {
                    feed(WppEvent.StageTimeout)
                }
            }

            // Some Bluetooth stacks report the RFCOMM socket "connected" slightly before the
            // underlying channel is actually ready to carry data - writing immediately can be
            // silently dropped on such hardware (a known real class of RFCOMM race: see the
            // kernel's "Move pending packets from RFCOMM socket to TTY" fix for the same
            // symptom on the HFP profile). A short delay costs nothing against either side's
            // timeout budget (phone's own first-message timeout is ~12s, ours is 15s) but gives
            // a flaky chip a moment to settle before the one message that matters most.
            delay(300)

            // The version exchange opens the conversation, *before* the wait for credentials
            // rather than after it. The phone starts its own ~12 s first-message timer when it
            // opens this channel, and on a cold P2P group the credential wait alone can outlast
            // that — a head unit that has said nothing by then is a head unit that "is not
            // present" as far as Gearhead is concerned. Saying something first costs nothing and
            // buys the whole bring-up window.
            feed(WppEvent.SocketReady)

            // CarLink (ZbtRetransmitPolicy port): on the ZBT module the daemon owns the accept, so a
            // message sent before the phone's channel exists is dropped unseen; repeat the opener
            // until anything answers. Never on this unit's own radio (retransmitsWhileSilent false).
            fun resendOpener(send: () -> Unit, what: String) {
                val now = SystemClock.elapsedRealtime()
                if (!com.andrerinas.openheadunit.connection.zbt.ZbtRetransmitPolicy.shouldResend(
                        socket.retransmitsWhileSilent, session.messagesReceived > 0, lastOpenerSentAt, now)) return
                AppLog.i("NativeAA: [TX] repeating $what - the phone has answered nothing yet.")
                lastOpenerSentAt = now
                runCatching { send() }.onFailure { AppLog.w("NativeAA: [TX] the repeat of $what failed: ${it.message}") }
            }

            AppLog.i("NativeAA: Phone connected. Current credentials state: SSID=${credentials?.ssid ?: "<null>"}, IP=${credentials?.ip ?: "<null>"}")
            AppLog.i("NativeAA: Waiting for WiFi credentials to be ready (Max ${CREDENTIALS_WAIT_MS / 1000}s)...")

            // Wait for credentials (P2P group / hotspot bring-up can be slow), servicing the
            // phone's messages while we do: an early Type 2 or Type 5 lands here, not in the loop
            // below.
            val credentialsDeadline = SystemClock.elapsedRealtime() + CREDENTIALS_WAIT_MS
            var lastRefreshAt = SystemClock.elapsedRealtime()
            var lastProgressLogAt = SystemClock.elapsedRealtime()
            while (credentials == null && isRunning && isActive &&
                !session.isTerminal() && SystemClock.elapsedRealtime() < credentialsDeadline) {
                val now = SystemClock.elapsedRealtime()
                val waitedS = (CREDENTIALS_WAIT_MS - (credentialsDeadline - now)) / 1000
                if (now - lastRefreshAt >= 10_000) {
                    lastRefreshAt = now
                    AppLog.w("NativeAA: Still waiting for credentials after ${waitedS}s. Requesting WiFi refresh...")
                    context.triggerWifiDirectRefresh()
                } else if (now - lastProgressLogAt >= 5_000) {
                    lastProgressLogAt = now
                    AppLog.d("NativeAA: Still waiting... credentials=${credentials != null} (${waitedS}s)")
                }
                resendOpener({ sendWifiVersionRequest(output) }, "WifiVersionRequest (Type 4)") // CarLink
                tick(500)
            }

            // Read once. The check and the use used to be separate reads of four separate fields,
            // so an invalidate between them threw on the `!!` and reported "Handshake error: null".
            val snapshot = credentials
            if (snapshot == null) {
                AppLog.e("NativeAA: Handshake failed - No WiFi credentials available after ${CREDENTIALS_WAIT_MS / 1000}s wait.")
                abortedLocally = true
                feed(WppEvent.CredentialsUnavailable)
                return@withContext
            }

            credIp = snapshot.ip
            credSsid = snapshot.ssid
            credPsk = snapshot.psk
            credBssid = snapshot.bssid.uppercase()

            // [FIX] Ensure BSSID is uppercase and not zeroed if possible
            if (!NativeCredentialsPolicy.isUsableBssid(credBssid)) {
                when (NativeCredentialsPolicy.onUnusableBssid(transport)) {
                    UnusableBssidAction.ABORT -> {
                        AppLog.e("NativeAA: BSSID is still masked/empty ($credBssid) at Type 3 time — phone WILL reject these credentials. Aborting handshake. PLEASE CHECK IF LOCATION (GPS) IS ENABLED ON THIS DEVICE!")
                        // Location is the usual cause and the one worth naming first, but it is not
                        // the only one: where this head unit is an ordinary phone rather than
                        // purpose-built hardware, every source in the chain is blocked by permission
                        // and no setting will unblock them. Say so, or the log sends the reader back
                        // to a location toggle that is already on.
                        AppLog.e("NativeAA: If location is already on, this device cannot read its own WiFi Direct MAC at all. Read it from the system (P2P device address) and set it as the static BSSID in Advanced settings.")
                        com.andrerinas.openheadunit.HostIntegration.engineEvent(context, // CarLink
                            com.andrerinas.openheadunit.HostIntegration.EngineEvent.BSSID_UNRESOLVED,
                            "location=${if (locationEnabled()) "on" else "off"}")
                        // Triggering a P2P refresh so the next attempt has a valid BSSID
                        context.triggerWifiDirectRefresh()
                        // Not fed to the session as CredentialsUnavailable: its failure reason
                        // would say the credentials never arrived, when in fact they arrived
                        // unusable, and the line above is the one the reporter needs to act on.
                        abortedLocally = true
                        return@withContext
                    }
                    UnusableBssidAction.SEND_WITH_EMPTY_BSSID -> {
                        // Sending is worth a try rather than expected to work — no implementation
                        // ships without a real BSSID, see NativeCredentialsPolicy. The point is that
                        // a refusal is a message we can explain; this line is the first to look at
                        // when one arrives.
                        AppLog.w("NativeAA: No usable BSSID for this access point — sending the credentials without one, which most phones refuse. Set a BSSID by hand in Advanced settings if the phone does not join.")
                        credBssid = ""
                        bssidOmitted = true
                    }
                }
            }

            // The port the credentials point at must be bound before they go out. The phone's next
            // move after Type 3 is to join the network and dial it; if nothing is listening it
            // gets a refusal, and the log reads as a perfect handshake followed by nothing at all.
            // Short wait rather than none: start() binds the port at service start, so being here
            // with it unbound means a genuine failure, not a race — but a session torn down and
            // rebuilt a moment ago can still be releasing it.
            if (!awaitWirelessServerListening(PORT_WAIT_MS)) {
                // Ask for a repair before giving up. A server that failed to bind once used to stay
                // dead for the life of the mode, because the only thing that rebuilt it was a full
                // mode re-initialisation - so this abort repeated every few seconds, forever, with
                // the phone woken each time and told nothing.
                if (!context.ensureWirelessServerListening("the Bluetooth handshake", PORT_ENSURE_MS)) {
                    AppLog.e("NativeAA: Handshake aborted — nothing is listening on port 5288 after ${PORT_WAIT_MS / 1000}s, and starting it here did not work either, so the phone would join the network and find no head unit. Restart the app if this persists.")
                    abortedLocally = true
                    feed(WppEvent.CredentialsUnavailable)
                    return@withContext
                }
                AppLog.i("NativeAA: port 5288 was not bound, and is now. Carrying on with the handshake.")
            }

            AppLog.i("NativeAA: Starting Handshake Exchange:")
            AppLog.i("  > Target SSID: $credSsid")
            AppLog.i("  > Target IP:   $credIp:5288")
            AppLog.i("  > BSSID:       $credBssid")

            feed(WppEvent.CredentialsReady)

            // Runs until the session finishes: the projection session lands, the phone reports
            // the join failed (Type 6), or a stage deadline expires.
            //
            // [BUG_FIX] The settle was once a flat delay(3000) before closing Bluetooth — a race,
            // not a grace period, since the phone needs however long it needs. Association has
            // been measured at 21 s on hardware where the 3 s close killed it dead. Wait for the
            // session, and where the phone reports its own progress, let it.
            while (isRunning && isActive && !session.isTerminal()) {
                if (session.stage == WppStage.AWAIT_INFO_REQUEST) { // CarLink
                    resendOpener({ sendWifiStartRequest(output, credIp, 5288) }, "WifiStartRequest (Type 1)")
                }
                tick(250)
            }

        } catch (e: Exception) {
            AppLog.e("NativeAA: Handshake error: ${e.message}", e)
        } finally {
            // Only clear the stamps if this handshake still owns them — a superseding handshake
            // has already taken over and set its own.
            if (activeHandshakeSocket === socket) {
                activeHandshakeSocket = null
                activeHandshakeJob = null
                handshakeStartedAt = 0L
                handoffSettlingSince = 0L
                // [BUG_FIX] Every silent ending counts, not just the timeout that used to
                // increment inline: a socket error, a swallowed write and a cancellation are one
                // failure from the outside, and each strands an unreclaimable IO thread.
                // Excludes our own pre-exchange aborts (no credentials, masked BSSID, USB already
                // up) — those repeat for as long as location services are off, and backing off
                // would bury the log line saying how to fix it.
                if (spokeToPhone && !abortedLocally && session.messagesReceived == 0) {
                    consecutiveHandshakeFailures++
                }
            }
            // Best effort only, exactly as before: on a stack where close() does not interrupt a
            // pending read this cannot end the reader — a blocking JNI read has no suspension
            // point to cancel at — so its thread is stranded from here on.
            readerJob?.cancel()
            inbound.close()
            try { socket.close() } catch (e: Exception) {}
            AppLog.i("NativeAA: BT Handshake socket closed.")
        }
    }

    /**
     * The status field of a message that carries one, or null when it has none, when the message
     * cannot be parsed, or when the type does not have one.
     *
     * Null is deliberately not a failure: [WppHandshakeSession] only ever treats a non-null,
     * non-zero status as the phone reporting trouble, so a message we fail to decode can never
     * abort a handshake that was going fine.
     */
    private fun parseStatus(msg: ProtobufMessage): Int? = try {
        when (msg.type) {
            WppMessageType.VERSION_RESPONSE ->
                Wireless.WifiVersionResponse.parseFrom(msg.payload).let { if (it.hasStatus()) it.status else null }
            WppMessageType.CONNECT_STATUS ->
                Wireless.WifiConnectStatus.parseFrom(msg.payload).let { if (it.hasStatus()) it.status else null }
            WppMessageType.START_RESPONSE ->
                Wireless.WifiStartResponse.parseFrom(msg.payload).let { if (it.hasStatus()) it.status else null }
            else -> null
        }
    } catch (e: Exception) {
        AppLog.d("NativeAA: Could not parse Type ${msg.type} payload (${msg.payload.size} bytes): ${e.message}")
        null
    }

    /** Says what a received message actually contained, where that is worth having in a log. */
    private fun logReceivedDetail(msg: ProtobufMessage) {
        try {
            when (msg.type) {
                WppMessageType.VERSION_RESPONSE -> {
                    val v = Wireless.WifiVersionResponse.parseFrom(msg.payload)
                    AppLog.i("NativeAA: [RX] WifiVersionResponse v${v.major}.${v.minor} status=${if (v.hasStatus()) v.status else "-"}")
                }
                WppMessageType.CONNECT_STATUS -> {
                    val s = Wireless.WifiConnectStatus.parseFrom(msg.payload)
                    AppLog.i("NativeAA: [RX] WifiConnectStatus status=${if (s.hasStatus()) s.status else "-"} (0 = the phone got onto our network)")
                }
                WppMessageType.START_RESPONSE -> {
                    val r = Wireless.WifiStartResponse.parseFrom(msg.payload)
                    AppLog.i("NativeAA: [RX] WifiStartResponse ip=${r.ipAddress} status=${if (r.hasStatus()) r.status else "-"}")
                }
            }
        } catch (e: Exception) {
            AppLog.d("NativeAA: Type ${msg.type} payload did not parse for logging: ${e.message}")
        }
    }

    private fun sendWifiStartRequest(output: OutputStream, ip: String, port: Int) {
        val request = Wireless.WifiStartRequest.newBuilder()
            .setIpAddress(ip)
            .setPort(port)
            .setStatus(0)
            .build()
        sendProtobuf(output, request.toByteArray(), WppMessageType.START_REQUEST)
    }

    /**
     * Declares our protocol version, opening the modern exchange. Real head units send this first
     * — the OEM ZLink app does — while aa-proxy-rs's own dongle does not, which is why it sits
     * behind [com.andrerinas.openheadunit.utils.Settings.nativeWifiVersionExchange].
     *
     * The version numbers are a guess and known to be one; see [WppHandshakeSession].
     */
    private fun sendWifiVersionRequest(output: OutputStream) {
        val request = Wireless.WifiVersionRequest.newBuilder()
            .setMajor(WppHandshakeSession.WPP_VERSION_MAJOR)
            .setMinor(WppHandshakeSession.WPP_VERSION_MINOR)
            .build()
        sendProtobuf(output, request.toByteArray(), WppMessageType.VERSION_REQUEST)
    }

    /**
     * Sends the credentials.
     *
     * All five fields go out every time, including an empty [bssid] where we have no real address:
     * the schema the other implementations use marks bssid, security_mode and access_point_type
     * `required`, and aa-proxy-rs sets an empty string on the one path where it has no MAC rather
     * than dropping the field. Omitting it risks a strict parser rejecting the whole message, which
     * would surface as silence rather than as the specific refusal an empty one produces.
     *
     * [transport] picks the access-point type: DYNAMIC for a hotspot, matching both reference
     * implementations, and STATIC for a WiFi Direct group as before.
     */
    private fun sendWifiSecurityResponse(
        output: OutputStream,
        ssid: String,
        key: String,
        bssid: String?,
        transport: NativeTransport
    ) {
        val response = Wireless.WifiInfoResponse.newBuilder()
            .setSsid(ssid)
            .setKey(key)
            .setSecurityMode(Wireless.SecurityMode.WPA2_PERSONAL)
            .setAccessPointType(
                if (transport != NativeTransport.WIFI_DIRECT) Wireless.AccessPointType.DYNAMIC
                else Wireless.AccessPointType.STATIC
            )
            .setBssid(bssid.orEmpty())
            .build()
        sendProtobuf(output, response.toByteArray(), WppMessageType.INFO_RESPONSE)
    }

    private fun sendProtobuf(output: OutputStream, data: ByteArray, type: Int, quiet: Boolean = false) {
        output.write(WppFraming.encodeFrame(data, type))
        output.flush()
        // Not "successfully delivered": write() and flush() returned, nothing more. A stack that
        // accepts the write and puts nothing on the air logs every send exactly like this, so the
        // old wording made a dead radio read as a textbook handshake. Proof is the phone's reply.
        // CarLink: [quiet] for the held channel's once-a-second ping answers (PR #1029 port).
        if (quiet) AppLog.d("NativeAA: [TX] Wrote TYPE $type (size ${data.size}) to Bluetooth")
        else AppLog.i("NativeAA: [TX] Wrote TYPE $type (size ${data.size}) to Bluetooth (write() returned; delivery unconfirmed)")
    }

    private fun readProtobuf(input: DataInputStream): ProtobufMessage {
        val header = ByteArray(WppFraming.HEADER_SIZE)
        input.readFully(header)
        val size = WppFraming.decodePayloadSize(header)
        val type = WppFraming.decodeType(header)
        val payload = if (size > 0) {
            val p = ByteArray(size)
            input.readFully(p)
            p
        } else ByteArray(0)
        return ProtobufMessage(type, payload)
    }

    data class ProtobufMessage(val type: Int, val payload: ByteArray)

    /** CarLink: run the handshake over the stereo's external Bluetooth module (ExternalBtTransportPolicy). */
    private fun startOverModule(route: ExternalBtTransportPolicy.Route) {
        isRunning = true
        aaListenersClosedForSession = false
        localRadioName = "external module ($route)"
        AppLog.i("NativeAA: external Bluetooth module route $route — the handshake goes over the stereo's module, not this unit's own radio.")
        val carrier = ExternalBtTransportPolicy.carrier(context, route, ExternalBtTransportPolicy.Hooks(
            serve = { link -> handleHandshake(link, localRadioName) },
            isRunning = { isRunning },
            isFinishedForSession = { aaListenersClosedForSession },
            isSessionConnected = { commManager.isConnected },
            isSettling = { isHandoffSettling() },
            isHandshakeInFlight = { isHandshakeInFlight() },
            mayServeHandshake = { NativeHandoffPolicy.shouldServeHandshake(consecutiveHandshakeFailures) },
            onPhoneEvidence = { resetHandshakeBackoff() },
        )) ?: return
        moduleCarrier = carrier
        carrier.requestWake()
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-ModuleCarrier")) {
            try {
                carrier.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("NativeAA: [$route] carrier stopped unexpectedly: ${e.message}", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        moduleCarrier?.close() // CarLink: unblocks the carrier's pump or reader
        moduleCarrier = null
        try { aaServerSocket?.close() } catch (e: Exception) {}
        try { hfpServerSocket?.close() } catch (e: Exception) {}
        synchronized(extraAaServerSockets) {
            extraAaServerSockets.forEach { try { it.close() } catch (e: Exception) {} }
            extraAaServerSockets.clear()
        }
        synchronized(extraHfpServerSockets) {
            extraHfpServerSockets.forEach { try { it.close() } catch (e: Exception) {} }
            extraHfpServerSockets.clear()
        }
        aaServerSocket = null
        hfpServerSocket = null
        standingInForHfp = false // CarLink (c86332be)
        credentials = null
        pokeJob?.cancel()
        pokeJob = null
        lastPokeTriggerCredentials = null
        // Neither a handshake nor a settle can outlive the manager: leaving these set would keep
        // isAttemptInFlight() true across a restart, blocking the very poke the next start()
        // needs.
        handshakeStartedAt = 0L
        handoffSettlingSince = 0L
        // Cancel before dropping the reference, for the same reason a supersede does: the socket
        // this manager just closed does not necessarily end the coroutine reading from it.
        activeHandshakeJob?.cancel()
        activeHandshakeJob = null
        activeHandshakeSocket = null
        // Only the per-attempt count resets: everAcceptedAaConnection is deliberately kept, so a
        // unit that has connected before is not warned just because the manager was re-armed.
        pokesSinceLastAccept = 0
        // A mode change or a user exit is a fresh start, so the next start() serves handshakes
        // again rather than inheriting a backoff the user cannot see.
        resetHandshakeBackoff()
    }
}
