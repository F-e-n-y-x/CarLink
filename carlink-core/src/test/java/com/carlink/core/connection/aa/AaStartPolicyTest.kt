package com.carlink.core.connection.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AaStartPolicyTest {
    private val wifiDirect = WirelessConfig(3, 0, "", "", "", "0")
    private val sameWifi = WirelessConfig(3, 1, "wlan0", "Home", "secret12", "AA:BB:CC:DD:EE:FF")

    private fun decide(running: Boolean = true, busy: Boolean = false, wedged: Boolean = false,
                       armed: WirelessConfig? = wifiDirect, current: WirelessConfig = wifiDirect) =
        WirelessStartPolicy.decide(running, busy, wedged, armed, current)

    @Test fun aStoppedServiceIsStarted() = assertEquals(WirelessStartDecision.START, decide(running = false, busy = true, wedged = true))

    @Test fun aConnectingOrConnectedSessionIsLeftAlone() {
        // isBusy, not isConnected: Connecting counts (DA§10.5).
        assertEquals(WirelessStartDecision.ALREADY_ACTIVE, decide(busy = true))
        assertEquals(WirelessStartDecision.ALREADY_ACTIVE, decide(busy = true, wedged = true))
    }

    @Test fun onlyAWedgedIdleServiceIsRestarted() {
        assertEquals(WirelessStartDecision.RESTART, decide(wedged = true))
        assertEquals(WirelessStartDecision.ARM, decide())
    }

    @Test fun changedOrUnknownWirelessSettingsForceARearm() {
        // initWifiMode re-arms by itself only when the mode or transport changes (DA§2.4); a new
        // hotspot interface, name, password or BSSID on transport 1 would otherwise be ignored.
        assertEquals(WirelessStartDecision.REARM, decide(armed = sameWifi.copy(hotspotPassword = "other123"), current = sameWifi))
        assertEquals(WirelessStartDecision.REARM, decide(armed = null))
        assertEquals(WirelessStartDecision.ARM, decide(armed = sameWifi, current = sameWifi))
    }

    @Test fun androidAutosOwnGroupIsLeftToTheEngine() {
        assertEquals(LeftoverGroupAction.LEAVE_FOR_ENGINE, LeftoverGroupPolicy.decide("DIRECT-K7-CarLink", true, "DIRECT-K7-CarLink", null))
        assertEquals(LeftoverGroupAction.LEAVE_FOR_ENGINE, LeftoverGroupPolicy.decide("DIRECT-xy-Old", true, "DIRECT-K7-CarLink", "DIRECT-xy-Old"))
    }

    @Test fun carPlaysLeftoverGroupIsRemoved() {
        assertEquals(LeftoverGroupAction.REMOVE_CARPLAY_GROUP, LeftoverGroupPolicy.decide("DIRECT-dp0123456789ab-Ab12", true, "DIRECT-K7-CarLink", null))
        // Another CarLink install's Android Auto group (1.0.5 uninstalled, test build installed, 2026-10-20).
        assertEquals(LeftoverGroupAction.REMOVE_OTHER_INSTALL, LeftoverGroupPolicy.decide("DIRECT-U3-CarLink", true, "DIRECT-K7-CarLink", null))
        assertEquals(LeftoverGroupAction.LEAVE_FOREIGN, LeftoverGroupPolicy.decide("DIRECT-U3-CarLink", false, "DIRECT-K7-CarLink", null))
        assertEquals(LeftoverGroupAction.LEAVE_FOREIGN, LeftoverGroupPolicy.decide("DIRECT-U3-Miracast", true, "DIRECT-K7-CarLink", null))
        assertEquals("only as owner", LeftoverGroupAction.LEAVE_FOREIGN, LeftoverGroupPolicy.decide("DIRECT-dp0123456789ab-Ab12", false, null, null))
    }

    @Test fun anotherAppsGroupIsLeftAlone() {
        assertEquals(LeftoverGroupAction.LEAVE_FOREIGN, LeftoverGroupPolicy.decide("DIRECT-ab-Mirror", true, "DIRECT-K7-CarLink", null))
        assertEquals(LeftoverGroupAction.NONE, LeftoverGroupPolicy.decide(null, false, "DIRECT-K7-CarLink", null))
    }

    @Test fun androidAutoGroupNeedsOwnershipAndAKnownName() {
        assertTrue(LeftoverGroupPolicy.isAndroidAutoGroup("A", true, "A", null))
        assertFalse(LeftoverGroupPolicy.isAndroidAutoGroup("A", false, "A", null))
        assertFalse(LeftoverGroupPolicy.isAndroidAutoGroup("B", true, "A", null))
        assertFalse(LeftoverGroupPolicy.isAndroidAutoGroup("", true, "", ""))
    }
}
