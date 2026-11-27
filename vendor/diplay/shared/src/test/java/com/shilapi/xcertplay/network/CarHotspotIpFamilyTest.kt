package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.network.CarHotspotIpFamily.Ipv6Candidate
import com.shilapi.xcertplay.network.CarHotspotIpFamily.MODE_DUAL_STACK
import com.shilapi.xcertplay.network.CarHotspotIpFamily.MODE_IPV4_ONLY
import com.shilapi.xcertplay.network.CarHotspotIpFamily.Probe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

// CarLink: automatic IPv4 / IPv4+IPv6 choice for the car hotspot.
class CarHotspotIpFamilyTest {
    private fun v6(text: String) = InetAddress.getByName(text) as Inet6Address
    private fun linkLocal(scope: Int) = Inet6Address.getByAddress(null,
        v6("fe80::1234:56ff:fe78:9abc").address, scope)

    private val global = Ipv6Candidate(v6("2001:db8:1::1"), tentative = false, dadFailed = false, deprecated = false, bindOk = true)
    private val scopedLinkLocal = Ipv6Candidate(linkLocal(12), tentative = false, dadFailed = false, deprecated = false, bindOk = true)

    /** A SoftAP like the Changan CS75 Plus 2023's (#93): 192.168.43.1 on wlan0 plus only a link-local fe80:: address. */
    private val cs75Probe = Probe(ipv4Present = true, candidates = listOf(scopedLinkLocal), ipv6Disabled = false, ipv6Route = false)
    private val dualProbe = Probe(ipv4Present = true, candidates = listOf(global, scopedLinkLocal), ipv6Disabled = false, ipv6Route = true)

    private class MapStore : CarHotspotIpFamily.Memory.Store {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    // ---- Probe decisions ----

    @Test fun cs75LinkLocalOnlyStaysIpv4OnlyOnTheFirstTry() {
        val decision = CarHotspotIpFamily.decide(cs75Probe, memory = null)
        assertEquals(MODE_IPV4_ONLY, decision.mode)
        assertEquals(CarHotspotIpFamily.REASON_LINK_LOCAL_ONLY, decision.reason)
        assertFalse(decision.ipv6Supported)
        assertTrue(decision.ipv6Addresses.isEmpty())
    }

    @Test fun noIpv6AtAllIsIpv4Only() {
        val d = CarHotspotIpFamily.decide(Probe(true, emptyList()), null)
        assertEquals(MODE_IPV4_ONLY, d.mode)
        assertEquals(CarHotspotIpFamily.REASON_NO_IPV6, d.reason)
    }

    @Test fun disabledTentativeDeprecatedOrUnboundIpv6IsIpv4Only() {
        assertEquals(CarHotspotIpFamily.REASON_IPV6_DISABLED,
            CarHotspotIpFamily.decide(dualProbe.copy(ipv6Disabled = true), null).reason)
        for (bad in listOf(global.copy(tentative = true), global.copy(dadFailed = true),
            global.copy(deprecated = true), global.copy(bindOk = false))) {
            val d = CarHotspotIpFamily.decide(Probe(true, listOf(bad)), null)
            assertEquals(MODE_IPV4_ONLY, d.mode)
            assertEquals(CarHotspotIpFamily.REASON_IPV6_NOT_USABLE, d.reason)
        }
    }

    @Test fun unscopedLinkLocalIsNotUsable() {
        val unscoped = scopedLinkLocal.copy(address = linkLocal(0))
        assertEquals(CarHotspotIpFamily.REASON_IPV6_NOT_USABLE,
            CarHotspotIpFamily.decide(Probe(true, listOf(unscoped)), null).reason)
    }

    @Test fun missingIpv6RouteIsIpv4Only() {
        assertEquals(CarHotspotIpFamily.REASON_NO_IPV6_ROUTE,
            CarHotspotIpFamily.decide(dualProbe.copy(ipv6Route = false), null).reason)
    }

    @Test fun noIpv4StillChoosesTheIpv4Path() {
        assertEquals(MODE_IPV4_ONLY, CarHotspotIpFamily.decide(dualProbe.copy(ipv4Present = false), null).mode)
    }

    @Test fun clearlyUsableIpv6GivesDualStackWithGlobalAddressesOnly() {
        val d = CarHotspotIpFamily.decide(dualProbe, null)
        assertEquals(MODE_DUAL_STACK, d.mode)
        assertEquals(CarHotspotIpFamily.REASON_IPV6_USABLE, d.reason)
        assertTrue(d.ipv6Supported)
        // Global/ULA only: the scoped link-local address is never served or advertised.
        assertEquals(listOf(global.address), d.ipv6Addresses)
        // An unreadable route table (Android 10+) does not block a usable global address.
        assertEquals(MODE_DUAL_STACK, CarHotspotIpFamily.decide(dualProbe.copy(ipv6Route = null), null).mode)
    }

    // ---- Per-unit memory and fallback ----

    @Test fun pictureStallFallbackIsRememberedForThisUnitOnly() {
        val store = MapStore()
        val memory = CarHotspotIpFamily.Memory(store) { 1_000L }
        val unit = CarHotspotIpFamily.unitKey("CS75-AP", "wlan0", "Allwinner/T7/sun8iw17")
        val other = CarHotspotIpFamily.unitKey("Other-AP", "wlan0", "Allwinner/T7/sun8iw17")
        assertEquals(MODE_DUAL_STACK, CarHotspotIpFamily.decide(dualProbe, memory.read(unit)).mode)

        memory.recordIpv6Failure(unit, CarHotspotIpFamily.REASON_FALLBACK_PICTURE_STALL)
        val next = CarHotspotIpFamily.decide(dualProbe, memory.read(unit))
        assertEquals(MODE_IPV4_ONLY, next.mode)
        assertEquals(CarHotspotIpFamily.REASON_REMEMBERED_IPV4, next.reason)
        assertTrue("the probe still reports IPv6 support", next.ipv6Supported)
        assertEquals(CarHotspotIpFamily.REASON_FALLBACK_PICTURE_STALL, memory.read(unit)!!.reason)
        // A different network (SSID) is a new unit: probed afresh.
        assertNull(memory.read(other))
        assertEquals(MODE_DUAL_STACK, CarHotspotIpFamily.decide(dualProbe, memory.read(other)).mode)
    }

    @Test fun noTcpFallbackIsRemembered() {
        val memory = CarHotspotIpFamily.Memory(MapStore()) { 5L }
        memory.recordIpv6Failure("u", CarHotspotIpFamily.REASON_FALLBACK_NO_TCP)
        assertEquals(MODE_IPV4_ONLY, CarHotspotIpFamily.decide(dualProbe, memory.read("u")).mode)
    }

    @Test fun successfulDualStackIsRememberedButReprobedWhenIpv6Disappears() {
        val memory = CarHotspotIpFamily.Memory(MapStore()) { 5L }
        memory.recordSuccess("u", MODE_DUAL_STACK)
        assertEquals(CarHotspotIpFamily.REASON_REMEMBERED_DUAL,
            CarHotspotIpFamily.decide(dualProbe, memory.read("u")).reason)
        // Network changed: no usable IPv6 now → IPv4 only, never a stale dual stack.
        val d = CarHotspotIpFamily.decide(cs75Probe, memory.read("u"))
        assertEquals(MODE_IPV4_ONLY, d.mode)
        assertEquals(CarHotspotIpFamily.REASON_LINK_LOCAL_ONLY, d.reason)
    }

    @Test fun ipv4OnlyVerdictExpiresOrIsReprobedAfterRepeatedFailures() {
        var now = 0L
        val memory = CarHotspotIpFamily.Memory(MapStore()) { now }
        memory.recordIpv6Failure("u", CarHotspotIpFamily.REASON_FALLBACK_PICTURE_STALL)
        repeat(CarHotspotIpFamily.REPROBE_AFTER_FAILURES - 1) { memory.recordIpv4Failure("u") }
        assertEquals(MODE_IPV4_ONLY, memory.read("u")!!.mode)
        memory.recordIpv4Failure("u")
        assertNull("re-probe after repeated IPv4-only failures", memory.read("u"))

        memory.recordIpv6Failure("u", CarHotspotIpFamily.REASON_FALLBACK_NO_TCP)
        now = CarHotspotIpFamily.REMEMBER_IPV4_ONLY_MILLIS + 1
        assertNull("re-probe after the verdict expires", memory.read("u"))
    }

    @Test fun successOnIpv4ResetsTheFailureCount() {
        val memory = CarHotspotIpFamily.Memory(MapStore()) { 0L }
        memory.recordIpv6Failure("u", CarHotspotIpFamily.REASON_FALLBACK_NO_TCP)
        memory.recordIpv4Failure("u")
        memory.recordSuccess("u", MODE_IPV4_ONLY)
        assertEquals(0, memory.read("u")!!.ipv4Failures)
        assertEquals(MODE_IPV4_ONLY, memory.read("u")!!.mode)
    }

    @Test fun unitKeyIsStableAndDependsOnNetworkAndModel() {
        val a = CarHotspotIpFamily.unitKey("AP", "wlan0", "m")
        assertEquals(a, CarHotspotIpFamily.unitKey("AP", "wlan0", "m"))
        assertNotEquals(a, CarHotspotIpFamily.unitKey("AP2", "wlan0", "m"))
        assertNotEquals(a, CarHotspotIpFamily.unitKey("AP", "ap0", "m"))
        assertNotEquals(a, CarHotspotIpFamily.unitKey("AP", "wlan0", "m2"))
        assertFalse(a.contains("AP"))
    }

    @Test fun carHotspotMemoryNeverUsesTheSharedWifiFamilyKey() {
        val store = MapStore()
        val memory = CarHotspotIpFamily.Memory(store)
        memory.recordIpv6Failure("u", CarHotspotIpFamily.REASON_FALLBACK_NO_TCP)
        memory.recordSuccess("v", MODE_DUAL_STACK)
        assertTrue(store.map.keys.none { it.contains("hotspot_address_family") })
        // And the shared-Wi-Fi plan still ignores any remembered family on the car hotspot.
        assertEquals(HotspotAddressPolicy.FAMILY_IPV4,
            WirelessAddressFamilyPlan.choose(WirelessHotspotBackend.MANUAL_HOTSPOT, HotspotAddressPolicy.FAMILY_IPV6, 36).preferredFamily)
    }


    // ---- the probe must see the AP's IPv6 addresses ----

    private val v4Ap = InetAddress.getByName("192.168.43.1")
    private val globalAddr = v6("2001:db8:1::1")
    private val ifInet6Good = "fe80000000000000123456fffe789abc 0c 40 20 80     wlan0\n" +
        "20010db8000100000000000000000001 0c 40 00 00     wlan0"
    private val routesWithGlobal =
        "20010db8000100000000000000000000 40 00000000000000000000000000000000 00 00000000000000000000000000000000 00000100 00000001 00000000 00000001     wlan0"
    private val routesLinkLocalOnly =
        "fe800000000000000000000000000000 40 00000000000000000000000000000000 00 00000000000000000000000000000000 00000100 00000001 00000000 00000001     wlan0"

    private fun fakeFiles(inet6: String, route: String, disabled: String = "0") = { path: String ->
        when (path) {
            "/proc/net/if_inet6" -> inet6
            "/proc/net/ipv6_route" -> route
            "/proc/sys/net/ipv6/conf/wlan0/disable_ipv6" -> disabled
            else -> null
        }
    }

    @Test fun probeSeesTheApsIpv6AddressesAndDecideChoosesDualStack() {
        val probe = CarHotspotIpFamily.probe("wlan0", listOf(v4Ap, globalAddr, linkLocal(12)),
            bindCheck = { true }, readFile = fakeFiles(ifInet6Good, routesWithGlobal))
        assertTrue(probe.ipv4Present)
        assertEquals(2, probe.candidates.size)
        assertEquals(true, probe.ipv6Route)
        val d = CarHotspotIpFamily.decide(probe, memory = null)
        assertEquals(MODE_DUAL_STACK, d.mode)
        assertEquals(CarHotspotIpFamily.REASON_IPV6_USABLE, d.reason)
        assertEquals(listOf(globalAddr), d.ipv6Addresses)
        assertTrue(d.ipv6Addresses.none { it.isLinkLocalAddress })
    }

    @Test fun anIpv4OnlyAddressListGivesTheProbeNothingToChooseFrom() {
        // The advertised host list (WirelessAddressFamilyPlan.usable strips IPv6): always IPv4 only, whatever the AP runs.
        val stripped = WirelessAddressFamilyPlan.usable(WirelessHotspotBackend.MANUAL_HOTSPOT,
            listOf(v4Ap, globalAddr, linkLocal(12)))
        val blind = CarHotspotIpFamily.probe("wlan0", stripped, bindCheck = { true },
            readFile = fakeFiles(ifInet6Good, routesWithGlobal))
        assertEquals(CarHotspotIpFamily.REASON_NO_IPV6, CarHotspotIpFamily.decide(blind, null).reason)
        // The probe input keeps IPv6.
        val seen = WirelessAddressFamilyPlan.probeAddresses(listOf(v4Ap, globalAddr, linkLocal(12)), listOf(v4Ap))
        assertEquals(listOf(v4Ap, globalAddr, linkLocal(12)), seen)
        assertEquals(listOf(v4Ap), WirelessAddressFamilyPlan.probeAddresses(emptyList(), listOf(v4Ap)))
    }

    @Test fun linkLocalOnlyApGivesIpv4OnlyAndAdvertisesNoIpv6() {
        val probe = CarHotspotIpFamily.probe("wlan0", listOf(v4Ap, linkLocal(12)), bindCheck = { true },
            readFile = fakeFiles("fe80000000000000123456fffe789abc 0c 40 20 80     wlan0", routesLinkLocalOnly))
        val d = CarHotspotIpFamily.decide(probe, null)
        assertEquals(MODE_IPV4_ONLY, d.mode)
        assertEquals(CarHotspotIpFamily.REASON_LINK_LOCAL_ONLY, d.reason)
        assertTrue(d.ipv6Addresses.isEmpty())
    }

    @Test fun globalAddressWithoutAGlobalRouteStaysIpv4() {
        val probe = CarHotspotIpFamily.probe("wlan0", listOf(v4Ap, globalAddr), bindCheck = { true },
            readFile = fakeFiles(ifInet6Good, routesLinkLocalOnly))
        assertEquals(CarHotspotIpFamily.REASON_NO_IPV6_ROUTE, CarHotspotIpFamily.decide(probe, null).reason)
    }

    @Test fun tentativeOrUnboundGlobalAddressStaysIpv4() {
        val tentative = "20010db8000100000000000000000001 0c 40 00 40     wlan0"
        val a = CarHotspotIpFamily.probe("wlan0", listOf(v4Ap, globalAddr), bindCheck = { true },
            readFile = fakeFiles(tentative, routesWithGlobal))
        assertEquals(CarHotspotIpFamily.REASON_IPV6_NOT_USABLE, CarHotspotIpFamily.decide(a, null).reason)
        val b = CarHotspotIpFamily.probe("wlan0", listOf(v4Ap, globalAddr), bindCheck = { false },
            readFile = fakeFiles(ifInet6Good, routesWithGlobal))
        assertEquals(CarHotspotIpFamily.REASON_IPV6_NOT_USABLE, CarHotspotIpFamily.decide(b, null).reason)
    }

    @Test fun dualStackFailureFallsBackToIpv4OnlyAndIsRemembered() {
        val memory = CarHotspotIpFamily.Memory(MapStore()) { 1_000L }
        val probe = CarHotspotIpFamily.probe("wlan0", listOf(v4Ap, globalAddr), bindCheck = { true },
            readFile = fakeFiles(ifInet6Good, routesWithGlobal))
        val unit = CarHotspotIpFamily.unitKey("AP", "wlan0", "model")
        assertEquals(MODE_DUAL_STACK, CarHotspotIpFamily.decide(probe, memory.read(unit)).mode)
        // The controller records this when a dual-stack run gets no AirPlay TCP, or no picture in 25 s, then restarts.
        memory.recordIpv6Failure(unit, CarHotspotIpFamily.REASON_FALLBACK_NO_TCP)
        val next = CarHotspotIpFamily.decide(probe, memory.read(unit))
        assertEquals(MODE_IPV4_ONLY, next.mode)
        assertEquals(CarHotspotIpFamily.REASON_REMEMBERED_IPV4, next.reason)
        assertTrue(next.ipv6Addresses.isEmpty())
    }

    @Test fun anAdvertisedListNeverKeepsALinkLocalIpv6() {
        val all = listOf(v4Ap, globalAddr, linkLocal(12))
        assertEquals(listOf(v4Ap, globalAddr), WirelessAddressFamilyPlan.withoutLinkLocalIpv6(all))
        assertEquals(listOf(v4Ap), WirelessAddressFamilyPlan.withoutLinkLocalIpv6(listOf(v4Ap, linkLocal(12))))
        // And the 0x4301 address list built from it has no fe80:: either.
        assertTrue(WirelessAddressFamilyPlan.endpointAddresses(v4Ap, WirelessAddressFamilyPlan.withoutLinkLocalIpv6(all))
            .none { it is Inet6Address && it.isLinkLocalAddress })
    }

    @Test fun mediaIpv4BindHoldsOnEveryInfrastructureBackendWhateverTheDecidedFamily() {
        // mediaBindAddress only narrows an IPv4 session; an IPv6 session keeps `::`, so a dual-stack run is covered too.
        assertTrue(WirelessAddressFamilyPlan.bindMediaToSessionIpv4(WirelessHotspotBackend.MANUAL_HOTSPOT))
        assertTrue(WirelessAddressFamilyPlan.bindMediaToSessionIpv4(WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT))
        assertTrue(WirelessAddressFamilyPlan.bindMediaToSessionIpv4(WirelessHotspotBackend.EXISTING_WIFI))
        assertFalse(WirelessAddressFamilyPlan.bindMediaToSessionIpv4(WirelessHotspotBackend.WIFI_P2P))
    }

    @Test fun aFamilyRememberedFromSharedWifiNeverReachesTheCarHotspot() {
        for (remembered in listOf(null, HotspotAddressPolicy.FAMILY_IPV4, HotspotAddressPolicy.FAMILY_IPV6)) {
            assertEquals(HotspotAddressPolicy.FAMILY_IPV4,
                WirelessAddressFamilyPlan.choose(WirelessHotspotBackend.MANUAL_HOTSPOT, remembered, 36).preferredFamily)
        }
    }

    // ---- /proc parsing ----

    @Test fun parsesIfInet6FlagsForTheInterface() {
        val text = """
            fe80000000000000123456fffe789abc 0c 40 20 80     wlan0
            20010db8000100000000000000000001 0c 40 00 40     wlan0
            20010db8000200000000000000000001 03 40 00 00     rmnet0
        """.trimIndent()
        val flags = CarHotspotIpFamily.parseIfInet6(text, "wlan0")
        assertEquals(2, flags.size)
        assertEquals(0x40, flags["20010db8000100000000000000000001"])
    }

    @Test fun routeTableNeedsANonLinkLocalRouteOnTheInterface() {
        val linkLocalOnly = """
            fe800000000000000000000000000000 40 00000000000000000000000000000000 00 00000000000000000000000000000000 00000100 00000001 00000000 00000001     wlan0
            ff000000000000000000000000000000 08 00000000000000000000000000000000 00 00000000000000000000000000000000 00000100 00000001 00000000 00000001     wlan0
        """.trimIndent()
        assertFalse(CarHotspotIpFamily.hasGlobalRoute(linkLocalOnly, "wlan0"))
        val global = linkLocalOnly + "\n" +
            "20010db8000100000000000000000000 40 00000000000000000000000000000000 00 00000000000000000000000000000000 00000100 00000001 00000000 00000001     wlan0"
        assertTrue(CarHotspotIpFamily.hasGlobalRoute(global, "wlan0"))
        assertFalse(CarHotspotIpFamily.hasGlobalRoute(global, "ap0"))
    }
}
