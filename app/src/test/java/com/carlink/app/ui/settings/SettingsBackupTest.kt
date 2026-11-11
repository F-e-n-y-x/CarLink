package com.carlink.app.ui.settings

import android.app.Application
import com.carlink.app.ui.UiPreferences
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.core.settings.AdvancedSettings
import com.carlink.core.settings.CarBrand
import com.carlink.core.settings.CarLinkPreferences
import com.carlink.core.settings.Control
import com.carlink.core.settings.DayNight
import com.carlink.core.settings.InterfaceSize
import com.carlink.core.settings.UnifiedSettings
import com.carlink.core.settings.UnifiedSettingsStore
import com.carlink.core.settings.WheelAction
import com.carlink.core.settings.WifiDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Back up and Restore: every value survives the round trip, passwords only on request, bad files change nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsBackupTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private val custom = UnifiedSettings(
        carHotspot = WifiDetails("Car", "hotspot-pass"), homeWifi = WifiDetails("Home", "home-pass"),
        interfaceSize = InterfaceSize.LARGE, dayNight = DayNight.SCHEDULE, nightStartMinute = 20 * 60, rightHandDrive = true,
        sendCarLocation = false, showAdvanced = true, wheelKeys = mapOf(WheelAction.VOICE to 231),
    )

    private fun setUp() {
        UnifiedSettingsStore(app).save(custom)
        UiPreferences(app).apply { themeMode = ThemeMode.LIGHT; interfaceScale = 1.3f; floatingSwitcher = false }
        CarLinkPreferences(app).apply { autoConnectUsb = false; rootMode = true }
    }

    private fun wipe() = listOf("carlink_unified", "carlink").forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }

    @Test fun everyValueSurvivesARoundTrip() {
        setUp()
        val toggle = AdvancedSettings.all.first { it.control is Control.Toggle }
        val text = SettingsBackup.export(app, CarBrand.HYUNDAI, mapOf(toggle.id to "true", "not-a-setting" to "x"), withPasswords = true)
        wipe()
        val backup = SettingsBackup.parse(text)
        assertEquals(CarBrand.HYUNDAI, backup.carBrand)
        assertEquals(mapOf(toggle.id to "true"), backup.advanced)
        SettingsBackup.applyPreferences(app, backup)
        assertEquals(custom, UnifiedSettingsStore(app).load())
        UiPreferences(app).apply {
            assertEquals(ThemeMode.LIGHT, themeMode)
            assertEquals(1.3f, interfaceScale)
            assertFalse(floatingSwitcher)
        }
        assertFalse(CarLinkPreferences(app).autoConnectUsb)
        assertFalse("root mode is this device's grant, never restored", CarLinkPreferences(app).rootMode)
    }

    @Test fun passwordsStayOutUnlessAskedAndTheSavedOnesAreKept() {
        setUp()
        val text = SettingsBackup.export(app, null, null, withPasswords = false)
        assertFalse(text.contains("hotspot-pass") || text.contains("home-pass"))
        UnifiedSettingsStore(app).save(custom.copy(homeWifi = WifiDetails("Home", "newer-pass"), interfaceSize = InterfaceSize.SMALL))
        SettingsBackup.applyPreferences(app, SettingsBackup.parse(text))
        val restored = UnifiedSettingsStore(app).load()
        assertEquals("newer-pass", restored.homeWifi.password)
        assertEquals(InterfaceSize.LARGE, restored.interfaceSize)
    }

    @Test fun aRestoreResetsValuesTheBackupDoesNotHold() {
        val text = SettingsBackup.export(app, null, null, withPasswords = false) // all defaults: nothing stored
        UiPreferences(app).themeMode = ThemeMode.DARK
        SettingsBackup.applyPreferences(app, SettingsBackup.parse(text))
        assertEquals(ThemeMode.FOLLOW_SYSTEM, UiPreferences(app).themeMode)
    }

    @Test fun badFilesAreRefusedBeforeAnythingIsWritten() {
        val good = """{"format":"carlink-settings","version":1,"settings":{}}"""
        SettingsBackup.parse(good)
        listOf(
            "not json",
            """{"format":"something-else","version":1}""",
            """{"format":"carlink-settings","version":99}""",
            """{"format":"carlink-settings","version":1,"settings":{"carlink_unified":{"interface_size":"HUGE"}}}""",
            """{"format":"carlink-settings","version":1,"settings":{"carlink_unified":{"night_start":5000}}}""",
            """{"format":"carlink-settings","version":1,"settings":{"carlink":{"interface_scale":9.0}}}""",
            """{"format":"carlink-settings","version":1,"settings":{"carlink":{"start_on_boot":"yes"}}}""",
            """{"format":"carlink-settings","version":1,"carBrand":"DELOREAN"}""",
            """{"format":"carlink-settings","version":1,"advanced":{"no-such-setting":"1"}}""",
        ).forEach { text ->
            try {
                SettingsBackup.parse(text)
                fail("accepted: $text")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }
}
