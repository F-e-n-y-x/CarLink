package com.carlink.core.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDetailsTest {
    @Test fun wifiDetailsValidation() {
        assertTrue(WifiDetails("Net", "").isValid()) // open network
        assertTrue(WifiDetails("Net", "12345678").isValid())
        assertFalse(WifiDetails("", "12345678").isValid())
        assertFalse(WifiDetails("Net", "short").isValid())
        assertFalse(WifiDetails("N".repeat(33), "12345678").isValid())
    }

    @Test fun ssidLimitIsInUtf8Bytes() {
        assertTrue(WifiDetails("N".repeat(32), "").isValid())
        // 11 three-byte characters = 33 bytes.
        assertFalse(WifiDetails("車".repeat(11), "").isValid())
        assertTrue(WifiDetails("x".repeat(1), "p".repeat(63)).isValid())
        assertFalse(WifiDetails("x", "p".repeat(64)).isValid())
        assertFalse(WifiDetails("x", "p".repeat(7)).isValid())
    }
}
