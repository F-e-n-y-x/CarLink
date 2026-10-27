package com.andrerinas.openheadunit.connection

// CarLink: ported from open-headunit cc195e12 with the 2192c2e0/7e126682 rules (an incomplete
// replacement, or one at another endpoint, is never sent).

/**
 * Whether the credentials a handshake captured before Type 1 are still the ones to send in Type 3.
 * A group torn down or replaced in between leaves the phone hunting a network that is gone.
 */
object CredentialFreshnessPolicy {

    data class Network(val ssid: String, val psk: String, val ip: String, val bssid: String)

    enum class Action { SEND_AS_CAPTURED, SEND_LIVE, ABORT }

    fun decide(captured: Network, live: Network?): Action = when {
        live == null -> Action.ABORT
        live.ssid.isBlank() || live.psk.isEmpty() -> Action.ABORT
        live == captured -> Action.SEND_AS_CAPTURED
        // Type 1 already told the phone where to dial; a new endpoint makes the exchange stale.
        live.ip != captured.ip -> Action.ABORT
        else -> Action.SEND_LIVE
    }
}
