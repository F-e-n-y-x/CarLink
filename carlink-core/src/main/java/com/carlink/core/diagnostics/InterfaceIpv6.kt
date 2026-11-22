package com.carlink.core.diagnostics

import java.io.File

/**
 * Whether a network interface carries IPv6, for the log (#93: wireless CarPlay on an IPv4-only car hotspot).
 * Reads `/proc/sys/net/ipv6/conf/<iface>/disable_ipv6` and `/proc/net/if_inet6`; either may be unreadable to an
 * app on newer Android, which the line then says.
 */
object InterfaceIpv6 {
    /** One log line for [iface], e.g. `wlan0 IPv6: disable_ipv6=1 addresses=0 -> IPv4-only`. */
    fun describe(iface: String): String =
        describe(iface, read("/proc/sys/net/ipv6/conf/$iface/disable_ipv6"), read("/proc/net/if_inet6"))

    /** [disableIpv6] and [ifInet6] are the two files' text, null when unreadable. */
    fun describe(iface: String, disableIpv6: String?, ifInet6: String?): String {
        val disabled = disableIpv6?.trim()
        // if_inet6: "<32 hex address> <ifindex> <prefix> <scope> <flags> <name>"; scope 20 is link-local.
        val rows = ifInet6?.lines()?.map { it.trim().split(Regex("\\s+")) }?.filter { it.size >= 6 && it[5] == iface }
        val linkLocal = rows?.count { it[3] == "20" }
        val verdict = when {
            disabled == "1" || rows?.isEmpty() == true -> "IPv4-only"
            disabled == "0" && rows != null -> "has IPv6"
            else -> "unknown"
        }
        return "$iface IPv6: disable_ipv6=${disabled ?: "unreadable"} " +
            "addresses=${rows?.size ?: "unreadable"}${linkLocal?.let { " (link-local $it)" } ?: ""} -> $verdict"
    }

    private fun read(path: String): String? = runCatching { File(path).readText() }.getOrNull()
}
