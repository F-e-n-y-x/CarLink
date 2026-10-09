package com.carlink.app.connection.aa

import com.carlink.app.core.Transport
import com.carlink.app.settings.Picture
import com.carlink.app.settings.UnifiedSettings
import com.carlink.app.settings.VehicleProfile
import com.carlink.app.settings.WifiDetails
import com.carlink.app.settings.WifiEnvironment
import com.carlink.app.settings.WirelessPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AaSessionSettingsTest {
    private val base = UnifiedSettings()
    private val joined = WifiEnvironment(joinedNetworks = 1, joinedSsid = "Home", carHotspotOn = false,
        joinedBssid = "AA:BB:CC:DD:EE:FF", joinedInterface = "wlan0")

    private fun values(settings: UnifiedSettings = base, transport: Transport = Transport.WIRELESS,
                       path: WirelessPath? = WirelessPath.WIFI_DIRECT, profile: VehicleProfile = VehicleProfile.GENERIC,
                       wifi: WifiEnvironment? = null) =
        AaSessionSettings.values(settings, transport, path, profile, "My Car", 320, wifi)

    @Test fun everySessionWritesTheHostOwnedKeys() {
        for (path in listOf(WirelessPath.WIFI_DIRECT, WirelessPath.CAR_HOTSPOT)) {
            val v = values(path = path).values
            assertEquals(3, v["wifi-connection-mode"])
            for (key in listOf("auto-start-on-boot", "auto-start-on-screen-on", "auto-start-on-usb",
                    "auto-start-on-wifi", "auto-start-self-mode", "auto-connect-last-session", "kill-on-disconnect")) {
                assertEquals("$path $key", false, v[key])
            }
            assertEquals("0", v["static-bssid"])
            assertEquals("My Car", v["vehicle-display-name"])
            assertEquals(true, v["wifi-direct-stable-identity"])
        }
        assertEquals(false, values(transport = Transport.USB, path = null).values["auto-connect-last-session"])
    }

    @Test fun wifiDirectClearsEveryAccessPointValue() {
        val v = values(settings = base.copy(carHotspot = WifiDetails("CarAP", "password1"))).values
        assertEquals(0, v["native-ap-transport"])
        assertEquals("", v["hotspot-interface"])
        assertEquals("", v["hotspot-ssid"])
        assertEquals("", v["hotspot-password"])
    }

    @Test fun carHotspotUsesSavedDetailsOrTheSystemsOwn() {
        val saved = values(settings = base.copy(carHotspot = WifiDetails("CarAP", "password1")), path = WirelessPath.CAR_HOTSPOT).values
        assertEquals(1, saved["native-ap-transport"])
        assertEquals("CarAP", saved["hotspot-ssid"])
        assertEquals("password1", saved["hotspot-password"])
        assertEquals(false, saved["auto-enable-hotspot"])
        val unsaved = values(path = WirelessPath.CAR_HOTSPOT).values
        assertEquals("", unsaved["hotspot-ssid"])
    }

    @Test fun sameWifiNamesTheRouterAndItsExactBssid() {
        val r = values(settings = base.copy(homeWifi = WifiDetails("Home", "secret123")), path = WirelessPath.SAME_WIFI, wifi = joined)
        assertNull(r.problem)
        assertEquals(1, r.values["native-ap-transport"])
        assertEquals("wlan0", r.values["hotspot-interface"])
        assertEquals("Home", r.values["hotspot-ssid"])
        assertEquals("secret123", r.values["hotspot-password"])
        assertEquals("AA:BB:CC:DD:EE:FF", r.values["static-bssid"])
    }

    @Test fun sameWifiWithoutItsDetailsIsAProblemNotAStaleRoute() {
        val r = values(settings = base.copy(homeWifi = WifiDetails("Home", "secret123")), path = WirelessPath.SAME_WIFI,
            wifi = joined.copy(joinedBssid = null))
        assertNotNull(r.problem)
        assertFalse(r.values.containsKey("static-bssid"))
        assertFalse(r.values.containsKey("native-ap-transport"))
    }

    @Test fun usbWritesTheWifiDirectRouteForTheBackgroundEngine() {
        val v = values(transport = Transport.USB, path = WirelessPath.SAME_WIFI, wifi = joined).values
        assertEquals(0, v["native-ap-transport"])
        assertEquals("0", v["static-bssid"])
    }

    @Test fun musicAndNavigationFollowTheProfile() {
        val generic = values(settings = base.copy(bydDirections = true)).values
        assertEquals(false, generic["music-via-bluetooth"])
        assertEquals(false, generic["byd-navigation-enabled"])
        assertEquals(true, values(settings = base.copy(soundThroughCarBluetooth = true)).values["music-via-bluetooth"])

        val byd = values(settings = base.copy(bydDirections = true), profile = VehicleProfile.BYD_DILINK).values
        assertEquals(true, byd["music-via-bluetooth"])
        assertEquals(true, byd["byd-navigation-enabled"])
        assertEquals(false, values(settings = base.copy(bydDirections = false), profile = VehicleProfile.BYD_DILINK).values["byd-navigation-enabled"])
    }

    @Test fun pictureAndSizeKeepV1sTranslations() {
        val v = values(settings = base.copy(picture = Picture.BALANCED)).values
        assertEquals(2, v["resolutionId"])
        assertEquals(30, v["fps-limit"])
        assertEquals(0, v["dpi-pixel-density"])
        val custom = values(settings = base.copy(picture = Picture.CUSTOM)).values
        assertFalse(custom.containsKey("resolutionId"))
    }
}
