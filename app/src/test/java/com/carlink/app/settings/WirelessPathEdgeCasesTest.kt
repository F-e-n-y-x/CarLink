package com.carlink.app.settings

import com.carlink.app.core.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Edge cases of [WirelessPathSelector] and [EngineMappings] beyond [EngineMappingsTest]. */
class WirelessPathEdgeCasesTest {
    private val hotspot = WifiDetails("BYD-Car", "carpassword")
    private val home = WifiDetails("HomeNet", "homepassword")
    private val both = UnifiedSettings(homeWifi = home, carHotspot = hotspot)
    private val routerKnown = WifiEnvironment(1, "HomeNet", null, joinedBssid = "AA:BB:CC:DD:EE:FF", joinedInterface = "wlan0")

    private fun select(engine: Engine, settings: UnifiedSettings, wifi: WifiEnvironment) =
        WirelessPathSelector.select(engine, settings, wifi)

    @Test fun unreadableSsidNeverMeansSameWifi() {
        // Without location permission the SSID reads as null, even when the router details are known.
        val unreadable = routerKnown.copy(joinedSsid = null)
        Engine.entries.forEach { engine ->
            assertEquals("$engine", WirelessPath.CAR_HOTSPOT, select(engine, both, unreadable))
            assertEquals("$engine", WirelessPath.WIFI_DIRECT, select(engine, both.copy(carHotspot = WifiDetails()), unreadable))
        }
    }

    @Test fun twoWifiNetworksAreAmbiguousEvenIfOneMatches() {
        val two = routerKnown.copy(joinedNetworks = 2)
        Engine.entries.forEach { engine ->
            assertEquals("$engine", WirelessPath.CAR_HOTSPOT, select(engine, both, two))
        }
    }

    @Test fun noJoinedNetworkMeansNoSameWifi() {
        val none = WifiEnvironment(0, null, null)
        assertEquals(WirelessPath.CAR_HOTSPOT, select(Engine.CARPLAY, both, none))
    }

    @Test fun ssidMatchIsExact() {
        assertEquals(WirelessPath.CAR_HOTSPOT, select(Engine.CARPLAY, both, routerKnown.copy(joinedSsid = "homenet")))
        assertEquals(WirelessPath.CAR_HOTSPOT, select(Engine.CARPLAY, both, routerKnown.copy(joinedSsid = "HomeNet ")))
    }

    @Test fun invalidSavedHomeWifiIsNotUsedAutomatically() {
        val badPassword = both.copy(homeWifi = WifiDetails("HomeNet", "short"))
        assertEquals(WirelessPath.CAR_HOTSPOT, select(Engine.CARPLAY, badPassword, routerKnown))
        val openHome = both.copy(homeWifi = WifiDetails("HomeNet", ""))
        assertEquals(WirelessPath.SAME_WIFI, select(Engine.CARPLAY, openHome, routerKnown))
    }

    @Test fun androidAutoNeedsBothBssidAndInterface() {
        assertEquals(WirelessPath.SAME_WIFI, select(Engine.ANDROID_AUTO, both, routerKnown))
        assertEquals(WirelessPath.CAR_HOTSPOT, select(Engine.ANDROID_AUTO, both, routerKnown.copy(joinedBssid = null)))
        assertEquals(WirelessPath.CAR_HOTSPOT, select(Engine.ANDROID_AUTO, both, routerKnown.copy(joinedInterface = null)))
        // CarPlay does not need them.
        assertEquals(WirelessPath.SAME_WIFI, select(Engine.CARPLAY, both, routerKnown.copy(joinedBssid = null, joinedInterface = null)))
    }

    @Test fun forcedSameWifiForAndroidAutoFallsBackWhenUnusable() {
        val forced = both.copy(connectionMethod = ConnectionMethod.SAME_WIFI)
        assertEquals(WirelessPath.SAME_WIFI, select(Engine.ANDROID_AUTO, forced, routerKnown))
        val noRouter = WifiEnvironment(1, "HomeNet", carHotspotOn = null)
        assertEquals(WirelessPath.CAR_HOTSPOT, select(Engine.ANDROID_AUTO, forced, noRouter))
        assertEquals(WirelessPath.WIFI_DIRECT, select(Engine.ANDROID_AUTO, forced, noRouter.copy(carHotspotOn = false)))
        assertEquals(WirelessPath.WIFI_DIRECT,
            select(Engine.ANDROID_AUTO, forced.copy(carHotspot = WifiDetails()), noRouter))
    }

    @Test fun forcedSameWifiForCarPlayIsHonouredEvenWhenNotJoined() {
        // The driver's explicit choice; CarPlay's own pre-flight reports missing details.
        val forced = UnifiedSettings(connectionMethod = ConnectionMethod.SAME_WIFI)
        assertEquals(WirelessPath.SAME_WIFI, select(Engine.CARPLAY, forced, WifiEnvironment(0, null, false)))
    }

    @Test fun explicitHotspotAndWifiDirectIgnoreTheEnvironment() {
        val hotspotOnly = both.copy(connectionMethod = ConnectionMethod.CAR_HOTSPOT)
        val direct = both.copy(connectionMethod = ConnectionMethod.WIFI_DIRECT)
        Engine.entries.forEach { engine ->
            assertEquals(WirelessPath.CAR_HOTSPOT, select(engine, hotspotOnly, routerKnown.copy(carHotspotOn = false)))
            assertEquals(WirelessPath.WIFI_DIRECT, select(engine, direct, routerKnown))
        }
    }

    @Test fun hotspotStateUnknownCountsAsUsable() {
        assertEquals(WirelessPath.CAR_HOTSPOT,
            select(Engine.ANDROID_AUTO, UnifiedSettings(carHotspot = hotspot), WifiEnvironment(0, null, null)))
        assertEquals(WirelessPath.CAR_HOTSPOT,
            select(Engine.ANDROID_AUTO, UnifiedSettings(carHotspot = hotspot), WifiEnvironment(0, null, true)))
    }

    @Test fun ssidLimitIsInUtf8Bytes() {
        assertEquals(true, WifiDetails("N".repeat(32), "").isValid())
        // 11 three-byte characters = 33 bytes.
        assertFalse(WifiDetails("車".repeat(11), "").isValid())
        assertEquals(true, WifiDetails("x".repeat(1), "p".repeat(63)).isValid())
        assertFalse(WifiDetails("x", "p".repeat(64)).isValid())
        assertFalse(WifiDetails("x", "p".repeat(7)).isValid())
    }

    @Test fun customPictureLeavesOnlyPictureAlone() {
        val s = UnifiedSettings(picture = Picture.CUSTOM, interfaceSize = InterfaceSize.LARGE)
        val carPlay = EngineMappings.carPlay(s, null)
        assertNull(carPlay.displayScalePercent)
        assertNull(carPlay.fps)
        assertEquals(250, carPlay.widthPhysicalMm)
        val aa = EngineMappings.androidAuto(s, null, 200)
        assertFalse("resolutionId" in aa)
        assertFalse("fps-limit" in aa)
        assertEquals(240, aa["dpi-pixel-density"])
    }

    @Test fun customInterfaceSizeLeavesOnlyInterfaceSizeAlone() {
        val s = UnifiedSettings(picture = Picture.BALANCED, interfaceSize = InterfaceSize.CUSTOM)
        val carPlay = EngineMappings.carPlay(s, null)
        assertNull(carPlay.widthPhysicalMm)
        assertEquals(100, carPlay.displayScalePercent)
        assertEquals(30, carPlay.fps)
        val aa = EngineMappings.androidAuto(s, null, 200)
        assertFalse("dpi-pixel-density" in aa)
        assertEquals(2, aa["resolutionId"])
        assertEquals(30, aa["fps-limit"])
    }

    @Test fun smallInterfaceScalesThePanelDensity() {
        assertEquals(170, EngineMappings.androidAuto(UnifiedSettings(interfaceSize = InterfaceSize.SMALL), null, 200)["dpi-pixel-density"])
        assertEquals(350, EngineMappings.carPlay(UnifiedSettings(interfaceSize = InterfaceSize.SMALL), null).widthPhysicalMm)
    }

    @Test fun androidAutoSameWifiWithoutRouterDetailsWritesNoTransport() {
        val aa = EngineMappings.androidAuto(both, WirelessPath.SAME_WIFI, 240, WifiEnvironment(1, "HomeNet", null))
        assertFalse("native-ap-transport" in aa)
        assertFalse("static-bssid" in aa)
    }

    @Test fun hotspotPathWithoutValidHotspotClearsTheCredentials() {
        val aa = EngineMappings.androidAuto(UnifiedSettings(carHotspot = WifiDetails("Car", "bad")), WirelessPath.WIFI_DIRECT, 240)
        assertEquals(0, aa["native-ap-transport"])
        assertEquals("", aa["hotspot-ssid"])
        assertEquals("", aa["hotspot-password"])
        assertEquals("0", aa["static-bssid"])
    }

    @Test fun scheduleNightTimesReachBothEngines() {
        val s = UnifiedSettings(dayNight = DayNight.SCHEDULE, nightStartMinute = 20 * 60 + 30, nightEndMinute = 6 * 60)
        val carPlay = EngineMappings.carPlay(s, null)
        assertEquals("schedule", carPlay.nightModeKey)
        assertEquals(1230, carPlay.nightStartMinute)
        assertEquals(360, carPlay.nightEndMinute)
        val aa = EngineMappings.androidAuto(s, null, 240)
        assertEquals(3, aa["night-mode"])
        assertEquals(1230, aa["night-mode-manual-start"])
        assertEquals(360, aa["night-mode-manual-end"])
        assertEquals("system", EngineMappings.carPlay(UnifiedSettings(), null).nightModeKey)
        assertEquals(0, EngineMappings.androidAuto(UnifiedSettings(), null, 240)["night-mode"])
    }
}
