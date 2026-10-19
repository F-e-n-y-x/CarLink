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

    @Test fun throughTheStereosModuleAndroidsBluetoothDoesNotMatter() {
        val offAndUnpaired = ready.copy(bluetoothOn = false, phoneBonded = false)
        assertEquals(emptyList<Problem>(),
            DevicePreflight.check(wireless, WirelessPath.WIFI_DIRECT, offAndUnpaired, UnifiedSettings(), onHome, bluetoothViaModule = true))
        assertEquals(emptyList<Problem>(), DevicePreflight.check(wireless, WirelessPath.WIFI_DIRECT,
            ready.copy(phoneBonded = false), UnifiedSettings(), onHome, bluetoothViaModule = true))
        // Wi-Fi Direct still needs the radio and Location.
        assertEquals(listOf(Problem.WIFI_OFF), DevicePreflight.check(wireless, WirelessPath.WIFI_DIRECT,
            offAndUnpaired.copy(wifiOn = false), UnifiedSettings(), onHome, bluetoothViaModule = true))
        assertEquals(listOf(Problem.BLUETOOTH_OFF), check(offAndUnpaired))
    }

    @Test fun eachMissingThingIsNamed() {
        assertEquals(listOf(Problem.BLUETOOTH_OFF), check(ready.copy(bluetoothOn = false)))
        assertEquals(listOf(Problem.PHONE_NOT_PAIRED), check(ready.copy(phoneBonded = false)))
        assertEquals(listOf(Problem.PAIRING_LOST), check(ready.copy(pairingLost = true)))
        assertEquals(emptyList<Problem>(), check(ready.copy(pairingLost = true), request = usb))
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
        assertEquals(listOf(Problem.SAME_WIFI_DETAILS), check(path = WirelessPath.SAME_WIFI))
        assertEquals(emptyList<Problem>(), check(path = WirelessPath.SAME_WIFI, settings = UnifiedSettings(homeWifi = WifiDetails("Home", "")),
            wifi = onHome.copy(joinedOpen = true)))
    }

    // CarLink 1.0.0 (tablet, 2026-10-20): the password was saved, yet Home said "Add your Wi-Fi password".
    @Test fun sameWifiWithTheRightPasswordNamesWhatIsActuallyWrong() {
        val saved = UnifiedSettings(homeWifi = WifiDetails("Home", "homepassword"))
        fun sameWifi(facts: DevicePreflight.Facts = ready, wifi: WifiEnvironment) =
            check(facts, path = WirelessPath.SAME_WIFI, settings = saved, wifi = wifi)
        // On another network, on none, or on two at once (the name is not read then).
        assertEquals(listOf(Problem.SAME_WIFI_NOT_JOINED), sameWifi(wifi = WifiEnvironment(1, "Office", null)))
        assertEquals(listOf(Problem.SAME_WIFI_NOT_JOINED), sameWifi(wifi = WifiEnvironment(0, null, null)))
        assertEquals(listOf(Problem.SAME_WIFI_NOT_JOINED), sameWifi(wifi = WifiEnvironment(2, null, null)))
        // Android hides the name with Location off or not allowed, and Wi-Fi off has no name: only that is said.
        assertEquals(listOf(Problem.LOCATION_OFF), sameWifi(ready.copy(locationOn = false), WifiEnvironment(1, null, null)))
        assertEquals(listOf(Problem.PERMISSIONS), sameWifi(ready.copy(missingPermissions = listOf("x")), WifiEnvironment(1, null, null)))
        assertEquals(listOf(Problem.WIFI_OFF), sameWifi(ready.copy(wifiOn = false), WifiEnvironment(0, null, null)))
        // The names are compared exactly; saving aligns a near-miss (WifiDetailsTest).
        assertEquals(listOf(Problem.SAME_WIFI_NOT_JOINED), sameWifi(wifi = WifiEnvironment(1, "home", null)))
    }

    // User report (2026-10-20): "Wi-Fi Direct works only when the Wi-Fi password is entered in both".
    // No Wi-Fi Direct decision reads the saved Same Wi-Fi details, for either engine.
    @Test fun wifiDirectNeedsNoSavedWifiForEitherEngine() {
        val none = UnifiedSettings()
        val saved = UnifiedSettings(homeWifi = WifiDetails("Home", "homepassword"))
        for (engine in Engine.entries) {
            // Automatic never picks Same Wi-Fi, saved or not.
            for (s in listOf(none, saved)) {
                val paths = WirelessPolicy.candidates(WirelessPolicy.Inputs(engine, s, onHome, headUnit = false))
                assertEquals(listOf(WirelessPath.WIFI_DIRECT), paths)
            }
            assertEquals(emptyList<Problem>(), DevicePreflight.check(wireless.copy(engine = engine), WirelessPath.WIFI_DIRECT, ready, none, onHome))
        }
        val carPlay = CarPlayPreflightInput(
            transport = Transport.WIRELESS, path = WirelessPath.WIFI_DIRECT, sdkInt = 35, authenticationReady = true,
            grantedPermissions = CarPlayPreflight.requiredPermissions(Transport.WIRELESS, WirelessPath.WIFI_DIRECT, 35).toSet(),
            vpnConsentGranted = false, bluetoothOn = true, bondedAddresses = setOf("AA:BB"), iphoneAddress = "AA:BB",
            wifiOn = true, locationOn = true, homeWifi = WifiDetails(), carHotspot = WifiDetails(),
            joinedNetworks = 0, joinedSsid = null, carHotspotOn = null, carHotspotCanStart = false,
        )
        assertEquals(emptyList<CarPlayProblem>(), CarPlayPreflight.check(carPlay))
        // Android Auto's Wi-Fi Direct route carries no network name or password at all.
        for (s in listOf(none, saved)) {
            val v = com.carlink.core.connection.aa.AaSessionSettings.values(s, Transport.WIRELESS, WirelessPath.WIFI_DIRECT,
                com.carlink.core.settings.VehicleProfile.GENERIC, "Car", 320, onHome).values
            assertEquals("", v["hotspot-ssid"])
            assertEquals("", v["hotspot-password"])
        }
    }

    @Test fun hotspotNeedsItsDetailsAndToBeOn() {
        val s = UnifiedSettings(carHotspot = WifiDetails("CarAP", "carpassword"))
        assertEquals(emptyList<Problem>(), check(path = WirelessPath.CAR_HOTSPOT, settings = s))
        assertEquals(listOf(Problem.CAR_HOTSPOT_OFF), check(path = WirelessPath.CAR_HOTSPOT, settings = s, wifi = onHome.copy(carHotspotOn = false)))
        assertEquals(listOf(Problem.CAR_HOTSPOT_DETAILS), check(path = WirelessPath.CAR_HOTSPOT))
    }
}
