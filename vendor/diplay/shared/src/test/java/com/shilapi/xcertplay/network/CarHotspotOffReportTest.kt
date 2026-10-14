package com.shilapi.xcertplay.network

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotOffReportTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before @After fun clear() {
        context.getSharedPreferences("diplay_car_hotspot", 0).edit().clear().commit()
    }

    @Test fun anOffReportCountsUntilTheDriverConnectsAnyway() {
        assertTrue(CarHotspotSettings.reportedOff(context, false))
        CarHotspotSettings.setOffReportIgnored(context, true)
        assertFalse(CarHotspotSettings.reportedOff(context, false))
    }

    @Test fun anOnReportClearsTheDriversChoice() {
        CarHotspotSettings.setOffReportIgnored(context, true)
        assertFalse(CarHotspotSettings.reportedOff(context, true))
        assertFalse(CarHotspotSettings.offReportIgnored(context))
        assertTrue(CarHotspotSettings.reportedOff(context, false))
    }

    @Test fun anUnknownStateNeverBlocksAndKeepsTheChoice() {
        assertFalse(CarHotspotSettings.reportedOff(context, null))
        CarHotspotSettings.setOffReportIgnored(context, true)
        assertFalse(CarHotspotSettings.reportedOff(context, null))
        assertTrue(CarHotspotSettings.offReportIgnored(context))
    }
}
