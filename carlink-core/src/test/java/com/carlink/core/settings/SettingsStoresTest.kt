package com.carlink.core.settings

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SettingsStoresTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test fun emptyUnifiedStoreLoadsTheDefaults() {
        assertEquals(UnifiedSettings(), UnifiedSettingsStore(app).load())
    }

    @Test fun unifiedSettingsRoundTripEveryField() {
        val settings = UnifiedSettings(
            connectionMethod = ConnectionMethod.CAR_HOTSPOT,
            carHotspot = WifiDetails("BYD-Car ☀", "carpassword"),
            homeWifi = WifiDetails("Home \"Net\"", ""),
            picture = Picture.LIGHT,
            interfaceSize = InterfaceSize.CUSTOM,
            dayNight = DayNight.SCHEDULE,
            nightStartMinute = 21 * 60 + 15,
            nightEndMinute = 5 * 60 + 45,
            rightHandDrive = true,
            soundThroughCarBluetooth = true,
            sendCarLocation = false,
            bydDirections = false,
            showAdvanced = true,
            labUnlocked = true,
        )
        UnifiedSettingsStore(app).save(settings)
        // A new instance reads what the old one wrote.
        assertEquals(settings, UnifiedSettingsStore(app).load())
    }

    @Test fun everyEnumValueRoundTrips() {
        val store = UnifiedSettingsStore(app)
        ConnectionMethod.entries.forEach { store.save(UnifiedSettings(connectionMethod = it)); assertEquals(it, store.load().connectionMethod) }
        Picture.entries.forEach { store.save(UnifiedSettings(picture = it)); assertEquals(it, store.load().picture) }
        InterfaceSize.entries.forEach { store.save(UnifiedSettings(interfaceSize = it)); assertEquals(it, store.load().interfaceSize) }
        DayNight.entries.forEach { store.save(UnifiedSettings(dayNight = it)); assertEquals(it, store.load().dayNight) }
    }

    @Test fun savingDefaultsAgainClearsEarlierValues() {
        val store = UnifiedSettingsStore(app)
        store.save(UnifiedSettings(carHotspot = WifiDetails("Car", "carpassword"), rightHandDrive = true))
        store.save(UnifiedSettings())
        assertEquals(UnifiedSettings(), store.load())
    }

    @Test fun unknownEnumNamesFromAnotherVersionFallBackToDefaults() {
        app.getSharedPreferences("carlink_unified", Context.MODE_PRIVATE).edit()
            .putString("connection_method", "BLUETOOTH_ONLY")
            .putString("picture", "ULTRA")
            .putString("interface_size", "")
            .putString("day_night", "dusk")
            .commit()
        val loaded = UnifiedSettingsStore(app).load()
        assertEquals(ConnectionMethod.AUTOMATIC, loaded.connectionMethod)
        assertEquals(Picture.AUTOMATIC, loaded.picture)
        assertEquals(InterfaceSize.MEDIUM, loaded.interfaceSize)
        assertEquals(DayNight.AUTOMATIC, loaded.dayNight)
    }

    @Test fun carLinkPreferencesDefaults() {
        val prefs = CarLinkPreferences(app)
        assertTrue(prefs.autoConnectUsb)
        assertTrue(prefs.autoConnectBluetooth)
        assertFalse(prefs.startOnBoot)
        assertFalse(prefs.autoConnectPaused)
    }

    @Test fun carLinkPreferencesPersistAcrossInstances() {
        CarLinkPreferences(app).apply {
            autoConnectUsb = false
            autoConnectBluetooth = false
            startOnBoot = true
            autoConnectPaused = true
        }
        CarLinkPreferences(app).apply {
            assertFalse(autoConnectUsb)
            assertFalse(autoConnectBluetooth)
            assertTrue(startOnBoot)
            assertTrue(autoConnectPaused)
        }
    }
}
