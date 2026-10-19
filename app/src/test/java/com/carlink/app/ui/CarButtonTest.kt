package com.carlink.app.ui

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The car tile in CarPlay / Android Auto opens Settings, and Back from there returns to the picture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CarButtonTest {
    @Test fun backFromCarSettingsReturnsToTheLiveProjectionOnly() {
        assertTrue(backReturnsToProjection(Route.SETTINGS, fromCar = true, live = true))
        assertFalse("the session ended meanwhile", backReturnsToProjection(Route.SETTINGS, fromCar = true, live = false))
        assertFalse("Settings opened from Home", backReturnsToProjection(Route.SETTINGS, fromCar = false, live = true))
        assertFalse("deeper screen (About)", backReturnsToProjection(Route.ABOUT, fromCar = true, live = true))
    }

    @Test fun carSettingsIntentOpensHomeOnTheSettingsPage() {
        val intent = HomeActivity.carSettingsIntent(RuntimeEnvironment.getApplication())
        assertEquals(HomeActivity::class.java.name, intent.component?.className)
        assertEquals("settings", intent.getStringExtra("page"))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
