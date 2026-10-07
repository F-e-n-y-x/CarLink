package com.shilapi.xcertplay

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class InterfaceSizeTest {
    private fun screen(widthDp: Int, heightDp: Int, densityDpi: Int) = Configuration().apply {
        screenWidthDp = widthDp
        screenHeightDp = heightDp
        smallestScreenWidthDp = minOf(widthDp, heightDp)
        this.densityDpi = densityDpi
    }

    @Test
    fun autoEnlargesALowDensityWideScreenToTheTargetWidth() {
        val override = InterfaceSize.override(screen(2666, 1333, 144), InterfaceSize.AUTO)!!

        assertEquals(720, override.smallestScreenWidthDp)
        assertEquals(1440, override.screenWidthDp)
        assertEquals(267, override.densityDpi)
    }

    @Test
    fun autoLeavesOrdinaryScreensAlone() {
        assertNull(InterfaceSize.override(screen(1280, 720, 160), InterfaceSize.AUTO))
        assertNull(InterfaceSize.override(screen(400, 800, 420), InterfaceSize.AUTO))
        assertNull(InterfaceSize.override(screen(2666, 1333, 144), 100))
    }

    @Test
    fun aFixedChoiceScalesBySelf() {
        val override = InterfaceSize.override(screen(1280, 720, 160), 150)!!

        assertEquals(240, override.densityDpi)
        assertEquals(853, override.screenWidthDp)
        assertEquals(480, override.smallestScreenWidthDp)
    }

    @Test
    fun enforceRewritesMetricsThatAFrameworkPutBack() {
        val resources = org.robolectric.RuntimeEnvironment.getApplication().resources
        val override = InterfaceSize.override(screen(2666, 1333, 144), InterfaceSize.AUTO)!!

        assertEquals(true, InterfaceSize.enforce(resources, override))
        assertEquals(267, resources.displayMetrics.densityDpi)
        assertEquals(267 / 160f, resources.displayMetrics.density)
        assertEquals(720, resources.configuration.smallestScreenWidthDp)
        assertEquals(false, InterfaceSize.enforce(resources, override))
    }

    @Test
    fun enforceAlsoRewritesDimensionsWhenOnlyTheyWereReset() {
        val resources = org.robolectric.RuntimeEnvironment.getApplication().resources
        val override = InterfaceSize.override(screen(2666, 1333, 144), InterfaceSize.AUTO)!!
        InterfaceSize.enforce(resources, override)
        resources.configuration.screenWidthDp = 2666

        assertEquals(true, InterfaceSize.enforce(resources, override))
        assertEquals(1440, resources.configuration.screenWidthDp)
    }

    @Test
    fun onlyADensityChangeNeedsANewActivity() {
        val applied = InterfaceSize.override(screen(2666, 1333, 144), InterfaceSize.AUTO)
        val wider = InterfaceSize.override(screen(2666, 1333, 144).apply { screenWidthDp = 2000 }, InterfaceSize.AUTO)
        val split = InterfaceSize.override(screen(1333, 1225, 144), InterfaceSize.AUTO)

        assertEquals(false, InterfaceSize.needsRecreate(applied, wider))
        assertEquals(true, InterfaceSize.needsRecreate(applied, split))
        assertEquals(true, InterfaceSize.needsRecreate(null, applied))
        assertEquals(false, InterfaceSize.needsRecreate(null, null))
        assertEquals(true, InterfaceSize.reportsScaled(applied, screen(1440, 720, 267)))
        assertEquals(false, InterfaceSize.reportsScaled(applied, screen(2666, 1333, 144)))
        assertEquals(false, InterfaceSize.reportsScaled(null, screen(2666, 1333, 144)))
    }

    @Test
    fun theOverrideKeepsTheBaseLanguage() {
        val base = screen(2666, 1333, 144).apply { setLocales(android.os.LocaleList(java.util.Locale("ar"))) }

        val override = InterfaceSize.override(base, InterfaceSize.AUTO)!!

        assertEquals("ar", override.locales[0].language)
        assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, override.layoutDirection)
    }
}
