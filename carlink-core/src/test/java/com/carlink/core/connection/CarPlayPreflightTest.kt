package com.carlink.core.connection

import android.Manifest
import com.carlink.core.core.Transport
import com.carlink.core.settings.WifiDetails
import com.carlink.core.settings.WirelessPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pre-flight v2 (DIPLAY.md F3, F4, F7, F16, F17). */
class CarPlayPreflightTest {
    private val iphone = "AA:BB:CC:DD:EE:FF"
    private val home = WifiDetails("HomeNet", "homepassword")
    private val allPermissions = setOf(
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        CarPlayPreflight.ACCESS_LOCAL_NETWORK,
    )

    private val ready = CarPlayPreflightInput(
        transport = Transport.WIRELESS, path = WirelessPath.WIFI_DIRECT, sdkInt = 35,
        authenticationReady = true, grantedPermissions = allPermissions, vpnConsentGranted = false,
        bluetoothOn = true, bondedAddresses = setOf(iphone), iphoneAddress = iphone,
        wifiOn = true, locationOn = true, homeWifi = home, carHotspot = WifiDetails(),
        joinedNetworks = 1, joinedSsid = "HomeNet", carHotspotOn = null, carHotspotCanStart = false,
    )

    private fun check(input: CarPlayPreflightInput) = CarPlayPreflight.check(input)

    @Test fun readyWirelessSessionHasNoProblem() {
        assertEquals(emptyList<CarPlayProblem>(), check(ready))
        assertEquals(emptyList<CarPlayProblem>(), check(ready.copy(path = WirelessPath.SAME_WIFI)))
    }

    @Test fun usbNeedsOnlyAuthenticationAndVpnConsent() {
        val usb = ready.copy(transport = Transport.USB, path = null, bluetoothOn = false, iphoneAddress = null,
            grantedPermissions = emptySet())
        assertEquals(listOf(CarPlayProblem.VpnConsentNeeded), check(usb))
        assertEquals(emptyList<CarPlayProblem>(), check(usb.copy(vpnConsentGranted = true)))
        assertEquals(listOf(CarPlayProblem.AuthenticationMissing), check(usb.copy(vpnConsentGranted = true, authenticationReady = false)))
    }

    @Test fun bluetoothOffAndPairing() {
        assertEquals(listOf(CarPlayProblem.BluetoothOff), check(ready.copy(bluetoothOn = false)))
        assertEquals(listOf(CarPlayProblem.BluetoothOff), check(ready.copy(bluetoothOn = null)))
        assertEquals(listOf(CarPlayProblem.NoIphoneSelected), check(ready.copy(iphoneAddress = null)))
        assertEquals(listOf(CarPlayProblem.IphoneNotBonded(iphone)), check(ready.copy(bondedAddresses = setOf("11:22:33:44:55:66"))))
        // Address case does not matter; unreadable bonds are not guessed at.
        assertEquals(emptyList<CarPlayProblem>(), check(ready.copy(iphoneAddress = iphone.lowercase())))
        assertEquals(emptyList<CarPlayProblem>(), check(ready.copy(bondedAddresses = null)))
    }

    @Test fun wirelessPermissionsFollowTheHostAndAndroid17() {
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES),
            CarPlayPreflight.requiredPermissions(Transport.WIRELESS, WirelessPath.WIFI_DIRECT, 33))
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION), CarPlayPreflight.requiredPermissions(Transport.WIRELESS, WirelessPath.CAR_HOTSPOT, 31))
        assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            CarPlayPreflight.requiredPermissions(Transport.WIRELESS, WirelessPath.WIFI_DIRECT, 29))
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT),
            CarPlayPreflight.requiredPermissions(Transport.WIRELESS, WirelessPath.SAME_WIFI, 35))
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT, CarPlayPreflight.ACCESS_LOCAL_NETWORK),
            CarPlayPreflight.requiredPermissions(Transport.WIRELESS, WirelessPath.SAME_WIFI, 37))
        assertEquals(emptyList<String>(), CarPlayPreflight.requiredPermissions(Transport.USB, null, 37))

        val sdk37 = ready.copy(sdkInt = 37, grantedPermissions = allPermissions - CarPlayPreflight.ACCESS_LOCAL_NETWORK)
        assertEquals(listOf(CarPlayProblem.PermissionsMissing(listOf(CarPlayPreflight.ACCESS_LOCAL_NETWORK))), check(sdk37))
        assertEquals(emptyList<CarPlayProblem>(), check(sdk37.copy(sdkInt = 36)))
    }

    @Test fun sameWifiNeedsItsDetailsAndAReadableMatchingNetwork() {
        val same = ready.copy(path = WirelessPath.SAME_WIFI)
        assertEquals(listOf(CarPlayProblem.SameWifiDetailsMissing), check(same.copy(homeWifi = WifiDetails())))
        assertEquals(listOf(CarPlayProblem.SameWifiDetailsMissing), check(same.copy(homeWifi = WifiDetails("HomeNet", "short"))))
        assertEquals(listOf(CarPlayProblem.SameWifiNotConnected), check(same.copy(joinedNetworks = 0, joinedSsid = null)))
        assertEquals(listOf(CarPlayProblem.SameWifiMultipleNetworks), check(same.copy(joinedNetworks = 2, joinedSsid = null)))
        assertEquals(listOf(CarPlayProblem.SameWifiNameUnreadable(needsLocationPermission = true, needsLocationOn = true)),
            check(same.copy(joinedSsid = null, locationOn = false,
                grantedPermissions = allPermissions - Manifest.permission.ACCESS_FINE_LOCATION)))
        assertEquals(listOf(CarPlayProblem.SameWifiDifferentNetwork("HomeNet-5G", "HomeNet")), check(same.copy(joinedSsid = "HomeNet-5G")))
        // No trimming: DiPlay sends the saved name as typed (§3.4 #7).
        assertEquals(listOf(CarPlayProblem.SameWifiDifferentNetwork("HomeNet", "HomeNet ")),
            check(same.copy(homeWifi = WifiDetails("HomeNet ", "homepassword"))))
        // Wi-Fi off is one problem, not two.
        assertEquals(listOf(CarPlayProblem.WifiOff), check(same.copy(wifiOn = false, joinedNetworks = 0, joinedSsid = null)))
    }

    @Test fun carHotspotNeedsDetailsAndAnAccessPoint() {
        val hotspot = ready.copy(path = WirelessPath.CAR_HOTSPOT, wifiOn = false)
        assertEquals(listOf(CarPlayProblem.CarHotspotDetailsMissing), check(hotspot))
        val details = hotspot.copy(carHotspot = WifiDetails("Car-AP", "carpassword"))
        assertEquals(emptyList<CarPlayProblem>(), check(details)) // unknown state is not "off" (§12 row 7)
        assertEquals(listOf(CarPlayProblem.CarHotspotOff), check(details.copy(carHotspotOn = false)))
        assertEquals(emptyList<CarPlayProblem>(), check(details.copy(carHotspotOn = false, carHotspotCanStart = true)))
    }

    @Test fun wifiDirectNeedsWifiOn() {
        assertEquals(listOf(CarPlayProblem.WifiOff), check(ready.copy(wifiOn = false)))
        assertEquals(listOf(CarPlayProblem.WifiOff), check(ready.copy(path = null, wifiOn = false)))
    }

    @Test fun everyProblemIsReportedMostFundamentalFirst() {
        val all = check(ready.copy(authenticationReady = false, grantedPermissions = emptySet(), bluetoothOn = false,
            iphoneAddress = null, wifiOn = false))
        assertEquals(CarPlayProblem.AuthenticationMissing, all.first())
        assertTrue(all.contains(CarPlayProblem.BluetoothOff))
        assertTrue(all.contains(CarPlayProblem.NoIphoneSelected))
        assertTrue(all.any { it is CarPlayProblem.PermissionsMissing })
        assertEquals(CarPlayProblem.WifiOff, all.last())
    }
}
