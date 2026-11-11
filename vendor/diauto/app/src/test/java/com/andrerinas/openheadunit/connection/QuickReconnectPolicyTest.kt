package com.andrerinas.openheadunit.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// CarLink: when a dropped wireless session is re-offered on the held Bluetooth channel.
class QuickReconnectPolicyTest {
    private fun tries(
        enabled: Boolean = true, userExit: Boolean = false, usbOnly: Boolean = false,
        held: Boolean = true, credentials: Boolean = true,
    ) = QuickReconnectPolicy.shouldTry(enabled, userExit, usbOnly, held, credentials)

    @Test fun triesOnlyWhenEverythingAllowsIt() {
        assertTrue(tries())
        assertFalse("off unless the host turns it on", tries(enabled = false))
        assertFalse("a user exit ends the session", tries(userExit = true))
        assertFalse("never for a USB session", tries(usbOnly = true))
        assertFalse("the phone closed the channel, or it was never held", tries(held = false))
        assertFalse("the network went away", tries(credentials = false))
    }

    @Test fun aHeldChannelWaitsForTheDecisionOnlyWhenEnabledAndOnlyBriefly() {
        assertFalse("off: released at once, as before", QuickReconnectPolicy.waitsForDecision(false, 0))
        assertTrue(QuickReconnectPolicy.waitsForDecision(true, 0))
        assertTrue(QuickReconnectPolicy.waitsForDecision(true, QuickReconnectPolicy.DECISION_GRACE_MS - 1))
        assertFalse(QuickReconnectPolicy.waitsForDecision(true, QuickReconnectPolicy.DECISION_GRACE_MS))
        assertFalse("clock went back: not trusted", QuickReconnectPolicy.waitsForDecision(true, -1))
    }
}
