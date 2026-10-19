package com.carlink.core.detect

import com.carlink.core.core.PhoneKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingLossTest {
    @Test fun onlyAKnownPhoneOnAndroid16OrLaterCounts() {
        assertTrue(PairingLoss.applies(36, PhoneKind.IPHONE))
        assertTrue(PairingLoss.applies(37, PhoneKind.ANDROID))
        assertFalse(PairingLoss.applies(35, PhoneKind.IPHONE))
        assertFalse(PairingLoss.applies(36, null))
        assertFalse(PairingLoss.applies(36, PhoneKind.UNKNOWN))
    }
}
