package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

// CarLink: new file (see docs/VENDOR_CHANGES.md). #93: the car hotspot is never a modem interface, and a
// usable IPv4 wins a tie.
class CarHotspotInterfaceChoiceTest {
    private val ipv4 = InetAddress.getByName("192.168.43.1")
    private val modemIpv4 = InetAddress.getByName("10.12.0.5")
    private val linkLocal = InetAddress.getByName("fe80::1234")
    private fun iface(name: String, addresses: List<InetAddress>, index: Int = 7) =
        HotspotInterfaceSnapshot(name, index, true, addresses, wirelessInterfaceName(name))
    private fun select(vararg interfaces: HotspotInterfaceSnapshot, ap: Set<String>?) =
        selectHotspotInterface(HotspotNetworkSnapshot(interfaces.toList(), ap, emptySet(), null)) {}

    @Test fun wronglyListedMobileDataDoesNotBeatWlan0() {
        val modem = iface("rmnet_data0", listOf(InetAddress.getByName("192.168.100.2")), index = 20)
        assertEquals("wlan0", select(modem, iface("wlan0", listOf(ipv4)), ap = setOf("rmnet_data0", "wlan0"))?.name)
    }

    @Test fun aSetListingOnlyAModemReadsAsUnobservable() {
        // Otherwise wlan0 would be "not_platform_ap" and the car hotspot would never be ready.
        for (modem in listOf("rmnet_data0", "r_rmnet_data1", "ccmni0")) {
            assertEquals("wlan0", select(iface(modem, listOf(modemIpv4)), iface("wlan0", listOf(ipv4)), ap = setOf(modem))?.name)
        }
    }

    @Test fun aModemIsNeverTheHotspot() {
        assertNull(select(iface("rmnet_data0", listOf(InetAddress.getByName("192.168.100.2"))), ap = null))
    }

    @Test fun aUsableIpv4WinsATie() {
        // "ap0" sorts first by name but has only a link-local address.
        assertEquals("wlan1", select(iface("ap0", listOf(linkLocal)), iface("wlan1", listOf(ipv4)), ap = null)?.name)
        assertEquals("wlan1", select(iface("ap0", listOf(linkLocal)), iface("wlan1", listOf(ipv4)),
            ap = setOf("ap0", "wlan1"))?.name)
    }
}
