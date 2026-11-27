package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

// CarLink: new file (see docs/VENDOR_CHANGES.md). #93: car-hotspot media listeners bind the session's IPv4.
class AirPlayMediaBindTest {
    private val wildcard = InetAddress.getByName("::")
    private val ipv4 = InetAddress.getByName("192.168.43.1")
    private val linkLocal = InetAddress.getByName("fe80::1")

    @Test fun carHotspotBindsTheSessionsIpv4() {
        assertEquals(ipv4, mediaBindAddress(ipv4, ipv4Only = true))
    }

    // CarLink: the head unit joined an iPhone's Personal Hotspot (existing Wi-Fi): an IPv4 session binds its IPv4.
    @Test fun iphonePersonalHotspotSessionBindsItsIpv4() {
        val onIphoneHotspot = InetAddress.getByName("172.20.10.4")
        assertEquals(onIphoneHotspot, mediaBindAddress(onIphoneHotspot, ipv4Only = true))
    }

    @Test fun everyOtherCaseKeepsTheWildcard() {
        assertEquals(wildcard, mediaBindAddress(ipv4, ipv4Only = false))
        assertEquals(wildcard, mediaBindAddress(linkLocal, ipv4Only = true))
        assertEquals(wildcard, mediaBindAddress(null, ipv4Only = true))
    }
}
