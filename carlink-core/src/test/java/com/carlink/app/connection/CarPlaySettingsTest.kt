package com.carlink.app.connection

import android.app.Application
import com.carlink.app.settings.DayNight
import com.carlink.app.settings.InterfaceSize
import com.carlink.app.settings.Picture
import com.carlink.app.settings.UnifiedSettings
import com.carlink.app.settings.VehicleProfile
import com.carlink.app.settings.WifiDetails
import com.carlink.app.settings.WirelessPath
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayHostIntegration
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CarPlaySettingsTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @After fun restoreHooks() { CarPlayHostIntegration.vehicleIntegrations = true }

    private fun map(settings: UnifiedSettings = UnifiedSettings(), path: WirelessPath? = null,
                    profile: VehicleProfile = VehicleProfile.GENERIC, location: Boolean = true) =
        CarPlaySettingsPolicy.map(settings, path, profile, location)

    @Test fun pathsMapToDiPlayBackends() {
        assertEquals(WirelessHotspotMode.EXISTING_WIFI, map(path = WirelessPath.SAME_WIFI).wirelessMode)
        assertEquals(WirelessHotspotMode.MANUAL, map(path = WirelessPath.CAR_HOTSPOT).wirelessMode)
        assertEquals(WirelessHotspotMode.WIFI_P2P, map(path = WirelessPath.WIFI_DIRECT).wirelessMode)
        assertNull(map(path = null).wirelessMode)
    }

    @Test fun pictureSizeAndNightKeepTheVerifiedMapping() {
        val best = map(UnifiedSettings(picture = Picture.BEST, interfaceSize = InterfaceSize.SMALL, dayNight = DayNight.SCHEDULE))
        assertEquals(100, best.displayScalePercent); assertEquals(60, best.fps); assertEquals(350, best.widthPhysicalMm)
        assertEquals("schedule", best.nightModeKey)
        val custom = map(UnifiedSettings(picture = Picture.CUSTOM, interfaceSize = InterfaceSize.CUSTOM))
        assertNull(custom.displayScalePercent); assertNull(custom.fps); assertNull(custom.widthPhysicalMm)
        assertEquals(75, map(UnifiedSettings(picture = Picture.LIGHT)).displayScalePercent)
        assertEquals("system", map().nightModeKey)
    }

    @Test fun locationOnlyWithThePermission() {
        // F17: CarLink defaults "send location" on; DiPlay would ask before every start without it.
        assertTrue(map(UnifiedSettings(sendCarLocation = true), location = true).locationReporting)
        assertFalse(map(UnifiedSettings(sendCarLocation = true), location = false).locationReporting)
        assertFalse(map(UnifiedSettings(sendCarLocation = false), location = true).locationReporting)
    }

    @Test fun genericProfileTurnsEveryBydItemOff() {
        // F5: UnifiedSettings and DiPlay both default the BYD output on.
        val generic = map(UnifiedSettings(bydDirections = true)).vehicle
        assertFalse(generic.integrations)
        assertFalse(generic.navigationOutput)
        assertTrue(generic.forceBydExtrasOff)
        assertEquals("CarLink", generic.oemLabel)
        val byd = map(UnifiedSettings(bydDirections = true), profile = VehicleProfile.BYD_DILINK).vehicle
        assertTrue(byd.integrations)
        assertTrue(byd.navigationOutput)
        assertFalse(byd.forceBydExtrasOff)
        assertEquals("BYD", byd.oemLabel)
        assertFalse(map(UnifiedSettings(bydDirections = false), profile = VehicleProfile.BYD_DILINK).vehicle.navigationOutput)
    }

    @Test fun applyWritesGenericValuesIntoDiPlay() {
        BydOutputSettings.setBatteryToIphone(app, true)
        AirPlayPersistence.saveClusterMapEnabled(app, true)
        AirPlayPersistence.saveManualHotspotBand(app, ManualHotspotBand.GHZ_5)
        val settings = UnifiedSettings(homeWifi = WifiDetails("HomeNet", "homepassword"),
            carHotspot = WifiDetails("Car-AP", ""))
        CarPlaySettingsPolicy.apply(app, map(settings, WirelessPath.SAME_WIFI))

        assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(app))
        assertEquals("HomeNet", AirPlayPersistence.loadExistingWifiSsid(app))
        assertEquals("homepassword", AirPlayPersistence.loadExistingWifiPassphrase(app))
        assertEquals("Car-AP", AirPlayPersistence.loadManualHotspotSsid(app))
        assertEquals(ManualHotspotSecurity.OPEN, AirPlayPersistence.loadManualHotspotSecurity(app))
        // F17: the driver's band is no longer forced to AUTO.
        assertEquals(ManualHotspotBand.GHZ_5, AirPlayPersistence.loadManualHotspotBand(app))
        assertFalse(BydOutputSettings.enabled(app))
        assertFalse(BydOutputSettings.batteryToIphone(app))
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(app))
        assertFalse(CarPlayHostIntegration.vehicleIntegrations)
        assertEquals("CarLink", AirPlayPersistence.loadOemLabel(app))
    }

    @Test fun clearedSameWifiDetailsAreClearedInDiPlay() {
        AirPlayPersistence.saveExistingWifiCredentials(app, "OldNet", "oldpassword")
        CarPlaySettingsPolicy.apply(app, map(UnifiedSettings(homeWifi = WifiDetails()), WirelessPath.WIFI_DIRECT))
        assertEquals("", AirPlayPersistence.loadExistingWifiSsid(app))
        assertEquals("", AirPlayPersistence.loadExistingWifiPassphrase(app))
    }

    @Test fun aCarNameTheDriverTypedIsKept() {
        AirPlayPersistence.saveOemLabel(app, "Volvo")
        CarPlaySettingsPolicy.apply(app, map())
        assertEquals("Volvo", AirPlayPersistence.loadOemLabel(app))
        AirPlayPersistence.saveOemLabel(app, "CarLink")
        CarPlaySettingsPolicy.apply(app, map(profile = VehicleProfile.BYD_DILINK))
        assertEquals("BYD", AirPlayPersistence.loadOemLabel(app))
        assertTrue(CarPlayHostIntegration.vehicleIntegrations)
    }
}
