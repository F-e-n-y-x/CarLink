package com.carlink.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootModeTest {
    @Test fun grantsCoverEverySetupScreenAndEndSuccessfully() {
        val commands = RootMode.grantCommands("com.carlink.app", listOf("android.permission.RECORD_AUDIO"))
        assertTrue("appops set com.carlink.app ACTIVATE_VPN allow" in commands)
        assertTrue("appops set com.carlink.app SYSTEM_ALERT_WINDOW allow" in commands)
        assertTrue(commands.any { it.startsWith("dumpsys deviceidle whitelist +com.carlink.app") })
        assertTrue(commands.any { it.startsWith("pm grant com.carlink.app android.permission.RECORD_AUDIO") })
        assertEquals("true", commands.last())
    }

    @Test fun projectionTurnsLowLatencyOnAndBackOff() {
        assertTrue(RootMode.projectionCommands(true).any { it.startsWith("cmd wifi force-low-latency-mode enabled") })
        assertTrue(RootMode.projectionCommands(false).any { it.startsWith("cmd wifi force-low-latency-mode disabled") })
        assertEquals("true", RootMode.projectionCommands(false).last())
    }
}
