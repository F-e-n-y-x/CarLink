package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal data class HotspotInterfaceSnapshot(
    val name: String,
    val index: Int,
    val up: Boolean,
    val addresses: List<InetAddress>,
    val wireless: Boolean,
)

internal data class HotspotNetworkSnapshot(
    val interfaces: List<HotspotInterfaceSnapshot>,
    val apInterfaces: Set<String>?,
    val wifiUpstreams: Set<String>?,
    val defaultInterface: String?,
    val consistent: Boolean = true,
    val apEnabled: Boolean? = true,
    /** The Wi-Fi interfaces the platform itself reports as tethered, or null when it cannot be read. */
    val platformAp: Set<String>? = null,
)

internal data class HotspotSelection(val name: String, val index: Int, val address: InetAddress) {
    fun sameAddress(other: HotspotSelection): Boolean = name == other.name && index == other.index &&
        address.address.contentEquals(other.address.address) &&
        (address as? Inet6Address)?.scopeId == (other.address as? Inet6Address)?.scopeId
}

internal fun selectHotspotInterface(snapshot: HotspotNetworkSnapshot, log: (String) -> Unit): HotspotSelection? {
    if (!snapshot.consistent || snapshot.apEnabled == false) {
        log("hotspot sample rejected: network_changed=${!snapshot.consistent} apEnabled=${snapshot.apEnabled}")
        return null
    }
    // CarLink: #93, a modem interface listed as the AP (a stale ownership set) is no AP evidence; when it was the
    // only one listed, the set reads as unobservable so the real wlan0/softap/ap0 can still be found.
    val apInterfaces = snapshot.apInterfaces?.filterNot(::mobileDataInterfaceName)?.toSet()
        ?.takeUnless { it.isEmpty() && snapshot.apInterfaces.isNotEmpty() }
    return snapshot.interfaces.mapNotNull { iface ->
        val owned = apInterfaces?.contains(iface.name) == true
        val upstream = snapshot.wifiUpstreams?.contains(iface.name) == true
        val hotspotAddress = wirelessHostAddress(iface.addresses.filter {
            it is Inet6Address && it.isLinkLocalAddress || publishesHotspotAddress(it)
        }, iface.index)
        // A vendor AP interface may publish a non-private subnet: the reference head unit hands out
        // 198.18.34.x on its eth0.3 VLAN (#573), which isSiteLocalAddress() rejects. The wider rule
        // applies only to an interface already named as the hotspot; an ordinary station interface
        // still has to publish a private address before it may stand in for one.
        val address = if (owned) hotspotAddress else hotspotAddress?.takeIf {
            it !is Inet4Address || it.isSiteLocalAddress
        }
        // A Unisoc UMS512 head unit (#1187) read isUp() false for the wlan0 that the platform listed as its
        // tethered hotspot and that held the hotspot address, so the car hotspot never became ready. The
        // platform's own tether list outweighs that flag; a configured or inferred interface still has to be up.
        val reportedDown = !iface.up && !(snapshot.platformAp?.contains(iface.name) == true && address != null)
        val reason = when {
            reportedDown || iface.index <= 0 -> "interface_down"
            mobileDataInterfaceName(iface.name) -> "mobile_data" // CarLink: #93
            address == null -> "address_unavailable"
            owned -> "platform_ap"
            apInterfaces != null -> "not_platform_ap" // CarLink: #93 (was snapshot.apInterfaces)
            upstream -> "wifi_upstream"
            snapshot.defaultInterface == iface.name -> "default_network_without_ap_evidence"
            snapshot.wifiUpstreams == null -> "upstream_unobservable"
            !iface.wireless -> "no_ap_evidence"
            else -> "wireless_non_upstream"
        }
        log("hotspot candidate iface=${iface.name} index=${iface.index} " +
            "family=${if (address is Inet6Address) "IPv6" else if (address != null) "IPv4" else "none"} " +
            "scope=${(address as? Inet6Address)?.scopeId ?: 0} evidence=$reason " +
            "ap=${apInterfaces?.let { if (owned) "yes" else "no" } ?: "unobservable"} " + // CarLink: #93
            "defaultConflict=${owned && (upstream || snapshot.defaultInterface == iface.name)} up=${iface.up}")
        if (reason != "platform_ap" && reason != "wireless_non_upstream") null
        else (if (owned) 100 else 0) to HotspotSelection(iface.name, iface.index, address!!)
    }.sortedWith(compareByDescending<Pair<Int, HotspotSelection>> { it.first }
        .thenByDescending { it.second.address is Inet4Address } // CarLink: #93, a usable IPv4 wins a tie
        .thenBy { it.second.name })
        .firstOrNull()?.second
}

internal class ManualHotspotReadiness(
    private val sample: () -> HotspotNetworkSnapshot,
    private val cancelled: () -> Boolean,
    private val pause: (Long) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
    private val configuredInterface: String? = null,
) {
    fun await(timeoutMillis: Long): HotspotSelection {
        val deadline = nowMillis() + timeoutMillis
        var previous: HotspotSelection? = null
        var stable = 0
        var last: HotspotNetworkSnapshot? = null
        while (true) {
            if (cancelled()) throw InterruptedIOException("Hotspot readiness cancelled")
            if (nowMillis() >= deadline) throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_NOT_READY, "Hotspot network is not ready",
                if (last != null && lacksConfiguredInterface(last)) ConnectionFailureCause.HOTSPOT_INTERFACE_NOT_FOUND else null,
            )
            last = sample()
            val selected = selectHotspotInterface(last, log)
            if (cancelled()) throw InterruptedIOException("Hotspot readiness cancelled")
            stable = if (selected != null && previous?.sameAddress(selected) == true) stable + 1 else 1
            previous = selected
            if (selected != null && stable >= WirelessStartupPolicy.STABLE_SAMPLES && nowMillis() < deadline) {
                log("hotspot interface confirmed iface=${selected.name} index=${selected.index} atMs=${nowMillis()}")
                return selected
            }
            pause(minOf(WirelessStartupPolicy.INTERFACE_POLL_MILLIS, (deadline - nowMillis()).coerceAtLeast(1)))
        }
    }

    private fun lacksConfiguredInterface(snapshot: HotspotNetworkSnapshot): Boolean {
        val name = configuredInterface?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return snapshot.apEnabled == true && snapshot.interfaces.isNotEmpty() && snapshot.interfaces.none { it.name == name }
    }
}
