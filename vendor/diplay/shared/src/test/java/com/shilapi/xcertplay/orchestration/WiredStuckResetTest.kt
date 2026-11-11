package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// CarLink: new test (see docs/VENDOR_CHANGES.md).
class WiredStuckResetTest {
    private val second = 1_000_000_000L

    private fun check(now: Long, active: Boolean = false, tcp: Boolean = false, pairing: Boolean = false) =
        WiredStuckReset.shouldReset(now, active, tcp, pairing)

    @Before fun forget() = WiredStuckReset.sessionReached()

    @Test fun aSilentStartIsResetOnce() {
        assertTrue(check(10 * second))
        assertFalse(check(25 * second))
        assertFalse(check(129 * second))
        assertTrue(check(131 * second))
    }

    @Test fun anIphoneOnItsWayOrAskingItsOwnerIsLeftAlone() {
        assertFalse(check(10 * second, active = true))
        assertFalse(check(10 * second, tcp = true))
        assertFalse(check(10 * second, pairing = true))
        // None of those used up the one reset.
        assertTrue(check(11 * second))
    }

    @Test fun aSessionAllowsTheNextReset() {
        assertTrue(check(10 * second))
        WiredStuckReset.sessionReached()
        assertTrue(check(20 * second))
    }
}
