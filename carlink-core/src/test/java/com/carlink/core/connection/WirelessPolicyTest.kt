package com.carlink.core.connection

import com.carlink.core.core.Engine
import com.carlink.core.settings.ConnectionMethod
import com.carlink.core.settings.UnifiedSettings
import com.carlink.core.settings.WifiDetails
import com.carlink.core.settings.WifiEnvironment
import com.carlink.core.settings.WirelessPath.CAR_HOTSPOT
import com.carlink.core.settings.WirelessPath.SAME_WIFI
import com.carlink.core.settings.WirelessPath.WIFI_DIRECT
import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessPolicyTest {
    private val home = WifiDetails("Home", "homepassword")
    private val hotspot = WifiDetails("CarAP", "carpassword")
    private val onHome = WifiEnvironment(1, "Home", carHotspotOn = null, joinedBssid = "AA:BB:CC:DD:EE:FF", joinedInterface = "wlan0")

    private fun paths(engine: Engine, settings: UnifiedSettings, wifi: WifiEnvironment = onHome, headUnit: Boolean = false,
                      last: com.carlink.core.settings.WirelessPath? = null) =
        WirelessPolicy.candidates(WirelessPolicy.Inputs(engine, settings, wifi, headUnit, last))

    @Test fun automaticNeverPicksTheSharedRouter() {
        // Even with the router saved and joined: Same Wi-Fi is the driver's explicit choice only.
        Engine.entries.forEach { assertEquals(listOf(WIFI_DIRECT), paths(it, UnifiedSettings(homeWifi = home))) }
    }

    @Test fun headUnitHotspotComesFirstForCarPlayOnly() {
        val s = UnifiedSettings(carHotspot = hotspot)
        assertEquals(listOf(CAR_HOTSPOT, WIFI_DIRECT), paths(Engine.CARPLAY, s, headUnit = true))
        assertEquals(listOf(WIFI_DIRECT, CAR_HOTSPOT), paths(Engine.ANDROID_AUTO, s, headUnit = true))
        assertEquals(listOf(WIFI_DIRECT, CAR_HOTSPOT), paths(Engine.CARPLAY, s, headUnit = false))
    }

    @Test fun hotspotNeedsKnownDetailsAndNotOff() {
        assertEquals(listOf(WIFI_DIRECT), paths(Engine.CARPLAY, UnifiedSettings(carHotspot = hotspot), onHome.copy(carHotspotOn = false), headUnit = true))
        assertEquals(listOf(WIFI_DIRECT), paths(Engine.CARPLAY, UnifiedSettings(carHotspot = WifiDetails("Open", "")), headUnit = true))
        val readable = onHome.copy(carHotspotOn = true, systemHotspot = hotspot)
        assertEquals(listOf(CAR_HOTSPOT, WIFI_DIRECT), paths(Engine.CARPLAY, UnifiedSettings(), readable, headUnit = true))
    }

    @Test fun whatWorkedLastGoesFirst() {
        val s = UnifiedSettings(carHotspot = hotspot)
        assertEquals(listOf(CAR_HOTSPOT, WIFI_DIRECT), paths(Engine.ANDROID_AUTO, s, last = CAR_HOTSPOT))
        // A remembered path Automatic would not use is ignored.
        assertEquals(listOf(WIFI_DIRECT), paths(Engine.ANDROID_AUTO, UnifiedSettings(), last = SAME_WIFI))
    }

    @Test fun explicitMethodsAreHonoured() {
        assertEquals(listOf(SAME_WIFI), paths(Engine.CARPLAY, UnifiedSettings(connectionMethod = ConnectionMethod.SAME_WIFI)))
        assertEquals(listOf(CAR_HOTSPOT), paths(Engine.ANDROID_AUTO, UnifiedSettings(connectionMethod = ConnectionMethod.CAR_HOTSPOT)))
        assertEquals(listOf(WIFI_DIRECT), paths(Engine.CARPLAY, UnifiedSettings(connectionMethod = ConnectionMethod.WIFI_DIRECT)))
    }
}
