package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CustomResolutionPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clearPreferences() { context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().apply() }
    @Test fun customPercentMigratesOldSettingsAndSurvivesLegacySettingsSave() {
        AirPlayPersistence.saveDisplayScaleTenths(context, 6)
        assertEquals(60, AirPlayPersistence.loadDisplayScalePercent(context))
        AirPlayPersistence.saveDisplayScalePercent(context, 55)
        AirPlayPersistence.saveDisplayScaleTenths(context, 6)
        assertEquals(55, AirPlayPersistence.loadDisplayScalePercent(context))
    }

    @Test fun customPercentKeepsTheExtendedRange() {
        AirPlayPersistence.saveDisplayScalePercent(context, 150)
        assertEquals(150, AirPlayPersistence.loadDisplayScalePercent(context))
        AirPlayPersistence.saveDisplayScalePercent(context, CarPlayDisplayScale.MAX_PERCENT)
        assertEquals(CarPlayDisplayScale.MAX_PERCENT, AirPlayPersistence.loadDisplayScalePercent(context))
        AirPlayPersistence.saveDisplayScalePercent(context, CarPlayDisplayScale.MAX_PERCENT + 60)
        assertEquals(CarPlayDisplayScale.MAX_PERCENT, AirPlayPersistence.loadDisplayScalePercent(context))
        AirPlayPersistence.saveDisplayScalePercent(context, 10)
        assertEquals(30, AirPlayPersistence.loadDisplayScalePercent(context))
    }

}
