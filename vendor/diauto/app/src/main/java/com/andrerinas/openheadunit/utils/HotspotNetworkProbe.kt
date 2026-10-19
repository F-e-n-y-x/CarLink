package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * Read-only snapshot of how this device's hotspot and network interfaces look to the app, for the
 * diagnostic report. It exists for units where the framework says the hotspot is enabled but no
 * interface looks like an access point, which the connection log alone cannot explain because it
 * lists only interfaces that are up.
 *
 * It never changes a setting or starts anything. Addresses are classified, not printed, and the
 * hotspot name and passphrase are never read into the report.
 */
internal object HotspotNetworkProbe {
    private const val WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"

    fun report(context: Context): List<String> {
        val out = mutableListOf<String>()
        section(out, "Soft AP state") { listOf("framework state=${SoftApStateReader.read(context)}") }
        section(out, "All network interfaces (including down)") { interfaces() }
        section(out, "Sticky hotspot broadcast") { stickyExtras(context, WIFI_AP_STATE_CHANGED) }
        section(out, "Sticky tethering broadcast") { stickyExtras(context, TETHER_STATE_CHANGED) }
        section(out, "Tethering queries") { tetheringQueries(context) }
        section(out, "Hotspot configuration (band/channel only)") { apBandAndChannel(context) }
        section(out, "Kernel interface names") { kernelInterfaceNames() }
        return out
    }

    private fun section(out: MutableList<String>, title: String, block: () -> List<String>) {
        out += "[$title]"
        val lines = try {
            block().ifEmpty { listOf("(none)") }
        } catch (t: Throwable) {
            listOf("(unavailable: ${t.javaClass.simpleName}: ${t.message})")
        }
        out += lines.map { "  $it" }
    }

    private fun interfaces(): List<String> =
        NetworkInterface.getNetworkInterfaces().toList().sortedBy { it.name }.map { nif ->
            val flags = listOfNotNull(
                if (safe { nif.isUp } == true) "up" else "down",
                "loopback".takeIf { safe { nif.isLoopback } == true },
                "virtual".takeIf { nif.isVirtual },
                "p2p".takeIf { safe { nif.isPointToPoint } == true },
            )
            val addresses = nif.interfaceAddresses.mapNotNull { ia ->
                when (val a = ia.address) {
                    is Inet4Address -> "ipv4/${ia.networkPrefixLength} " + when {
                        a.isSiteLocalAddress -> "private"
                        a.isLinkLocalAddress -> "link-local"
                        a.isLoopbackAddress -> "loopback"
                        else -> "other"
                    }
                    is Inet6Address -> if (a.isLinkLocalAddress) "ipv6 link-local" else "ipv6"
                    else -> null
                }
            }
            val mac = safe { nif.hardwareAddress }?.let { "mac=yes" } ?: "mac=no"
            "${nif.name} [${flags.joinToString()}] mtu=${safe { nif.mtu } ?: "?"} $mac addresses=${addresses.ifEmpty { listOf("none") }.joinToString()}"
        }

    private fun stickyExtras(context: Context, action: String): List<String> {
        // A null receiver only reads the last sticky broadcast; nothing stays registered.
        val intent: Intent = ContextCompat.registerReceiver(
            context, null, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED
        ) ?: return listOf("no sticky $action")
        val extras = intent.extras ?: return listOf("no extras")
        return extras.keySet().sorted().map { key ->
            @Suppress("DEPRECATION")
            val value = extras.get(key)
            val text = when (value) {
                is Array<*> -> value.joinToString(prefix = "[", postfix = "]")
                is ArrayList<*> -> value.joinToString(prefix = "[", postfix = "]")
                else -> value.toString()
            }
            // Interface names, states and modes only; nothing here carries credentials.
            "$key=$text"
        }
    }

    private fun tetheringQueries(context: Context): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return listOf("getTetheredIfaces", "getTetherableIfaces", "getTetheringErroredIfaces", "getTetherableWifiRegexs")
            .map { name ->
                val result = try {
                    val value = cm.javaClass.getMethod(name).invoke(cm)
                    (value as? Array<*>)?.joinToString(prefix = "[", postfix = "]") ?: value.toString()
                } catch (t: Throwable) {
                    val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                    "unavailable (${cause.javaClass.simpleName})"
                }
                "$name=$result"
            }
    }

    private fun apBandAndChannel(context: Context): List<String> {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val config: Any
        try {
            config = wm.javaClass.getMethod("getWifiApConfiguration").invoke(wm)
                ?: return listOf("no configuration returned")
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            return listOf("getWifiApConfiguration unavailable (${cause.javaClass.simpleName}: ${cause.message})")
        }
        return listOf("apBand", "apChannel").map { field ->
            "$field=" + (runCatching { config.javaClass.getField(field).get(config) }.getOrNull() ?: "?")
        }
    }

    private fun kernelInterfaceNames(): List<String> {
        val lines = mutableListOf<String>()
        lines += "/sys/class/net: " + (File("/sys/class/net").list()?.sorted()?.joinToString() ?: "not readable")
        lines += "/proc/net/dev: " + (runCatching {
            File("/proc/net/dev").readLines().drop(2).map { it.substringBefore(':').trim() }.sorted().joinToString()
        }.getOrNull() ?: "not readable")
        return lines
    }

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (_: Exception) { null }
}
