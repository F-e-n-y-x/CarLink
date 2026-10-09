package com.carlink.app.ui

import com.carlink.app.ui.theme.RenderingMode
import com.carlink.app.ui.theme.RenderingPolicy
import com.carlink.core.connection.DeviceSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When CarLink draws in lite rendering (ui/theme/RenderingMode.kt). */
class RenderingPolicyTest {
    private val tablet = DeviceSignals(totalRamMb = 7_600, lowRamDevice = false, cpuCores = 8,
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
    // Typical cheap head units.
    private val mt8163 = DeviceSignals(totalRamMb = 1_950, lowRamDevice = false, cpuCores = 4, supportedAbis = listOf("arm64-v8a", "armeabi-v7a"))
    private val t3 = DeviceSignals(totalRamMb = 990, lowRamDevice = true, cpuCores = 4, supportedAbis = listOf("armeabi-v7a", "armeabi"))
    private val uis7862With3Gb = DeviceSignals(totalRamMb = 2_870, lowRamDevice = false, cpuCores = 8,
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a"))
    private val uis7862With4Gb = uis7862With3Gb.copy(totalRamMb = 3_740)

    @Test fun automaticIsLiteOnLowEndHardwareOnly() {
        assertFalse(RenderingPolicy.lite(RenderingMode.AUTO, tablet))
        assertTrue(RenderingPolicy.lite(RenderingMode.AUTO, mt8163))
        assertTrue(RenderingPolicy.lite(RenderingMode.AUTO, t3))
        assertTrue(RenderingPolicy.lite(RenderingMode.AUTO, uis7862With3Gb))
        assertFalse(RenderingPolicy.lite(RenderingMode.AUTO, uis7862With4Gb))
    }

    @Test fun eachSignalAloneIsEnough() {
        assertTrue(RenderingPolicy.lite(RenderingMode.AUTO, tablet.copy(lowRamDevice = true)))
        assertTrue(RenderingPolicy.lite(RenderingMode.AUTO, tablet.copy(totalRamMb = 3_000)))
        assertTrue(RenderingPolicy.lite(RenderingMode.AUTO, tablet.copy(supportedAbis = listOf("armeabi-v7a"))))
        assertTrue(RenderingPolicy.lite(RenderingMode.AUTO, tablet.copy(cpuCores = 4)))
        // Unknown values are not signals.
        assertFalse(RenderingPolicy.lite(RenderingMode.AUTO, tablet.copy(totalRamMb = 0, cpuCores = 0, supportedAbis = emptyList())))
    }

    @Test fun theDriversChoiceWins() {
        assertTrue(RenderingPolicy.lite(RenderingMode.LITE, tablet))
        assertFalse(RenderingPolicy.lite(RenderingMode.FULL, t3))
    }

    @Test fun displayPrefsResolveAutomatic() {
        val auto = DisplayPrefs(com.carlink.app.ui.theme.ThemeMode.DARK, com.carlink.app.ui.theme.UiProfileMode.AUTO, 1f,
            RenderingMode.AUTO, autoLite = true)
        assertTrue(auto.liteRendering)
        assertFalse(auto.copy(autoLite = false).liteRendering)
        assertFalse(auto.copy(renderingMode = RenderingMode.FULL).liteRendering)
        assertEquals(true, auto.copy(renderingMode = RenderingMode.LITE, autoLite = false).liteRendering)
    }
}
