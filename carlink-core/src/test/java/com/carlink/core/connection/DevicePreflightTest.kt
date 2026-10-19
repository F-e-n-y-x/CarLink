package com.carlink.core.connection

import com.carlink.core.core.Engine
import com.carlink.core.core.Transport
import com.carlink.core.settings.UnifiedSettings
import com.carlink.core.settings.WifiDetails
import com.carlink.core.settings.WifiEnvironment
import com.carlink.core.settings.WirelessPath
import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePreflightTest {
    private val ready = DevicePreflight.Facts(bluetoothOn = true, phoneBonded = true, wifiOn = true, locationOn = true,
        missingPermissions = emptyList(), canDrawOverlays = true)
    private val wireless = SessionRequest(Engine.ANDROID_AUTO, Transport.WIRELESS, "AA:BB", "Pixel")
    private val usb = SessionRequest(Engine.ANDROID_AUTO, Transport.USB, null, "Pixel")
    private val onHome = WifiEnvironment(1, "Home", null)

    private fun check(facts: DevicePreflight.Facts = ready, request: SessionRequest = wireless, path: WirelessPath? = WirelessPath.WIFI_DIRECT,
                      settings: UnifiedSettings = UnifiedSettings(), wifi: WifiEnvironment = onHome) =
        DevicePreflight.check(request, path, facts, settings, wifi)

    @Test fun readyDeviceHasNoProblems() = assertEquals(emptyList<Problem>(), check())

    @Test fun eachMissingThingIsNamed() {
        assertEquals(listOf(Problem.BLUETOOTH_OFF), check(ready.copy(bluetoothOn = false)))
        assertEquals(listOf(Problem.PHONE_NOT_PAIRED), check(ready.copy(phoneBonded = false)))
        assertEquals(listOf(Problem.WIFI_OFF), check(ready.copy(wifiOn = false)))
        assertEquals(listOf(Problem.LOCATION_OFF), check(ready.copy(locationOn = false)))
        assertEquals(listOf(Problem.PERMISSIONS), check(ready.copy(missingPermissions = listOf("x"))))
    }

    @Test fun overlayNeverBlocksASession() { // issue #2
        assertEquals(emptyList<Problem>(), check(ready.copy(canDrawOverlays = false)))
        assertEquals(emptyList<Problem>(), check(ready.copy(canDrawOverlays = false), usb, null))
    }

    @Test fun usbNeedsNoRadios() {
        assertEquals(emptyList<Problem>(), check(ready.copy(bluetoothOn = false, wifiOn = false, locationOn = false), usb, null))
    }

    @Test fun sameWifiNeedsTheJoinedNetworksPassword() {
        val saved = UnifiedSettings(homeWifi = WifiDetails("Home", "homepassword"))
        assertEquals(emptyList<Problem>(), check(path = WirelessPath.SAME_WIFI, settings = saved))
        assertEquals(listOf(Problem.SAME_WIFI_DETAILS), check(path = WirelessPath.SAME_WIFI, settings = UnifiedSettings(homeWifi = WifiDetails("Home", ""))))
        assertEquals(listOf(Problem.SAME_WIFI_DETAILS), check(path = WirelessPath.SAME_WIFI, settings = saved, wifi = WifiEnvironment(1, null, null)))
        assertEquals(emptyList<Problem>(), check(path = WirelessPath.SAME_WIFI, settings = UnifiedSettings(homeWifi = WifiDetails("Home", "")),
            wifi = onHome.copy(joinedOpen = true)))
    }

    @Test fun hotspotNeedsItsDetailsAndToBeOn() {
        val s = UnifiedSettings(carHotspot = WifiDetails("CarAP", "carpassword"))
        assertEquals(emptyList<Problem>(), check(path = WirelessPath.CAR_HOTSPOT, settings = s))
        assertEquals(listOf(Problem.CAR_HOTSPOT_OFF), check(path = WirelessPath.CAR_HOTSPOT, settings = s, wifi = onHome.copy(carHotspotOn = false)))
        assertEquals(listOf(Problem.CAR_HOTSPOT_DETAILS), check(path = WirelessPath.CAR_HOTSPOT))
    }
}
