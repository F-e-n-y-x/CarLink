package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-sw1333dp-w2666dp-h1225dp-land-mdpi", manifest = Config.NONE)
class InterfaceSizeActivityTest {
    @Test
    fun scaledSettingsKeepTheChosenLanguageBeforeAndroid13() {
        CarPlayBackgroundSession.clear()
        AppLocale.save(RuntimeEnvironment.getApplication(), AppLocale.ARABIC)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        controller.setup()
        try {
            val configuration = activity.resources.configuration
            assertEquals(720, configuration.smallestScreenWidthDp)
            assertEquals("ar", configuration.locales[0].language)
        } finally {
            controller.pause().stop().destroy()
            AppLocale.save(RuntimeEnvironment.getApplication(), AppLocale.SYSTEM)
        }
    }
}
