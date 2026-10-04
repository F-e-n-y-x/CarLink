package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Use the same scoped, link-local path for manual APs as for Wi-Fi Direct. */
internal fun wirelessHostAddress(addresses: List<InetAddress>, interfaceIndex: Int): InetAddress? {
    if (interfaceIndex > 0) {
        addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }?.let {
            return Inet6Address.getByAddress(null, it.address, interfaceIndex)
        }
    }
    return addresses.firstOrNull {
        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
            !it.isAnyLocalAddress && !it.isMulticastAddress
    }
}

/** Station LAN discovery must cover IPv4 multicast as well as scoped link-local IPv6. */
internal fun existingWifiHostAddresses(addresses: List<InetAddress>, interfaceIndex: Int): List<InetAddress> {
    val ipv4 = addresses.firstOrNull {
        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
            !it.isAnyLocalAddress && !it.isMulticastAddress
    }
    val ipv6 = wirelessHostAddress(addresses, interfaceIndex) as? Inet6Address
    return listOfNotNull(ipv4, ipv6)
}
