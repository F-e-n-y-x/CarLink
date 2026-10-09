package com.andrerinas.openheadunit.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// CarLink: ported with upstream's EarlyWakePolicy (open-headunit PR #958).
class PokeLoopPolicyTest {
    private val empty = Triple("", "", "")
    private val creds = Triple("DIRECT-ab-CarLink", "192.168.49.1", "aa:bb:cc:dd:ee:ff")
    private val other = Triple("DIRECT-cd-CarLink", "192.168.49.1", "aa:bb:cc:dd:ee:00")

    @Test fun noRunningLoopAlwaysStarts() {
        assertTrue(PokeLoopPolicy.shouldRestartLoop(false, creds, creds))
        assertTrue(PokeLoopPolicy.shouldRestartLoop(false, null, empty))
    }

    @Test fun credentialsArrivingUnderALoopThatStartedWithoutThemKeepIt() {
        assertFalse(PokeLoopPolicy.shouldRestartLoop(true, empty, creds))
        assertFalse(PokeLoopPolicy.shouldRestartLoop(true, creds, creds))
    }

    @Test fun aNewGroupOrAManualPokeHandsTheSlotToANewLoop() {
        assertTrue(PokeLoopPolicy.shouldRestartLoop(true, creds, other))
        // manualPoke clears the key, so the delivery that follows starts the retry loop.
        assertTrue(PokeLoopPolicy.shouldRestartLoop(true, null, creds))
    }
}
