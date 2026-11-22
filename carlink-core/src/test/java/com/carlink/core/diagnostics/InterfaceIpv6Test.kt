package com.carlink.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class InterfaceIpv6Test {
    private val ifInet6 = """
        00000000000000000000000000000001 01 80 10 80       lo
        fe80000000000000a8bbccfffe001122 1e 40 20 80    wlan0
        fe80000000000000a8bbccfffe003344 09 40 20 80 rmnet_data0
    """.trimIndent()

    @Test fun disabledIpv6IsIpv4Only() {
        assertEquals("ap0 IPv6: disable_ipv6=1 addresses=0 (link-local 0) -> IPv4-only",
            InterfaceIpv6.describe("ap0", "1\n", ifInet6))
    }

    @Test fun noIpv6AddressIsIpv4OnlyEvenWhenNotDisabled() {
        assertEquals("ap0 IPv6: disable_ipv6=0 addresses=0 (link-local 0) -> IPv4-only",
            InterfaceIpv6.describe("ap0", "0", ifInet6))
    }

    @Test fun aLinkLocalAddressMeansIpv6IsThere() {
        assertEquals("wlan0 IPv6: disable_ipv6=0 addresses=1 (link-local 1) -> has IPv6",
            InterfaceIpv6.describe("wlan0", "0", ifInet6))
    }

    @Test fun unreadableFilesSaySo() {
        assertEquals("wlan0 IPv6: disable_ipv6=unreadable addresses=unreadable -> unknown",
            InterfaceIpv6.describe("wlan0", null, null))
        assertEquals("wlan0 IPv6: disable_ipv6=0 addresses=unreadable -> unknown",
            InterfaceIpv6.describe("wlan0", "0", null))
    }
}
