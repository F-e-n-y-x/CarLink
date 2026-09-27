package com.andrerinas.openheadunit.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.wifi.SoftApConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.andrerinas.openheadunit.aap.ApInterfaceCandidate
import com.andrerinas.openheadunit.aap.LocalHotspotPolicy
import com.andrerinas.openheadunit.aap.NativeCredentialsPolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.InterfaceMacReader
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.Executor

/** An app-owned local AP reservation; never changes saved Wi-Fi or global tethering settings. */
class LocalHotspotCredentialsProvider(private val context: Context, private val scope: CoroutineScope) {
    private val main = Handler(Looper.getMainLooper())
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // All state changes, including framework callbacks and final publication, run on main.
    private var generation = 0
    private var requested = false
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var resolveJob: Job? = null
    private var timeout: Runnable? = null
    private var ready: Credentials? = null
    private var listener: ((String, String, String, String) -> Unit)? = null
    private var invalidated: (() -> Unit)? = null

    private class Credentials(val ssid: String, val password: String, val ip: String, val bssid: String)

    fun setCredentialsListener(value: (String, String, String, String) -> Unit) { listener = value }
    fun setInvalidatedListener(value: () -> Unit) { invalidated = value }

    fun start() { main.post {
        if (requested) { publish(); return@post }
        if (Build.VERSION.SDK_INT < 26) {
            report("Local hotspot requires Android 8 or later. Choose Wi-Fi Direct or Car hotspot.")
            return@post
        }
        requested = true
        val token = ++generation
        val before = interfaces().mapNotNull { it.siteLocalIpv4 }.toSet()
        val upstreams = upstreamInterfaces()
        timeout = Runnable {
            if (current(token)) fail("Local hotspot did not become ready. Disconnect the car from home Wi-Fi or turn off its shared hotspot, then retry.")
        }.also { main.postDelayed(it, 25_000) }
        try {
            AppLog.i("LocalHotspot: starting with Wi-Fi client enabled=${wifi.isWifiEnabled}")
            startFiveGhzHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                    if (!current(token) || reservation != null) { value.close(); return }
                    reservation = value
                    resolve(token, value, before, upstreams)
                }
                override fun onStopped() {
                    if (current(token)) fail("Android stopped the local hotspot. Connect again when the Wi-Fi radio is available.")
                }
                override fun onFailed(reason: Int) {
                    if (current(token)) fail("Local hotspot could not start (Android reason $reason). Disconnect home Wi-Fi or turn off the car's shared hotspot, then retry.")
                }
            })
        } catch (_: SecurityException) {
            fail("Allow Nearby devices and Location in app permissions before starting the local hotspot.")
        } catch (e: Exception) {
            AppLog.w("LocalHotspot: start failed: ${e.javaClass.simpleName}")
            fail("This car could not start a standalone 5 GHz hotspot. Choose Wi-Fi Direct or Car hotspot.")
        }
    } }

    private fun startFiveGhzHotspot(callback: WifiManager.LocalOnlyHotspotCallback) {
        // Android 13 permits target-33+ apps to supply an LOHS config with Nearby devices.
        // This SystemApi is available on the tested DiLink 5.1 firmware. Other versions need
        // the API-36 public entry point; never silently fall back to the 2.4 GHz default AP.
        check(Build.VERSION.SDK_INT == 33 || Build.VERSION.SDK_INT >= 36) {
            "A standalone 5 GHz local hotspot is unavailable on this Android version"
        }
        val builder = SoftApConfiguration.Builder()
        SoftApConfiguration.Builder::class.java.getMethod("setSsid", String::class.java)
            .invoke(builder, "DiAuto-${UUID.randomUUID().toString().take(6)}")
        SoftApConfiguration.Builder::class.java.getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType)
            .invoke(builder, UUID.randomUUID().toString().replace("-", "").take(20), SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
        @Suppress("DEPRECATION")
        val stationFrequency = runCatching { wifi.connectionInfo?.frequency }.getOrNull()
        val channel = LocalHotspotPolicy.preferredFiveGhzChannel(stationFrequency)
        SoftApConfiguration.Builder::class.java.getMethod("setChannel", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(builder, channel, SoftApConfiguration.BAND_5GHZ)
        val method = if (Build.VERSION.SDK_INT >= 36) "startLocalOnlyHotspotWithConfiguration" else "startLocalOnlyHotspot"
        WifiManager::class.java.getMethod(method, SoftApConfiguration::class.java, Executor::class.java,
            WifiManager.LocalOnlyHotspotCallback::class.java)
            .invoke(wifi, builder.build(), Executor { main.post(it) }, callback)
        AppLog.i("LocalHotspot: requested 5 GHz channel $channel reservation with normal app permission")
    }

    // A credential refresh never restarts the AP or resets its startup timeout.
    fun refresh() { main.post { if (requested) publish() } }
    fun stop() { main.post { stopOnMain() } }

    private fun current(token: Int) = requested && generation == token
    private fun stopOnMain() {
        requested = false
        generation++
        timeout?.let(main::removeCallbacks); timeout = null
        resolveJob?.cancel(); resolveJob = null
        val owned = reservation
        reservation = null
        ready = null
        runCatching { owned?.close() }
    }
    private fun fail(message: String) {
        stopOnMain()
        invalidated?.invoke()
        report(message)
    }
    private fun report(message: String) {
        AppLog.w("LocalHotspot: $message")
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    private fun publish() {
        ready?.let { listener?.invoke(it.ssid, it.password, it.ip, it.bssid) }
    }

    @Suppress("DEPRECATION")
    private fun resolve(token: Int, owned: WifiManager.LocalOnlyHotspotReservation, before: Set<String>, upstreams: Set<String>) {
        resolveJob = scope.launch(Dispatchers.IO) {
            try {
                val modern = if (Build.VERSION.SDK_INT >= 30) owned.softApConfiguration else null
                val legacy = if (modern == null) owned.wifiConfiguration else null
                val ssid = modern?.ssid ?: legacy?.SSID
                val password = modern?.passphrase ?: legacy?.preSharedKey
                // The Bluetooth handshake below advertises WPA2; do not mislabel an SAE-only AP.
                val wpa2 = if (modern != null) modern.securityType in listOf(1, 2)
                    else legacy?.allowedKeyManagement?.get(android.net.wifi.WifiConfiguration.KeyMgmt.WPA2_PSK) == true
                if (ssid.isNullOrBlank() || password.isNullOrBlank() || !wpa2) {
                    withContext(Dispatchers.Main) { if (current(token)) fail("Android did not provide a compatible WPA2 local hotspot. Choose another transport.") }
                    return@launch
                }
                // Configured BSSID is often null. Only accept a newly assigned AP address;
                // the existing station, cellular interface and a foreign P2P group are excluded.
                while (isActive) {
                    val candidate = LocalHotspotPolicy.pick(interfaces(), before, upstreams + upstreamInterfaces())
                    if (candidate != null) {
                        val net = NetworkInterface.getByName(candidate.name)
                        val mac = modern?.bssid?.toString()
                            ?.takeIf(NativeCredentialsPolicy::isUsableBssid)
                            ?: runCatching { net.hardwareAddress?.joinToString(":") { "%02x".format(it.toInt() and 255) } }.getOrNull()
                                ?.takeIf(NativeCredentialsPolicy::isUsableBssid)
                            ?: net.inetAddresses.toList().filterIsInstance<Inet6Address>()
                                .firstNotNullOfOrNull { LocalHotspotPolicy.eui64Mac(it.address) }
                            ?: InterfaceMacReader.fromSysfs(candidate.name)
                        if (NativeCredentialsPolicy.isUsableBssid(mac)) {
                            val credentials = Credentials(ssid, password, candidate.siteLocalIpv4!!, mac!!)
                            withContext(Dispatchers.Main) {
                                if (current(token) && reservation === owned) {
                                    timeout?.let(main::removeCallbacks); timeout = null
                                    ready = credentials
                                    AppLog.i("LocalHotspot: 5 GHz reservation ready on ${candidate.name}; no upstream internet required")
                                    publish()
                                }
                            }
                            return@launch
                        }
                    }
                    delay(250)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                AppLog.w("LocalHotspot: resolve failed: ${e.javaClass.simpleName}")
                withContext(Dispatchers.Main) { if (current(token)) fail("The local hotspot address could not be read. Choose Wi-Fi Direct or Car hotspot.") }
            }
        }
    }

    private fun interfaces(): List<ApInterfaceCandidate> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().map { net ->
            ApInterfaceCandidate(net.name, net.isLoopback, net.isUp,
                net.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }?.hostAddress)
        }
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    private fun upstreamInterfaces(): Set<String> = connectivity.allNetworks
        .mapNotNull { connectivity.getLinkProperties(it)?.interfaceName }.toSet()
}
