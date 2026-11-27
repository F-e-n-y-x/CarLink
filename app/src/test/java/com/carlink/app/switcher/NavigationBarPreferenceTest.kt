package com.carlink.app.switcher

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class NavigationBarPreferenceTest {
    private lateinit var app: Application

    @Before fun clear() {
        app = RuntimeEnvironment.getApplication()
        NavigationBarPreference.preferences(app).edit().clear().commit()
    }

    @Test fun theBarIsOffUntilTheDriverTurnsItOn() {
        assertFalse(NavigationBarPreference.DEFAULT_ENABLED)
        assertFalse(NavigationBarPreference.enabled(app))
    }

    @Test fun theSwitchIsKept() {
        NavigationBarPreference.setEnabled(app, true)
        assertTrue(NavigationBarPreference.enabled(app))
        NavigationBarPreference.setEnabled(app, false)
        assertFalse(NavigationBarPreference.enabled(app))
    }

    @Test fun theAccessibilityServiceIsNotOnUntilAndroidListsIt() {
        assertFalse(NavigationBarService.isEnabled(app))
    }
}
