package com.andrerinas.openheadunit.aap

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class LocalHotspotPolicyTest {
    private fun net(name: String, ip: String, up: Boolean = true) = ApInterfaceCandidate(name, false, up, ip)

    @Test fun keepsFiveGhzOnTheStationChannelAndDefaultsTo36WithoutAStation() {
        assertEquals(40, LocalHotspotPolicy.preferredFiveGhzChannel(5200))
        assertEquals(149, LocalHotspotPolicy.preferredFiveGhzChannel(5745))
        assertEquals(36, LocalHotspotPolicy.preferredFiveGhzChannel(null))
        assertEquals(36, LocalHotspotPolicy.preferredFiveGhzChannel(-1))
        assertEquals(36, LocalHotspotPolicy.preferredFiveGhzChannel(2437))
    }

    @Test fun neverAdvertiseExistingStationOrForeignP2pOrCellularNetwork() {
        val existing = net("wlan0", "192.168.1.25")
        val ap = net("wlan1", "192.168.43.1")
        val candidates = listOf(existing, net("p2p0", "192.168.49.1"), net("seth_lte0", "10.0.1.1"), ap)
        assertEquals(ap, LocalHotspotPolicy.pick(candidates, setOf("192.168.1.25")))
        assertNull(LocalHotspotPolicy.pick(candidates - ap, setOf("192.168.1.25")))
    }

    @Test fun ambiguityAndDownInterfacesDoNotPublishCredentials() {
        assertNull(LocalHotspotPolicy.pick(listOf(net("wlan1", "192.168.43.1"), net("ap0", "192.168.44.1")), emptySet()))
        assertNull(LocalHotspotPolicy.pick(listOf(net("wlan1", "192.168.43.1", false)), emptySet()))
        assertNull(LocalHotspotPolicy.pick(listOf(net("apcli0", "192.168.43.1")), emptySet()))
    }

    @Test fun stationDhcpChangeDuringStartupIsNeverAdvertisedAsTheAp() {
        val station = net("wlan0", "192.168.1.26")
        assertNull(LocalHotspotPolicy.pick(listOf(station), setOf("192.168.1.25"), setOf("wlan0")))
        val ap = net("ap0", "192.168.43.1")
        assertEquals(ap, LocalHotspotPolicy.pick(listOf(station, ap), setOf("192.168.1.25"), setOf("wlan0")))
    }

    @Test fun derivesMacOnlyFromLinkLocalModifiedEui64() {
        assertEquals("00:11:22:33:44:55", LocalHotspotPolicy.eui64Mac(InetAddress.getByName("fe80::211:22ff:fe33:4455").address))
        assertNull(LocalHotspotPolicy.eui64Mac(InetAddress.getByName("2001::211:22ff:fe33:4455").address))
        assertNull(LocalHotspotPolicy.eui64Mac(InetAddress.getByName("fe80::1234:5678:1234:5678").address))
    }

    @Test fun localReservationNeverUsesGlobalHotspotTeardownOrP2p() {
        val transport = NativeTransport.fromSetting(2)
        assertEquals(NativeTransport.LOCAL_HOTSPOT, transport)
        assertFalse(WifiModePolicy.usesWifiDirect(3, 0, transport))
        assertFalse(UserExitHotspotPolicy.usesHeadUnitHotspot(3, 0, transport))
        assertFalse(LinkLossTeardownPolicy.shouldTearDown(LinkLossTrigger.WIFI_STATION_DISABLING, 3, 0, transport))
        assertEquals(UnusableBssidAction.ABORT, NativeCredentialsPolicy.onUnusableBssid(transport))
    }
}
