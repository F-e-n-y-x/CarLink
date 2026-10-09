package com.carlink.app.ui

import android.app.Application
import android.content.Context
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfileMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class UiPreferencesTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test fun uiPreferencesDefaults() {
        val prefs = UiPreferences(app)
        assertEquals(ThemeMode.FOLLOW_SYSTEM, prefs.themeMode)
        assertEquals(UiProfileMode.AUTO, prefs.uiProfileMode)
        assertEquals(1f, prefs.interfaceScale)
        assertTrue(prefs.interfaceScale in UiPreferences.INTERFACE_SCALES)
    }

    @Test fun uiPreferencesPersistAcrossInstances() {
        UiPreferences(app).apply {
            themeMode = ThemeMode.entries.last()
            uiProfileMode = UiProfileMode.entries.last()
            interfaceScale = 1.3f
        }
        UiPreferences(app).apply {
            assertEquals(ThemeMode.entries.last(), themeMode)
            assertEquals(UiProfileMode.entries.last(), uiProfileMode)
            assertEquals(1.3f, interfaceScale)
        }
    }

    @Test fun unknownThemeNameFallsBackToFollowSystem() {
        app.getSharedPreferences("carlink", Context.MODE_PRIVATE).edit()
            .putString("theme_mode", "SEPIA").putString("ui_profile_mode", "WATCH").commit()
        assertEquals(ThemeMode.FOLLOW_SYSTEM, UiPreferences(app).themeMode)
        assertEquals(UiProfileMode.AUTO, UiPreferences(app).uiProfileMode)
    }
}
