package com.carlink.app.settings

import com.carlink.app.core.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineMappingsTest {
    private val hotspot = WifiDetails("BYD-Car", "carpassword")
    private val home = WifiDetails("HomeNet", "homepassword")
    private val homeJoined = WifiEnvironment(joinedNetworks = 1, joinedSsid = "HomeNet", carHotspotOn = null)

    @Test fun automaticPrefersSameWifiForIphoneOnTheSavedNetwork() {
        val settings = UnifiedSettings(homeWifi = home, carHotspot = hotspot)
        assertEquals(WirelessPath.SAME_WIFI, WirelessPathSelector.select(Engine.CARPLAY, settings, homeJoined))
    }

    @Test fun androidAutoUsesSameWifiOnlyWithRouterDetails() {
        val settings = UnifiedSettings(homeWifi = home, carHotspot = hotspot)
        // Without the router's BSSID and the car's interface, Android Auto cannot be sent there.
        assertEquals(WirelessPath.CAR_HOTSPOT, WirelessPathSelector.select(Engine.ANDROID_AUTO, settings, homeJoined))
        val full = homeJoined.copy(joinedBssid = "AA:BB:CC:DD:EE:FF", joinedInterface = "wlan0")
        assertEquals(WirelessPath.SAME_WIFI, WirelessPathSelector.select(Engine.ANDROID_AUTO, settings, full))
        val forced = settings.copy(connectionMethod = ConnectionMethod.SAME_WIFI, carHotspot = WifiDetails())
        assertEquals(WirelessPath.WIFI_DIRECT, WirelessPathSelector.select(Engine.ANDROID_AUTO, forced, homeJoined))
    }

    @Test fun androidAutoSameWifiNamesTheRouter() {
        val full = homeJoined.copy(joinedBssid = "AA:BB:CC:DD:EE:FF", joinedInterface = "wlan0")
        val aa = EngineMappings.androidAuto(UnifiedSettings(homeWifi = home), WirelessPath.SAME_WIFI, 240, full)
        assertEquals(1, aa["native-ap-transport"])
        assertEquals("wlan0", aa["hotspot-interface"])
        assertEquals("HomeNet", aa["hotspot-ssid"])
        assertEquals("homepassword", aa["hotspot-password"])
        assertEquals("AA:BB:CC:DD:EE:FF", aa["static-bssid"])
        assertEquals(false, aa["auto-enable-hotspot"])
        // Leaving Same Wi-Fi clears the overrides so the hotspot route reads the real hotspot.
        val hotspotPath = EngineMappings.androidAuto(UnifiedSettings(homeWifi = home), WirelessPath.CAR_HOTSPOT, 240, full)
        assertEquals("", hotspotPath["hotspot-interface"])
        assertEquals("0", hotspotPath["static-bssid"])
    }

    @Test fun sameWifiNeedsOneReadableMatchingNetwork() {
        val settings = UnifiedSettings(homeWifi = home, carHotspot = hotspot)
        val other = WifiEnvironment(1, "CoffeeShop", null)
        val unreadable = WifiEnvironment(1, null, null)
        val two = WifiEnvironment(2, null, null)
        listOf(other, unreadable, two).forEach { env ->
            assertEquals("$env", WirelessPath.CAR_HOTSPOT, WirelessPathSelector.select(Engine.CARPLAY, settings, env))
        }
    }

    @Test fun hotspotOffOrUnsavedFallsBackToWifiDirect() {
        val off = WifiEnvironment(0, null, carHotspotOn = false)
        assertEquals(WirelessPath.WIFI_DIRECT,
            WirelessPathSelector.select(Engine.CARPLAY, UnifiedSettings(carHotspot = hotspot), off))
        assertEquals(WirelessPath.WIFI_DIRECT,
            WirelessPathSelector.select(Engine.CARPLAY, UnifiedSettings(), WifiEnvironment(0, null, null)))
    }

    @Test fun explicitMethodsAreHonoured() {
        val env = WifiEnvironment(0, null, null)
        assertEquals(WirelessPath.CAR_HOTSPOT,
            WirelessPathSelector.select(Engine.CARPLAY, UnifiedSettings(connectionMethod = ConnectionMethod.CAR_HOTSPOT), env))
        assertEquals(WirelessPath.WIFI_DIRECT,
            WirelessPathSelector.select(Engine.ANDROID_AUTO, UnifiedSettings(connectionMethod = ConnectionMethod.WIFI_DIRECT), env))
    }

    @Test fun wifiDetailsValidation() {
        assertTrue(WifiDetails("Net", "").isValid()) // open network
        assertTrue(WifiDetails("Net", "12345678").isValid())
        assertFalse(WifiDetails("", "12345678").isValid())
        assertFalse(WifiDetails("Net", "short").isValid())
        assertFalse(WifiDetails("N".repeat(33), "12345678").isValid())
    }

    @Test fun pictureMapsToEachEnginesUnits() {
        val best = UnifiedSettings(picture = Picture.BEST)
        assertEquals(100, EngineMappings.carPlay(best, null).displayScalePercent)
        assertEquals(60, EngineMappings.carPlay(best, null).fps)
        assertEquals(3, EngineMappings.androidAuto(best, null, 240)["resolutionId"])
        val light = UnifiedSettings(picture = Picture.LIGHT)
        assertEquals(75, EngineMappings.carPlay(light, null).displayScalePercent)
        assertEquals(1, EngineMappings.androidAuto(light, null, 240)["resolutionId"])
        assertEquals(30, EngineMappings.androidAuto(light, null, 240)["fps-limit"])
    }

    @Test fun customValuesAreLeftToAdvanced() {
        val custom = UnifiedSettings(picture = Picture.CUSTOM, interfaceSize = InterfaceSize.CUSTOM)
        val carPlay = EngineMappings.carPlay(custom, null)
        assertNull(carPlay.displayScalePercent)
        assertNull(carPlay.fps)
        assertNull(carPlay.widthPhysicalMm)
        val aa = EngineMappings.androidAuto(custom, null, 240)
        assertFalse("resolutionId" in aa)
        assertFalse("dpi-pixel-density" in aa)
    }

    @Test fun interfaceSizeMovesBothEnginesTheSameWay() {
        // Larger controls: CarPlay reports a narrower panel, Android Auto a higher DPI.
        val large = UnifiedSettings(interfaceSize = InterfaceSize.LARGE)
        val small = UnifiedSettings(interfaceSize = InterfaceSize.SMALL)
        assertTrue(EngineMappings.carPlay(large, null).widthPhysicalMm!! < EngineMappings.carPlay(small, null).widthPhysicalMm!!)
        assertTrue((EngineMappings.androidAuto(large, null, 240)["dpi-pixel-density"] as Int) >
            (EngineMappings.androidAuto(small, null, 240)["dpi-pixel-density"] as Int))
        assertEquals(0, EngineMappings.androidAuto(UnifiedSettings(), null, 240)["dpi-pixel-density"])
    }

    @Test fun sharedSwitchesReachBothEngines() {
        val s = UnifiedSettings(rightHandDrive = true, soundThroughCarBluetooth = true, sendCarLocation = false,
            dayNight = DayNight.NIGHT)
        val carPlay = EngineMappings.carPlay(s, null)
        val aa = EngineMappings.androidAuto(s, null, 240)
        assertTrue(carPlay.rightHandDrive); assertEquals(true, aa["right-hand-drive"])
        assertTrue(carPlay.carBluetoothAudio); assertEquals(true, aa["music-via-bluetooth"])
        assertFalse(carPlay.locationReporting); assertEquals(false, aa["gps-navigation"])
        assertEquals("night", carPlay.nightModeKey); assertEquals(2, aa["night-mode"])
    }

    @Test fun wirelessPathReachesBothEngines() {
        assertEquals("EXISTING_WIFI", EngineMappings.carPlay(UnifiedSettings(), WirelessPath.SAME_WIFI).wirelessMode)
        assertEquals("MANUAL", EngineMappings.carPlay(UnifiedSettings(), WirelessPath.CAR_HOTSPOT).wirelessMode)
        assertEquals("WIFI_P2P", EngineMappings.carPlay(UnifiedSettings(), WirelessPath.WIFI_DIRECT).wirelessMode)
        assertNull(EngineMappings.carPlay(UnifiedSettings(), null).wirelessMode)
        assertEquals(1, EngineMappings.androidAuto(UnifiedSettings(), WirelessPath.CAR_HOTSPOT, 240)["native-ap-transport"])
        assertEquals(0, EngineMappings.androidAuto(UnifiedSettings(), WirelessPath.WIFI_DIRECT, 240)["native-ap-transport"])
    }
}
