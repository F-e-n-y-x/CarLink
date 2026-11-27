package com.shilapi.xcertplay.network

// CarLink: new file (see docs/VENDOR_CHANGES.md). Automatic IPv4 / IPv4+IPv6 choice for the car hotspot.

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * The car hotspot (MANUAL_HOTSPOT) picks its address family by itself, per head unit.
 *
 * #93 made the car hotspot IPv4 end to end, because the SoftAP of a Changan CS75 Plus 2023 has only a
 * link-local IPv6 address: advertising it (AAAA, extra listeners, iAP2 0x4301) stalled CarPlay after
 * the Wi-Fi credentials. Other head units run a real IPv6 network on their AP, and for them dropping
 * IPv6 for good is a restriction nobody asked for. So IPv4 only stays the default and IPv6 is added only
 * when the head unit clearly runs it:
 *
 * 1. Probe the AP interface before anything is advertised. IPv6 counts as usable only with a
 *    non-tentative, non-deprecated **global/ULA** address that binds and answers a local connect,
 *    IPv6 not disabled on the interface, and (when readable) a non-link-local IPv6 route on it.
 *    A link-local address alone is never enough: that is exactly what the CS75 SoftAP has.
 * 2. Usable: dual stack, IPv4 always first (host, Bonjour, 0x4301), IPv6 as separate listeners. Only the
 *    global/ULA addresses are served and advertised; a link-local address is never a listener, an AAAA
 *    record or a 0x4301 address.
 * 3. A dual-stack session that gets no AirPlay TCP, or gets TCP / RTSP SETUP but never a first
 *    picture (Picture / NTP / event / RTP ports never connect), is a failure: this unit is
 *    remembered as IPv4-only and the session restarts on IPv4 only.
 * 4. Memory is per head unit ([unitKey]: SSID + AP interface + device model hash; not the AP MAC,
 *    which newer Android randomizes per SoftAP start), in its own prefs, never the shared-Wi-Fi
 *    `hotspot_address_family`. A changed network is a new key (re-probe); an IPv4-only verdict
 *    expires after [REMEMBER_IPV4_ONLY_MILLIS] or [REPROBE_AFTER_FAILURES] failed IPv4-only sessions
 *    in a row.
 */
object CarHotspotIpFamily {
    const val MODE_IPV4_ONLY = "ipv4_only"
    const val MODE_DUAL_STACK = "dual_stack"

    // Reasons (stable strings: logs, Diagnostics, tests).
    const val REASON_REMEMBERED_IPV4 = "remembered_ipv4_only"
    const val REASON_REMEMBERED_DUAL = "remembered_dual_stack"
    const val REASON_NO_IPV4 = "no_ipv4"
    const val REASON_IPV6_DISABLED = "ipv6_disabled"
    const val REASON_NO_IPV6 = "no_ipv6"
    const val REASON_IPV6_NOT_USABLE = "ipv6_not_usable"
    const val REASON_LINK_LOCAL_ONLY = "link_local_only"
    const val REASON_NO_IPV6_ROUTE = "no_ipv6_route"
    const val REASON_IPV6_USABLE = "ipv6_usable"
    const val REASON_FALLBACK_NO_TCP = "fallback_no_tcp"
    const val REASON_FALLBACK_PICTURE_STALL = "fallback_picture_stall"

    /** After the iPhone's first AirPlay TCP on a dual-stack session, the picture must arrive within this. */
    const val PICTURE_STALL_MILLIS = 25_000L
    const val REMEMBER_IPV4_ONLY_MILLIS = 30L * 24 * 60 * 60 * 1000
    const val REPROBE_AFTER_FAILURES = 3

    const val PREFS = "carlink_car_hotspot_ipfamily"

    /** One IPv6 address on the AP interface, as the probe saw it. Null flags = not readable. */
    data class Ipv6Candidate(
        val address: Inet6Address,
        val tentative: Boolean? = null,
        val dadFailed: Boolean? = null,
        val deprecated: Boolean? = null,
        val bindOk: Boolean = false,
    ) {
        val linkLocal: Boolean get() = address.isLinkLocalAddress
        val scoped: Boolean get() = !linkLocal || address.scopeId != 0
        val usable: Boolean get() = bindOk && tentative != true && dadFailed != true && deprecated != true && scoped &&
            !address.isLoopbackAddress && !address.isMulticastAddress && !address.isAnyLocalAddress
    }

    data class Probe(
        val ipv4Present: Boolean,
        val candidates: List<Ipv6Candidate>,
        /** /proc/sys/net/ipv6/conf/<iface>/disable_ipv6; null when unreadable. */
        val ipv6Disabled: Boolean? = null,
        /** A non-link-local IPv6 route on the interface in /proc/net/ipv6_route; null when unreadable. */
        val ipv6Route: Boolean? = null,
    ) {
        fun summary(): String = "ipv4=$ipv4Present ipv6=${candidates.size} " +
            "usable=${candidates.count { it.usable }} global=${candidates.count { it.usable && !it.linkLocal }} " +
            "disabled=${ipv6Disabled ?: "unknown"} route=${ipv6Route ?: "unknown"}"
    }

    /** What the unit remembers. */
    data class UnitRecord(
        val mode: String,
        val reason: String,
        val decidedAtMillis: Long,
        val ipv4Failures: Int = 0,
    )

    data class Decision(
        val mode: String,
        val reason: String,
        /** Whether the probe found IPv6 usable on the AP (the head unit "supports" it), independent of memory. */
        val ipv6Supported: Boolean,
        /** Global/ULA IPv6 addresses to serve and advertise after the IPv4 host (never link-local); empty for IPv4-only. */
        val ipv6Addresses: List<Inet6Address>,
    ) {
        val dualStack: Boolean get() = mode == MODE_DUAL_STACK
    }

    /** The pure decision. IPv4-only unless IPv6 is clearly usable; IPv4 always first. */
    fun decide(probe: Probe, memory: UnitRecord?): Decision {
        val usable = probe.candidates.filter { it.usable }
        val globals = usable.filter { !it.linkLocal }
        val probeReason = when {
            !probe.ipv4Present -> REASON_NO_IPV4
            probe.ipv6Disabled == true -> REASON_IPV6_DISABLED
            probe.candidates.isEmpty() -> REASON_NO_IPV6
            usable.isEmpty() -> REASON_IPV6_NOT_USABLE
            globals.isEmpty() -> REASON_LINK_LOCAL_ONLY
            probe.ipv6Route == false -> REASON_NO_IPV6_ROUTE
            else -> REASON_IPV6_USABLE
        }
        val supported = probeReason == REASON_IPV6_USABLE
        fun ipv4(reason: String) = Decision(MODE_IPV4_ONLY, reason, supported, emptyList())
        return when {
            !supported -> ipv4(probeReason)
            memory?.mode == MODE_IPV4_ONLY -> ipv4(REASON_REMEMBERED_IPV4)
            else -> Decision(
                MODE_DUAL_STACK,
                if (memory?.mode == MODE_DUAL_STACK) REASON_REMEMBERED_DUAL else REASON_IPV6_USABLE,
                ipv6Supported = true,
                // Global/ULA only: a link-local address must never be advertised (the CS75 stall, #93).
                ipv6Addresses = globals.map { it.address }.distinct(),
            )
        }
    }

    /**
     * Stable, non-reversible per-unit key: SSID + AP interface + device model. The AP MAC is left
     * out on purpose: newer Android randomizes the SoftAP MAC, which would forget a fallback.
     */
    fun unitKey(ssid: String?, iface: String?, model: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${ssid.orEmpty()}|${iface.orEmpty()}|$model".toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Per-unit memory over a tiny key/value store (SharedPreferences in production, a map in tests). */
    class Memory(private val store: Store, private val now: () -> Long = System::currentTimeMillis) {
        interface Store {
            fun get(key: String): String?
            fun put(key: String, value: String?)
        }

        fun read(unit: String): UnitRecord? {
            val record = store.get("unit.$unit")?.let(::parse) ?: return null
            if (record.mode == MODE_IPV4_ONLY &&
                (now() - record.decidedAtMillis > REMEMBER_IPV4_ONLY_MILLIS || record.ipv4Failures >= REPROBE_AFTER_FAILURES)
            ) {
                store.put("unit.$unit", null) // re-probe
                return null
            }
            return record
        }

        /** A dual-stack session failed (no TCP, or stuck before the picture): IPv4-only from now on. */
        fun recordIpv6Failure(unit: String, reason: String): UnitRecord =
            UnitRecord(MODE_IPV4_ONLY, reason, now()).also { write(unit, it) }

        /** The picture arrived. Dual stack is remembered as proven; IPv4-only verdicts are kept. */
        fun recordSuccess(unit: String, mode: String) {
            val existing = store.get("unit.$unit")?.let(::parse)
            if (mode == MODE_DUAL_STACK) {
                write(unit, UnitRecord(MODE_DUAL_STACK, REASON_REMEMBERED_DUAL, now()))
            } else if (existing != null && existing.ipv4Failures != 0) {
                write(unit, existing.copy(ipv4Failures = 0))
            }
        }

        /** An IPv4-only session failed; after [REPROBE_AFTER_FAILURES] in a row the verdict is re-probed. */
        fun recordIpv4Failure(unit: String) {
            val existing = store.get("unit.$unit")?.let(::parse) ?: return
            if (existing.mode != MODE_IPV4_ONLY) return
            write(unit, existing.copy(ipv4Failures = existing.ipv4Failures + 1))
        }

        private fun write(unit: String, record: UnitRecord) =
            store.put("unit.$unit", "${record.mode}|${record.reason}|${record.decidedAtMillis}|${record.ipv4Failures}")

        private fun parse(text: String): UnitRecord? {
            val parts = text.split('|')
            if (parts.size < 4) return null
            return UnitRecord(parts[0], parts[1], parts[2].toLongOrNull() ?: return null, parts[3].toIntOrNull() ?: 0)
        }
    }

    fun memory(context: Context): Memory = Memory(PrefsStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)))

    private class PrefsStore(private val prefs: SharedPreferences) : Memory.Store {
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    }

    // ---- The Android probe (not unit-tested: it reads the live interface). ----

    /**
     * Reads the AP interface: addresses, kernel flags when readable, a bind+connect test per IPv6 address.
     * [addresses] must be the interface's live addresses with IPv6 included; the advertised host
     * list is IPv4-only on this path (#93) and would make every probe come back "no IPv6".
     * [bindCheck] and [readFile] are injectable for tests.
     */
    fun probe(
        iface: String?,
        addresses: List<InetAddress>,
        bindCheck: (Inet6Address) -> Boolean = ::selfConnect,
        readFile: (String) -> String? = ::readText,
    ): Probe {
        val ipv4 = addresses.any { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        val flags = iface?.let { name -> readFile("/proc/net/if_inet6")?.let { parseIfInet6(it, name) } }.orEmpty()
        val candidates = addresses.filterIsInstance<Inet6Address>()
            .filter { !it.isLoopbackAddress && !it.isMulticastAddress && !it.isAnyLocalAddress }
            .distinct()
            .map { address ->
                val f = flags[hex(address)]
                Ipv6Candidate(
                    address = address,
                    tentative = f?.let { it and IFA_F_TENTATIVE != 0 },
                    dadFailed = f?.let { it and IFA_F_DADFAILED != 0 },
                    deprecated = f?.let { it and IFA_F_DEPRECATED != 0 },
                    bindOk = bindCheck(address),
                )
            }
        val disabled = iface?.let { readFile("/proc/sys/net/ipv6/conf/$it/disable_ipv6")?.trim() }?.let { it == "1" }
        val route = iface?.let { name -> readFile("/proc/net/ipv6_route")?.let { hasGlobalRoute(it, name) } }
        return Probe(ipv4, candidates, disabled, route)
    }

    private const val IFA_F_DADFAILED = 0x08
    private const val IFA_F_DEPRECATED = 0x20
    private const val IFA_F_TENTATIVE = 0x40

    /** `/proc/net/if_inet6`: address(hex) index prefixlen scope flags name → flags by address. */
    internal fun parseIfInet6(text: String, iface: String): Map<String, Int> =
        text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 6 || parts[5] != iface) null
            else parts[0].lowercase() to (parts[4].toIntOrNull(16) ?: return@mapNotNull null)
        }.toMap()

    /** `/proc/net/ipv6_route`: a route on [iface] whose destination is not fe80::/10, ff00::/8 or ::/128 loopback. */
    internal fun hasGlobalRoute(text: String, iface: String): Boolean =
        text.lineSequence().any { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 10 || parts[9] != iface) return@any false
            val dest = parts[0].lowercase()
            val prefixLen = parts[1].toIntOrNull(16) ?: return@any false
            prefixLen in 1..128 && !dest.startsWith("fe8") && !dest.startsWith("fe9") &&
                !dest.startsWith("fea") && !dest.startsWith("feb") && !dest.startsWith("ff")
        }

    private fun readText(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    private fun hex(address: Inet6Address): String =
        address.address.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Binds the address and connects to it: a tentative / unusable address or a broken scope fails. */
    private fun selfConnect(address: Inet6Address): Boolean = runCatching {
        ServerSocket().use { server ->
            server.bind(InetSocketAddress(address, 0), 1)
            Socket().use { client ->
                client.connect(InetSocketAddress(address, server.localPort), 500)
                client.isConnected
            }
        }
    }.getOrDefault(false)
}
