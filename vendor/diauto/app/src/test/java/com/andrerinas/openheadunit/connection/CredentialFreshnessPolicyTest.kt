package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.connection.CredentialFreshnessPolicy.Action
import com.andrerinas.openheadunit.connection.CredentialFreshnessPolicy.Network
import org.junit.Assert.assertEquals
import org.junit.Test

// CarLink: open-headunit cc195e12 / 2192c2e0 / 7e126682.
class CredentialFreshnessPolicyTest {
    private val captured = Network("DIRECT-ab-Car", "secret12", "192.168.49.1", "AA:BB:CC:DD:EE:FF")

    @Test fun decides() {
        assertEquals(Action.SEND_AS_CAPTURED, CredentialFreshnessPolicy.decide(captured, captured.copy()))
        assertEquals(Action.SEND_LIVE, CredentialFreshnessPolicy.decide(captured, captured.copy(bssid = "AA:BB:CC:DD:EE:00")))
        assertEquals(Action.ABORT, CredentialFreshnessPolicy.decide(captured, null))
        assertEquals(Action.ABORT, CredentialFreshnessPolicy.decide(captured, captured.copy(psk = "")))
        assertEquals(Action.ABORT, CredentialFreshnessPolicy.decide(captured, captured.copy(ssid = " ")))
        assertEquals(Action.ABORT, CredentialFreshnessPolicy.decide(captured, captured.copy(ip = "192.168.49.2")))
    }
}
