package com.carlink.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class EnvironmentChecksTest {
    @Test fun aClockIn1970Warns() {
        assertEquals(CheckStatus.WARN, Checks.clock(0L).status)
        assertEquals(CheckStatus.PASS, Checks.clock(1_793_734_323_164L).status)
    }

    @Test fun lowStorageWarnsWithTheStorageScreen() {
        assertEquals(CheckStatus.WARN, Checks.storage(50L * 1024 * 1024).status)
        assertEquals(CheckFix.OpenStorageSettings, Checks.storage(1).fix)
        assertEquals(CheckStatus.PASS, Checks.storage(2L * 1024 * 1024 * 1024).status)
    }
}
