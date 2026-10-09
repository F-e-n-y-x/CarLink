package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.network.HotspotAddressPolicy.FAMILY_IPV4
import com.shilapi.xcertplay.network.HotspotAddressPolicy.FAMILY_IPV6
import com.shilapi.xcertplay.network.WirelessAddressFamilyPlan.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// CarLink: Wi-Fi Direct tries IPv6 link-local first, with a quick fallback to IPv4.
class WirelessAddressFamilyPlanTest {
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
        for (backend in listOf(WirelessHotspotBackend.MANUAL_HOTSPOT, WirelessHotspotBackend.EXISTING_WIFI,
                WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT)) {
            assertEquals(Choice(null, guessed = false), WirelessAddressFamilyPlan.choose(backend, null, 36))
        }
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
}
