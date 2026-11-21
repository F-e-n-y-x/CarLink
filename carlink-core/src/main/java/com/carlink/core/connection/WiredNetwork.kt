package com.carlink.core.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** The screen's wired LAN (a USB Ethernet adapter), for CarPlay's Same Wi-Fi over a cable (GitHub #87). */
object WiredNetwork {
    /** The interface name (e.g. "eth0") of an Ethernet network with an IPv4 address, or null. */
    @Suppress("DEPRECATION") // allNetworks: still the way to list every network on API 25+.
    fun interfaceName(context: Context): String? {
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching {
            connectivity.allNetworks.firstNotNullOfOrNull { network ->
                val ethernet = connectivity.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                val properties = connectivity.getLinkProperties(network)
                properties?.interfaceName?.takeIf { ethernet && properties.linkAddresses.any { it.address is Inet4Address } }
            }
        }.getOrNull()
    }
}
