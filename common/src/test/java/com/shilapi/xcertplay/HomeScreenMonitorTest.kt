package com.shilapi.xcertplay

import android.content.Context
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HomeScreenMonitorTest {
    private lateinit var context: Context
    private val reports = mutableListOf<Boolean>()
    private lateinit var monitor: HomeScreenMonitor

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        reports.clear()
        monitor = HomeScreenMonitor(context) { reports.add(it) }
    }

    private fun feedPackage(pkg: String) {
        val method = monitor.javaClass.getDeclaredMethod("handleForegroundPackage", String::class.java)
            .apply { isAccessible = true }
        monitor.javaClass.getDeclaredField("active").apply { isAccessible = true }.set(monitor, true)
        method.invoke(monitor, pkg)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun launcherPackagesReportVisibleTrue() {
        feedPackage("com.android.launcher3")
        assertEquals(listOf(true), reports)

        feedPackage("com.smg.dydesktop")
        // No duplicate report since it's already true
        assertEquals(listOf(true), reports)

        feedPackage("com.dudu.android.launcher")
        assertEquals(listOf(true), reports)
    }

    @Test fun thirdPartyNonLauncherAppsReportVisibleFalse() {
        feedPackage("com.android.launcher3")
        assertEquals(listOf(true), reports)

        // User opens NetEase Cloud Music
        feedPackage("com.netease.cloudmusic.car")
        assertEquals(listOf(true, false), reports)

        // User returns to DiYou Desktop
        feedPackage("com.smg.dydesktop")
        assertEquals(listOf(true, false, true), reports)
    }

    @Test fun diPlayOwnPackagesAreIgnoredToPreventFlickeringLoop() {
        // Start on desktop
        feedPackage("com.smg.dydesktop")
        assertEquals(listOf(true), reports)

        // DiPlay shows overlay or dialog: events with DiPlay package should be completely ignored
        feedPackage(context.packageName)
        feedPackage("com.shilapi.xcertplay")
        feedPackage("com.shilapi.xcertplay.mobile")

        // State remains true, no false hide event triggered!
        assertEquals(listOf(true), reports)
    }
}
