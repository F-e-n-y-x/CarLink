package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.network.HotspotAddressPolicy.FAMILY_IPV4
import com.shilapi.xcertplay.network.HotspotAddressPolicy.FAMILY_IPV6
import com.shilapi.xcertplay.network.WirelessAddressFamilyPlan.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// CarLink: Wi-Fi Direct tries IPv6 link-local first, with a quick fallback to IPv4.
class WirelessAddressFamilyPlanTest {
    // The experiment is opt-in (CarLink); these tests cover its behaviour when switched on.
    @org.junit.Before fun enableGuess() { WirelessAddressFamilyPlan.ipv6Guess = true }
    @org.junit.After fun restore() { WirelessAddressFamilyPlan.ipv6Guess = false }

    @Test fun offByDefaultWifiDirectKeepsIpv4First() {
        WirelessAddressFamilyPlan.ipv6Guess = false
        val choice = WirelessAddressFamilyPlan.choose(WirelessHotspotBackend.WIFI_P2P, null, 36)
        org.junit.Assert.assertFalse(choice.guessed)
    }

    @Test fun wifiDirectWithoutMemoryGuessesIpv6OnAndroid13AndLater() {
        assertEquals(Choice(FAMILY_IPV6, guessed = true),
            WirelessAddressFamilyPlan.choose(WirelessHotspotBackend.WIFI_P2P, null, 36))
        assertEquals(Choice(FAMILY_IPV6, guessed = true),
            WirelessAddressFamilyPlan.choose(WirelessHotspotBackend.WIFI_P2P, null, 33))
    }

    @Test fun olderAndroidAndOtherBackendsKeepUpstreamsIpv4First() {
        // #283: the Android 9 link-local reply route failed; car hotspot and LAN are unchanged.
        assertEquals(Choice(null, guessed = false),
            WirelessAddressFamilyPlan.choose(WirelessHotspotBackend.WIFI_P2P, null, 28))
        assertEquals(Choice(null, guessed = false),
            WirelessAddressFamilyPlan.choose(WirelessHotspotBackend.EXISTING_WIFI, null, 36))
    }

    // CarLink #93: the car hotspot and local-only AP are IPv4 end to end.
    @Test fun carHotspotIgnoresARememberedIpv6Family() {
        for (backend in listOf(WirelessHotspotBackend.MANUAL_HOTSPOT, WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT)) {
            for (remembered in listOf(null, FAMILY_IPV4, FAMILY_IPV6)) {
                assertEquals(Choice(FAMILY_IPV4, guessed = false), WirelessAddressFamilyPlan.choose(backend, remembered, 36))
            }
            // IPv4 is never a guess, so a first TCP on it is never stored as a remembered family.
            assertNull(WirelessAddressFamilyPlan.familyToRemember(
                WirelessAddressFamilyPlan.choose(backend, null, 36), FAMILY_IPV4, FAMILY_IPV4))
        }
    }

    @Test fun sharedWifiAndWifiDirectStillHonourARememberedIpv6Family() {
        for (backend in listOf(WirelessHotspotBackend.EXISTING_WIFI, WirelessHotspotBackend.WIFI_P2P)) {
            assertEquals(Choice(FAMILY_IPV6, guessed = false), WirelessAddressFamilyPlan.choose(backend, FAMILY_IPV6, 36))
        }
    }

    @Test fun carHotspotUsesOnlyIpv4Addresses() {
        val all = listOf(v6, v4, v6Global)
        assertEquals(listOf(v4), WirelessAddressFamilyPlan.usable(WirelessHotspotBackend.MANUAL_HOTSPOT, all))
        assertEquals(listOf(v4), WirelessAddressFamilyPlan.usable(WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT, all))
        assertEquals(all, WirelessAddressFamilyPlan.usable(WirelessHotspotBackend.EXISTING_WIFI, all))
        assertEquals(all, WirelessAddressFamilyPlan.usable(WirelessHotspotBackend.WIFI_P2P, all))
        // With IPv6 only, the car hotspot has no host yet: the address wait looks for IPv4.
        assertEquals(emptyList<java.net.InetAddress>(),
            WirelessAddressFamilyPlan.usable(WirelessHotspotBackend.MANUAL_HOTSPOT, listOf(v6)))
    }

    @Test fun carHotspotInfoDropsItsIpv6Host() {
        fun info(backend: WirelessHotspotBackend, host: java.net.InetAddress?, hosts: List<java.net.InetAddress>) =
            WirelessHotspotInfo("car", "secret", com.shilapi.xcertplay.transport.Iap2WirelessSecurity.WPA_WPA2, 6,
                2437, null, "wlan0", host, "2.4 GHz", backend, hosts)
        val ap = WirelessAddressFamilyPlan.forPath(info(WirelessHotspotBackend.MANUAL_HOTSPOT, v6, listOf(v6, v4)))
        assertEquals(v4, ap.hostAddress)
        assertEquals(listOf(v4), ap.hostAddresses)
        assertEquals("wlan0", ap.interfaceName)
        assertNull(WirelessAddressFamilyPlan.forPath(info(WirelessHotspotBackend.MANUAL_HOTSPOT, v6, listOf(v6))).hostAddress)
        val lan = info(WirelessHotspotBackend.EXISTING_WIFI, v6, listOf(v6, v4))
        org.junit.Assert.assertSame(lan, WirelessAddressFamilyPlan.forPath(lan))
    }

    // CarLink: the car hotspot's automatic choice (CarHotspotIpFamily) can make a run dual stack.
    @Test fun aDualStackCarHotspotRunKeepsGlobalIpv6ButNeverLinkLocal() {
        val all = listOf(v6, v4, v6Global)
        assertEquals(listOf(v4, v6Global),
            WirelessAddressFamilyPlan.usable(WirelessHotspotBackend.MANUAL_HOTSPOT, all, dualStack = true))
        assertEquals(listOf(v4), WirelessAddressFamilyPlan.usable(WirelessHotspotBackend.MANUAL_HOTSPOT, all, dualStack = false))
        assertEquals(all, WirelessAddressFamilyPlan.usable(WirelessHotspotBackend.WIFI_P2P, all, dualStack = true))
        assertEquals(listOf(v4, v6Global), WirelessAddressFamilyPlan.withoutLinkLocalIpv6(all))
    }

    @Test fun aDualStackCarHotspotInfoKeepsAnIpv4HostFirstAndItsGlobalIpv6() {
        fun info(host: java.net.InetAddress?, hosts: List<java.net.InetAddress>) =
            WirelessHotspotInfo("car", "secret", com.shilapi.xcertplay.transport.Iap2WirelessSecurity.WPA_WPA2, 6,
                2437, null, "wlan0", host, "2.4 GHz", WirelessHotspotBackend.MANUAL_HOTSPOT, hosts)
        val ap = WirelessAddressFamilyPlan.forPath(info(v6Global, listOf(v6, v6Global, v4)), dualStack = true)
        assertEquals(v4, ap.hostAddress)
        assertEquals(listOf(v6Global, v4), ap.hostAddresses)
        // Local-only AP is never dual stack: the decision is only made for the car hotspot.
        val lo = WirelessAddressFamilyPlan.forPath(
            WirelessHotspotInfo("car", "secret", com.shilapi.xcertplay.transport.Iap2WirelessSecurity.WPA_WPA2, 6,
                2437, null, "wlan0", v4, "2.4 GHz", WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT, listOf(v4, v6Global)))
        assertEquals(listOf(v4), lo.hostAddresses)
    }

    @Test fun mediaSocketsBindToAnIpv4SessionOnEveryInfrastructureBackend() {
        // #93's Picture stall, on the car hotspot and on an iPhone's Personal Hotspot (existing Wi-Fi) alike.
        for (backend in listOf(WirelessHotspotBackend.MANUAL_HOTSPOT, WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT,
            WirelessHotspotBackend.EXISTING_WIFI)) {
            assertEquals(backend.name, true, WirelessAddressFamilyPlan.bindMediaToSessionIpv4(backend))
        }
        assertEquals(false, WirelessAddressFamilyPlan.bindMediaToSessionIpv4(WirelessHotspotBackend.WIFI_P2P))
    }

    @Test fun aRememberedFamilyWinsAndIsNotAGuess() {
        // After a guessed IPv6 timed out, upstream's flip remembers IPv4.
        assertEquals(Choice(FAMILY_IPV4, guessed = false),
            WirelessAddressFamilyPlan.choose(WirelessHotspotBackend.WIFI_P2P, FAMILY_IPV4, 36))
        assertEquals(Choice(FAMILY_IPV6, guessed = false),
            WirelessAddressFamilyPlan.choose(WirelessHotspotBackend.WIFI_P2P, FAMILY_IPV6, 36))
    }

    @Test fun onlyAPublishedGuessGetsTheShortFirstTcpDeadline() {
        val guess = Choice(FAMILY_IPV6, guessed = true)
        assertEquals(10_000L, WirelessAddressFamilyPlan.firstTcpTimeoutMillis(guess, FAMILY_IPV6))
        // No link-local address appeared, so IPv4 was published: the normal deadline.
        assertEquals(WirelessStartupPolicy.FIRST_TCP_MILLIS,
            WirelessAddressFamilyPlan.firstTcpTimeoutMillis(guess, FAMILY_IPV4))
        assertEquals(WirelessStartupPolicy.FIRST_TCP_MILLIS,
            WirelessAddressFamilyPlan.firstTcpTimeoutMillis(Choice(FAMILY_IPV6, guessed = false), FAMILY_IPV6))
        assertEquals(WirelessStartupPolicy.FIRST_TCP_MILLIS,
            WirelessAddressFamilyPlan.firstTcpTimeoutMillis(Choice(null, guessed = false), FAMILY_IPV4))
    }

    @Test fun aGuessIsRememberedOnlyWhenTheIphoneConnectedOverIt() {
        val guess = Choice(FAMILY_IPV6, guessed = true)
        assertEquals(FAMILY_IPV6, WirelessAddressFamilyPlan.familyToRemember(guess, FAMILY_IPV6, FAMILY_IPV6))
        assertNull(WirelessAddressFamilyPlan.familyToRemember(guess, FAMILY_IPV6, FAMILY_IPV4))
        assertNull(WirelessAddressFamilyPlan.familyToRemember(guess, FAMILY_IPV6, null))
        assertNull(WirelessAddressFamilyPlan.familyToRemember(guess, FAMILY_IPV4, FAMILY_IPV4))
        assertNull(WirelessAddressFamilyPlan.familyToRemember(
            Choice(FAMILY_IPV4, guessed = false), FAMILY_IPV4, FAMILY_IPV4))
    }

    // Upstream DiPlay #1344: an offered fe80:: next to IPv4 pulled the iPhone onto a link-local path with no reply route.
    private val v4 = java.net.InetAddress.getByName("192.168.43.1")
    private val v6 = java.net.Inet6Address.getByAddress(null, java.net.InetAddress.getByName("fe80::1").address, 5)
    private val v6Global = java.net.InetAddress.getByName("2001:db8::1")

    @Test fun endpointLeavesOutLinkLocalIpv6NextToIpv4() {
        assertEquals(listOf(v4, v6Global), WirelessAddressFamilyPlan.endpointAddresses(v4, listOf(v4, v6, v6Global)))
    }

    @Test fun endpointKeepsAPublishedLinkLocalAddressFirst() {
        assertEquals(listOf(v6, v4), WirelessAddressFamilyPlan.endpointAddresses(v6, listOf(v4, v6)))
    }

    @Test fun endpointKeepsLinkLocalWhenThereIsNoIpv4() {
        assertEquals(listOf(v6Global, v6), WirelessAddressFamilyPlan.endpointAddresses(v6Global, listOf(v6, v6Global)))
    }
}
