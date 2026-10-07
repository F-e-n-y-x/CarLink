package com.shilapi.xcertplay

import android.content.res.Configuration
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class NavigationWidgetAppearanceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        AppAppearanceRuntime.resetForTest()
    }

    @After fun cleanUp() = AppAppearanceRuntime.resetForTest()

    @Test fun coldProcessWidgetResolvesExplicitAppearancesFromPersistence() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        assertEquals(
            NavigationWidgetAppearance(
                R.drawable.widget_navigation_background_light,
                DiPlayPalette.LIGHT.primaryText,
                DiPlayPalette.LIGHT.secondaryText,
            ),
            NavigationWidgetAppearance.resolve(context),
        )

        AirPlayPersistence.saveAppAppearance(context, AppAppearance.DARK)
        assertEquals(
            NavigationWidgetAppearance(
                R.drawable.widget_navigation_background,
                DiPlayPalette.DARK.primaryText,
                DiPlayPalette.DARK.secondaryText,
            ),
            NavigationWidgetAppearance.resolve(context),
        )
    }

    @Test fun coldProcessAutoUsesTheSavedCarPlayPolicy() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.AUTO)
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.DAY)
        context.resources.configuration.uiMode = Configuration.UI_MODE_NIGHT_YES
        assertEquals(DiPlayPalette.LIGHT.primaryText, NavigationWidgetAppearance.resolve(context).primaryText)

        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.NIGHT)
        context.resources.configuration.uiMode = Configuration.UI_MODE_NIGHT_NO
        assertEquals(DiPlayPalette.DARK.primaryText, NavigationWidgetAppearance.resolve(context).primaryText)
    }
}
