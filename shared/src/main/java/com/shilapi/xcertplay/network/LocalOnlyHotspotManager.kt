package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Owns one Android LocalOnlyHotspot reservation and reports its live configuration.
 *
 * [start] must run on a worker thread because it blocks until the system callback arrives and
 * the AP interface is usable. The reservation and multicast lock stay owned by this instance
 * until [close].
 */
class LocalOnlyHotspotManager(context: Context, private val onDiagnostic: (String) -> Unit = {}) : WirelessHotspotManager {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val stateLock = Object()

    private var startAttempt: StartAttempt? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var radioObserver: LocalOnlyHotspotRadioInfo? = null
    private var stopped = false
    private var closed = false

    /**
     * Starts a LocalOnlyHotspot and waits up to [timeoutMillis] for the live configuration and AP
     * interface. The returned credentials are not retained by this manager.
     */
    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LocalOnlyHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val attempt = StartAttempt()
        synchronized(stateLock) {
            check(!closed) { "LocalOnlyHotspotManager is closed" }
            check(startAttempt == null && reservation == null) {
                "A LocalOnlyHotspot is already starting or active"
            }
            startAttempt = attempt
        }

        var acquiredMulticastLock: WifiManager.MulticastLock? = null
        val radioInfo = LocalOnlyHotspotRadioInfo(wifiManager)
        var observerAdopted = false
        val deadlineNanos = deadlineAfter(timeoutMillis)
        val previousAddresses = activeInterfaces().flatMap { it.siteLocalIpv4Addresses() }.toSet()
        val previousUpstreams = upstreamInterfaceNames()

        try {
            ensureStartActive(attempt)
            onDiagnostic("LocalOnlyHotspot starting with Wi-Fi client enabled=${wifiManager.isWifiEnabled}")
            requestHotspot(createCallback(attempt))

            val activeReservation = awaitStart(attempt, deadlineNanos, timeoutMillis)
            radioInfo.start()
            acquiredMulticastLock = acquireMulticastLock(attempt)
            val configuration = readConfiguration(activeReservation)
            val apInterface = awaitApInterface(
                bssid = configuration.bssidBytes,
                previousAddresses = previousAddresses,
                previousUpstreams = previousUpstreams,
                attempt = attempt,
                deadlineNanos = deadlineNanos,
            )
            val liveRadio = awaitRadioInfo(radioInfo, apInterface, configuration, attempt, deadlineNanos)
            if ((Build.VERSION.SDK_INT == 33 || Build.VERSION.SDK_INT >= 36) &&
                (liveRadio?.frequencyMHz?.let { it !in 5160..5895 } ?: (configuration.bandLabel != "5 GHz"))) {
                throw IOException("This firmware did not provide the requested 5 GHz local hotspot; choose Wi-Fi Direct or Car hotspot")
            }

            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                reservation = activeReservation
                multicastLock = acquiredMulticastLock
                radioObserver = radioInfo
                observerAdopted = true
                startAttempt = null
                stopped = false
                acquiredMulticastLock = null
            }
            radioInfo.watch(apInterface.bssid ?: configuration.bssid, onDiagnostic)

            return WirelessHotspotInfo(
                ssid = configuration.ssid,
                passphrase = configuration.passphrase,
                security = configuration.security,
                channel = liveRadio?.frequencyMHz?.let(::wifiFrequencyMhzToChannel) ?: configuration.channel,
                frequencyMHz = liveRadio?.frequencyMHz,
                bssid = apInterface.bssid ?: configuration.bssid,
                interfaceName = apInterface.name,
                hostAddress = apInterface.hostAddress,
                bandLabel = when (liveRadio?.frequencyMHz) {
                    in 2412..2484 -> "2.4 GHz"
                    in 5160..5895 -> "5 GHz"
                    in 5955..7115 -> "6 GHz"
                    else -> configuration.bandLabel
                },
                backend = WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT,
            )
        } catch (failure: Exception) {
            cleanupFailedStart(attempt, acquiredMulticastLock)
            throw failure
        } finally {
            if (!observerAdopted) radioInfo.close()
        }
    }

    private fun requestHotspot(callback: WifiManager.LocalOnlyHotspotCallback) {
        // Android 13's service accepts a custom LOHS configuration from target-33+ callers
        // with Nearby devices permission. Its framework entry point is SystemApi, so this
        // compatibility path is capability-checked and never changes global AP settings.
        // Other pre-36 releases keep the standard reservation path.
        val main = Handler(Looper.getMainLooper())
        val executor = Executor { main.post(it) }
        if (Build.VERSION.SDK_INT == 33 || Build.VERSION.SDK_INT >= 36) {
            try {
                val builder = SoftApConfiguration.Builder()
                SoftApConfiguration.Builder::class.java.getMethod("setSsid", String::class.java)
                    .invoke(builder, "DiPlay-${UUID.randomUUID().toString().take(6)}")
                SoftApConfiguration.Builder::class.java.getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(builder, UUID.randomUUID().toString().replace("-", "").take(20), SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                // Request the station's 5 GHz channel, or 36 without a 5 GHz station.
                // BYD may override even a fixed channel (observed 40 -> 149), so credentials
                // below always use the settled live callback rather than this preference.
                @Suppress("DEPRECATION")
                val stationFrequency = runCatching { wifiManager.connectionInfo?.frequency }.getOrNull()
                val preferredChannel = stationFrequency?.takeIf { it in 5160..5895 }
                    ?.let(::wifiFrequencyMhzToChannel) ?: 36
                SoftApConfiguration.Builder::class.java.getMethod("setChannel", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(builder, preferredChannel, SoftApConfiguration.BAND_5GHZ)
                val method = if (Build.VERSION.SDK_INT >= 36) "startLocalOnlyHotspotWithConfiguration" else "startLocalOnlyHotspot"
                WifiManager::class.java.getMethod(method, SoftApConfiguration::class.java, Executor::class.java,
                    WifiManager.LocalOnlyHotspotCallback::class.java).invoke(wifiManager, builder.build(), executor, callback)
                return
            } catch (failure: ReflectiveOperationException) {
                if (failure.cause != null && failure.cause !is SecurityException && failure.cause !is UnsupportedOperationException) {
                    throw IOException("LocalOnlyHotspot custom startup failed", failure.cause)
                }
                // Unsupported firmware retains the ordinary Android-generated AP.
            } catch (_: SecurityException) {
                // No privileged permission is requested to enable this optional path.
            }
        }
        // Main-loop callback delivery survives cancellation to close late reservations.
        wifiManager.startLocalOnlyHotspot(callback, main)
    }

    private fun awaitRadioInfo(
        observer: LocalOnlyHotspotRadioInfo,
        ap: ApInterface,
        configuration: HotspotConfiguration,
        attempt: StartAttempt,
        deadlineNanos: Long,
    ): LocalOnlyHotspotRadioInfo.Radio? {
        while (true) {
            ensureStartActive(attempt)
            observer.settledForBssid(ap.bssid ?: configuration.bssid)?.let { return it }
            observer.unavailableReason?.let {
                if (configuration.channel > 0) return null
                throw IOException("LocalOnlyHotspot: $it; cannot advertise automatic channel 0 to CarPlay")
            }
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) throw IOException("LocalOnlyHotspot did not report its live channel; cannot advertise automatic channel 0 to CarPlay")
            try {
                TimeUnit.NANOSECONDS.sleep(minOf(remaining, INTERFACE_POLL_NANOS))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for the LocalOnlyHotspot channel", interrupted)
            }
        }
    }

    override fun close() {
        val activeReservation: WifiManager.LocalOnlyHotspotReservation?
        val activeMulticastLock: WifiManager.MulticastLock?
        val activeObserver: LocalOnlyHotspotRadioInfo?
        synchronized(stateLock) {
            if (closed) return
            closed = true
            startAttempt?.stopped = true
            stateLock.notifyAll()
            activeReservation = reservation
            activeMulticastLock = multicastLock
            activeObserver = radioObserver
            radioObserver = null
            reservation = null
            multicastLock = null
        }

        releaseMulticastLock(activeMulticastLock)
        activeObserver?.close()
        activeReservation?.close()
    }

    private fun createCallback(attempt: StartAttempt): WifiManager.LocalOnlyHotspotCallback =
        object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(
                reservation: WifiManager.LocalOnlyHotspotReservation,
            ) {
                val closeReservation = synchronized(stateLock) {
                    if (
                        closed ||
                        startAttempt !== attempt ||
                        attempt.stopped ||
                        attempt.reservation != null
                    ) {
                        true
                    } else {
                        attempt.reservation = reservation
                        stateLock.notifyAll()
                        false
                    }
                }
                if (closeReservation) reservation.close()
            }

            override fun onFailed(reason: Int) {
                synchronized(stateLock) {
                    if (startAttempt === attempt && attempt.failure == null) {
                        attempt.failure = IOException(
                            "LocalOnlyHotspot failed: ${failureReason(reason)}",
                        )
                        stateLock.notifyAll()
                    }
                }
            }

            override fun onStopped() {
                var lockToRelease: WifiManager.MulticastLock? = null
                synchronized(stateLock) {
                    if (startAttempt === attempt) attempt.stopped = true
                    if (reservation === attempt.reservation) {
                        stopped = true
                        lockToRelease = multicastLock
                        multicastLock = null
                    }
                    stateLock.notifyAll()
                }
                releaseMulticastLock(lockToRelease)
            }
        }

    private fun awaitStart(
        attempt: StartAttempt,
        deadlineNanos: Long,
        timeoutMillis: Long,
    ): WifiManager.LocalOnlyHotspotReservation = synchronized(stateLock) {
        while (true) {
            if (closed) throw IOException("LocalOnlyHotspot manager closed while starting")
            attempt.failure?.let { throw it }
            if (attempt.stopped) {
                throw IOException("LocalOnlyHotspot stopped before startup completed")
            }
            attempt.reservation?.let { return@synchronized it }

            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                throw IOException(
                    "Timed out after ${timeoutMillis}ms waiting for LocalOnlyHotspot",
                )
            }
            waitNanos(remainingNanos)
        }
        error("unreachable")
    }

    private fun acquireMulticastLock(attempt: StartAttempt): WifiManager.MulticastLock {
        ensureStartActive(attempt)
        val lock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG)
        lock.setReferenceCounted(false)
        try {
            lock.acquire()
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
            }
            return lock
        } catch (failure: Exception) {
            releaseMulticastLock(lock)
            throw failure
        }
    }

    private fun readConfiguration(
        reservation: WifiManager.LocalOnlyHotspotReservation,
    ): HotspotConfiguration {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            readSoftApConfiguration(reservation.softApConfiguration)
        } else {
            @Suppress("DEPRECATION")
            val configuration = reservation.wifiConfiguration
                ?: throw IOException("LocalOnlyHotspot did not provide a Wi-Fi configuration")
            readWifiConfiguration(configuration)
        }
    }

    @Suppress("DEPRECATION")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun readSoftApConfiguration(configuration: SoftApConfiguration): HotspotConfiguration {
        val ssid = validateSsid(configuration.ssid)
        val security = mapSoftApSecurity(configuration.securityType)
        val passphrase = validatePassphrase(security, configuration.passphrase)
        val channel = if (Build.VERSION.SDK_INT >= 36) {
            readConfiguredChannel(configuration)
        } else {
            readLegacySoftApChannel(configuration)
        }
        val bssid = configuration.bssid

        return HotspotConfiguration(
            ssid = ssid,
            passphrase = passphrase,
            security = security,
            channel = channel.first,
            bssid = bssid?.toString(),
            bssidBytes = bssid?.toByteArray(),
            bandLabel = channel.second,
        )
    }

    @Suppress("DEPRECATION")
    private fun readWifiConfiguration(configuration: WifiConfiguration): HotspotConfiguration {
        val ssid = validateSsid(configuration.SSID)
        val security = mapWifiConfigurationSecurity(configuration)
        val passphrase = validatePassphrase(security, unquote(configuration.preSharedKey))
        val bssid = configuration.BSSID?.let {
            try {
                MacAddress.fromString(it)
            } catch (failure: IllegalArgumentException) {
                throw IOException("LocalOnlyHotspot reported an invalid BSSID: $it", failure)
            }
        }
        val channel = readWifiConfigurationChannel(configuration)

        return HotspotConfiguration(
            ssid = ssid,
            passphrase = passphrase,
            security = security,
            channel = channel,
            bssid = bssid?.toString(),
            bssidBytes = bssid?.toByteArray(),
            bandLabel = readWifiConfigurationBandLabel(configuration, channel),
        )
    }

    @RequiresApi(36)
    private fun readConfiguredChannel(configuration: SoftApConfiguration): Pair<Int, String> {
        val channels = configuration.channels
        if (channels.size() != 1) {
            throw IOException("Expected one LocalOnlyHotspot channel, got ${channels.size()}")
        }
        val band = channels.keyAt(0)
        val channel = requireChannel(
            channels.valueAt(0),
            "SoftApConfiguration.channels",
            allowAuto = true,
        )
        return channel to softApBandLabel(band)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readLegacySoftApChannel(configuration: SoftApConfiguration): Pair<Int, String> {
        val channel = try {
            val getter = SoftApConfiguration::class.java.getMethod("getChannel")
            val value = getter.invoke(configuration) as? Number
                ?: throw IOException("SoftApConfiguration.getChannel returned no channel")
            requireChannel(
                value.toInt(),
                "SoftApConfiguration.getChannel",
                allowAuto = true,
            )
        } catch (failure: ReflectiveOperationException) {
            throw IOException(
                "Android ${Build.VERSION.RELEASE} does not expose SoftApConfiguration.getChannel",
                failure,
            )
        }
        return channel to readLegacySoftApBandLabel(configuration, channel)
    }

    private fun readWifiConfigurationChannel(configuration: WifiConfiguration): Int {
        val channel = try {
            WifiConfiguration::class.java.getField("apChannel").getInt(configuration)
        } catch (failure: ReflectiveOperationException) {
            throw IOException(
                "Android ${Build.VERSION.RELEASE} does not expose WifiConfiguration.apChannel",
                failure,
            )
        }
        return requireChannel(channel, "WifiConfiguration.apChannel", allowAuto = true)
    }

    private fun readWifiConfigurationBandLabel(
        configuration: WifiConfiguration,
        channel: Int,
    ): String {
        val band = try {
            (WifiConfiguration::class.java.getField("apBand").get(configuration) as? Number)
                ?.toInt()
        } catch (_: ReflectiveOperationException) {
            null
        }
        return band?.let(::softApBandLabel) ?: legacyBandLabel(channel)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readLegacySoftApBandLabel(
        configuration: SoftApConfiguration,
        channel: Int,
    ): String {
        val band = try {
            (SoftApConfiguration::class.java.getMethod("getBand").invoke(configuration) as? Number)
                ?.toInt()
        } catch (_: ReflectiveOperationException) {
            null
        }
        return band?.let(::softApBandLabel) ?: legacyBandLabel(channel)
    }

    private fun awaitApInterface(
        bssid: ByteArray?,
        previousAddresses: Set<String>,
        previousUpstreams: Set<String>,
        attempt: StartAttempt,
        deadlineNanos: Long,
    ): ApInterface {
        while (true) {
            ensureStartActive(attempt)
            val networkInterface = findInterface(bssid, previousAddresses, previousUpstreams)
            if (networkInterface != null) {
                networkInterface.hotspotAddress()?.let { hostAddress ->
                    val interfaceBssid = networkInterface.interfaceBssid()
                    if (bssid == null && interfaceBssid == null) {
                        return@let
                    }
                    return ApInterface(
                        name = networkInterface.name,
                        hostAddress = hostAddress,
                        bssid = interfaceBssid,
                    )
                }
            }

            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                throw IOException("LocalOnlyHotspot started but its AP interface/address could not be identified")
            }
            try {
                TimeUnit.NANOSECONDS.sleep(minOf(remainingNanos, INTERFACE_POLL_NANOS))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for the LocalOnlyHotspot interface", interrupted)
            }
        }
    }

    private fun findInterface(
        bssid: ByteArray?,
        previousAddresses: Set<String>,
        previousUpstreams: Set<String>,
    ): NetworkInterface? {
        val interfaces = activeInterfaces()
        val candidates = interfaces.map { net ->
            LocalOnlyHotspotInterfacePolicy.Candidate(net.name, net.siteLocalIpv4Addresses(), net.interfaceBssid())
        }
        val selected = LocalOnlyHotspotInterfacePolicy.select(
            candidates, previousAddresses, previousUpstreams + upstreamInterfaceNames(), bssid?.toMacAddressString(),
        ) ?: return null
        return interfaces.singleOrNull { it.name == selected.name }
    }

    private fun activeInterfaces(): List<NetworkInterface> =
        NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }.orEmpty().filter { networkInterface ->
            try {
                networkInterface.isUp && !networkInterface.isLoopback
            } catch (_: SocketException) {
                false
            }
        }

    @Suppress("DEPRECATION")
    private fun upstreamInterfaceNames(): Set<String> = connectivityManager?.allNetworks.orEmpty()
        .mapNotNull { connectivityManager?.getLinkProperties(it)?.interfaceName }.toSet()

    private fun NetworkInterface.siteLocalIpv4Addresses(): Set<String> =
        Collections.list(inetAddresses).filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }.toSet()

    private fun NetworkInterface.interfaceBssid(): String? =
        runCatching { hardwareAddress?.toMacAddressString() }.getOrNull()
            ?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" }
            ?: Collections.list(inetAddresses).filterIsInstance<Inet6Address>()
                .firstNotNullOfOrNull { it.toEui64MacAddress() }

    private fun NetworkInterface.hotspotAddress(): InetAddress? {
        var ipv4: InetAddress? = null
        for (address in Collections.list(inetAddresses)) {
            if (address is Inet6Address && address.isLinkLocalAddress) {
                if (address.scopeId == index) return address
                try {
                    return Inet6Address.getByAddress(null, address.address, this)
                } catch (_: UnknownHostException) {
                    continue
                }
            }
            if (address is Inet4Address && !address.isLoopbackAddress && ipv4 == null) {
                ipv4 = address
            }
        }
        return ipv4
    }

    private fun ensureStartActive(attempt: StartAttempt) {
        synchronized(stateLock) {
            ensureStartActiveLocked(attempt)
        }
    }

    private fun ensureStartActiveLocked(attempt: StartAttempt) {
        if (closed) throw IOException("LocalOnlyHotspot manager closed while starting")
        if (startAttempt !== attempt) throw IOException("LocalOnlyHotspot startup was cancelled")
        if (attempt.stopped) throw IOException("LocalOnlyHotspot stopped while starting")
    }

    private fun cleanupFailedStart(
        attempt: StartAttempt,
        multicastLock: WifiManager.MulticastLock?,
    ) {
        val failedReservation: WifiManager.LocalOnlyHotspotReservation?
        synchronized(stateLock) {
            if (startAttempt === attempt) startAttempt = null
            attempt.stopped = true
            stateLock.notifyAll()
            failedReservation = attempt.reservation
        }
        releaseMulticastLock(multicastLock)
        failedReservation?.close()
    }

    private fun waitNanos(nanos: Long) {
        val millis = nanos / NANOS_PER_MILLISECOND
        val remainder = (nanos % NANOS_PER_MILLISECOND).toInt()
        try {
            stateLock.wait(millis, remainder)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for LocalOnlyHotspot", interrupted)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun validateSsid(value: String?): String {
        val ssid = unquote(value)
        if (ssid.isNullOrEmpty() || ssid == WifiManager.UNKNOWN_SSID) {
            throw IOException("LocalOnlyHotspot did not report a usable SSID")
        }
        if ('\u0000' in ssid) throw IOException("LocalOnlyHotspot SSID contains U+0000")
        return ssid
    }

    private fun validatePassphrase(
        security: Iap2WirelessSecurity,
        value: String?,
    ): String {
        val passphrase = value.orEmpty()
        if (security != Iap2WirelessSecurity.NONE && passphrase.isEmpty()) {
            throw IOException("LocalOnlyHotspot did not report a passphrase for secured Wi-Fi")
        }
        if ('\u0000' in passphrase) {
            throw IOException("LocalOnlyHotspot passphrase contains U+0000")
        }
        return passphrase
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun mapSoftApSecurity(securityType: Int): Iap2WirelessSecurity = when (securityType) {
        SoftApConfiguration.SECURITY_TYPE_OPEN -> Iap2WirelessSecurity.NONE
        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> Iap2WirelessSecurity.WPA_WPA2
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION ->
            Iap2WirelessSecurity.WPA3_TRANSITION
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> Iap2WirelessSecurity.WPA3_ONLY
        SoftApConfiguration.SECURITY_TYPE_WPA3_OWE,
        SoftApConfiguration.SECURITY_TYPE_WPA3_OWE_TRANSITION,
        -> throw IOException("Unsupported LocalOnlyHotspot security type: OWE")
        else -> throw IOException(
            "Unsupported LocalOnlyHotspot security type: $securityType",
        )
    }

    private fun mapWifiConfigurationSecurity(
        configuration: WifiConfiguration,
    ): Iap2WirelessSecurity {
        val keyManagement = configuration.allowedKeyManagement
            ?: throw IOException("LocalOnlyHotspot did not report its key management")
        val open = keyManagement.get(WifiConfiguration.KeyMgmt.NONE)
        val wpa2 = keyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
        val sae = keyManagement.get(WifiConfiguration.KeyMgmt.SAE)
        val owe = keyManagement.get(WifiConfiguration.KeyMgmt.OWE)

        return when {
            owe -> throw IOException("Unsupported LocalOnlyHotspot security type: OWE")
            open && !wpa2 && !sae -> Iap2WirelessSecurity.NONE
            wpa2 && sae -> Iap2WirelessSecurity.WPA3_TRANSITION
            wpa2 -> Iap2WirelessSecurity.WPA_WPA2
            sae -> Iap2WirelessSecurity.WPA3_ONLY
            else -> throw IOException(
                "Unsupported LocalOnlyHotspot key management: $keyManagement",
            )
        }
    }

    private fun requireChannel(channel: Int, source: String): Int {
        return requireChannel(channel, source, allowAuto = false)
    }

    private fun requireChannel(channel: Int, source: String, allowAuto: Boolean): Int {
        if (channel !in 0..0xff || (!allowAuto && channel == 0)) {
            throw IOException("$source returned invalid channel: $channel")
        }
        return channel
    }

    private fun ByteArray.toMacAddressString(): String =
        joinToString(":") { "%02x".format(it.toInt() and 0xff) }

    private fun Inet6Address.toEui64MacAddress(): String? {
        val bytes = address
        if (!isLinkLocalAddress || bytes.size != 16 || bytes[11] != 0xff.toByte() ||
            bytes[12] != 0xfe.toByte()
        ) {
            return null
        }
        return byteArrayOf(
            (bytes[8].toInt() xor 0x02).toByte(),
            bytes[9],
            bytes[10],
            bytes[13],
            bytes[14],
            bytes[15],
        ).toMacAddressString()
    }

    private fun softApBandLabel(band: Int): String = when (band) {
        SoftApConfiguration.BAND_2GHZ -> "2.4 GHz"
        SoftApConfiguration.BAND_5GHZ -> "5 GHz"
        SoftApConfiguration.BAND_6GHZ -> "6 GHz"
        SoftApConfiguration.BAND_60GHZ -> "60 GHz"
        else -> "Unknown band ($band)"
    }

    private fun legacyBandLabel(channel: Int): String = when (channel) {
        in 1..14 -> "2.4 GHz"
        in 32..177 -> "5 GHz"
        else -> "Unknown band"
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    private fun failureReason(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "no channel available"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "generic error"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "incompatible Wi-Fi mode"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "tethering disallowed"
        else -> "reason $reason"
    }

    private fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
        if (lock == null) return
        try {
            if (lock.isHeld) lock.release()
        } catch (_: RuntimeException) {
            // close() is best-effort; the hotspot reservation remains the authoritative owner.
        }
    }

    private class StartAttempt {
        var reservation: WifiManager.LocalOnlyHotspotReservation? = null
        var failure: IOException? = null
        var stopped = false
    }

    private class HotspotConfiguration(
        val ssid: String,
        val passphrase: String,
        val security: Iap2WirelessSecurity,
        val channel: Int,
        val bssid: String?,
        val bssidBytes: ByteArray?,
        val bandLabel: String,
    )

    private class ApInterface(
        val name: String,
        val hostAddress: InetAddress?,
        val bssid: String?,
    )

    private companion object {
        const val MULTICAST_LOCK_TAG = "xcertplay-local-only-hotspot-mdns"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val INTERFACE_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(100)
    }
}
