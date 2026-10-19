package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.utils.Settings.ConnectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessScopePolicyTest {

    @Test
    fun `usb only turns every stored wireless mode off`() {
        // Native AA (3) is the stored default, so this is the case a USB-only user hits first.
        for (stored in 0..3) {
            assertEquals(0, WirelessScopePolicy.effectiveMode(stored, setOf(ConnectionMode.USB)))
        }
    }

    @Test
    fun `self only does not start wireless either`() {
        assertFalse(WirelessScopePolicy.allowsWireless(setOf(ConnectionMode.SELF)))
        assertEquals(0, WirelessScopePolicy.effectiveMode(3, setOf(ConnectionMode.SELF)))
    }

    @Test
    fun `choosing wifi keeps the stored mode`() {
        for (stored in 0..3) {
            assertEquals(stored, WirelessScopePolicy.effectiveMode(stored, setOf(ConnectionMode.WIFI)))
            assertEquals(
                stored,
                WirelessScopePolicy.effectiveMode(stored, setOf(ConnectionMode.USB, ConnectionMode.WIFI))
            )
        }
    }

    @Test
    fun `nothing chosen yet behaves as before`() {
        // An empty selection shows every setting, so it must not silently disable wireless.
        assertTrue(WirelessScopePolicy.allowsWireless(emptySet()))
        assertEquals(3, WirelessScopePolicy.effectiveMode(3, emptySet()))
    }
}
