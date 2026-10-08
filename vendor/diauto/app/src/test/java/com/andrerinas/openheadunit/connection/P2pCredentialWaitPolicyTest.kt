package com.andrerinas.openheadunit.connection

import org.junit.Assert.*
import org.junit.Test

class P2pCredentialWaitPolicyTest {
    @Test fun plausibleFallbackWaitsForLateCurrentInterface() {
        val attempts = listOf(false, false, true)
        val deliveredAt = attempts.indices.first { pass ->
            P2pCredentialWaitPolicy.ready(true, true, false, attempts[pass], false, pass * 1000L)
        }
        assertEquals(2, deliveredAt)
    }

    @Test fun fallbackHasABoundedGracePeriod() {
        assertFalse(P2pCredentialWaitPolicy.ready(true, true, false, false, false, 2999))
        assertTrue(P2pCredentialWaitPolicy.ready(true, true, false, false, false, 3000))
        assertFalse(P2pCredentialWaitPolicy.ready(true, true, false, false, true, 3000))
    }

    @Test fun explicitOverridesAndClientGroupsDoNotWaitForInterfaceRecovery() {
        assertTrue(P2pCredentialWaitPolicy.ready(true, true, true, false, false, 0))
        assertTrue(P2pCredentialWaitPolicy.ready(true, false, false, false, false, 0))
        assertFalse(P2pCredentialWaitPolicy.ready(false, true, true, false, false, 0))
    }
}
