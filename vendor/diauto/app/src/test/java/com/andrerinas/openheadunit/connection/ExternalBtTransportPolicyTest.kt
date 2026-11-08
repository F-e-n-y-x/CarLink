// CarLink: route selection for the external Bluetooth module routes; cases adapted from
// open-headunit 1c1b5b72 (v.3.5.0) ExternalBtTransportPolicyTest to this policy's inputs.
package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.connection.ExternalBtTransportPolicy.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalBtTransportPolicyTest {
    private fun route(evidence: String?, host: Route?, overridden: Boolean = false) =
        ExternalBtTransportPolicy.route(evidence, host, overridden)

    @Test fun `ordinary hardware with no host route keeps its own radio`() =
        assertEquals(Route.NORMAL, route(null, null))

    @Test fun `external evidence with no host route is refused, as before the port`() =
        assertEquals(Route.BLOCKED, route("/dev/rf_serial exists", null))

    @Test fun `a manual secondary Bluetooth service still overrides the refusal`() =
        assertEquals(Route.NORMAL, route("/dev/rf_serial exists", null, overridden = true))

    @Test fun `the host's ZBT opt-in takes the module route`() =
        assertEquals(Route.ZBT, route("rw.zlink.bt.type=extra", Route.ZBT))

    @Test fun `the host's opt-in wins even where the engine sees no ZLink marker`() {
        // An FYT unit carries none of the markers ExternalBtPolicy knows.
        assertEquals(Route.BLINK, route(null, Route.BLINK))
        assertEquals(Route.ZBT, route(null, Route.ZBT))
        assertEquals(Route.ZJ_SOCKET, route(null, Route.ZJ_SOCKET))
    }

    @Test fun `the host's ZJ socket opt-in takes the socket route`() =
        assertEquals(Route.ZJ_SOCKET, route("/dev/zj_bt_serial exists", Route.ZJ_SOCKET))

    @Test fun `the module route wins over the manual override`() =
        assertEquals(Route.BLINK, route("/dev/rf_serial exists", Route.BLINK, overridden = true))

    @Test fun `the host's NORMAL overrides the engine's markers`() =
        // CarLink judged rw.zlink.bt.type=extra a false positive (Qualcomm Bengal head unit, 2026-11-08).
        assertEquals(Route.NORMAL, route("rw.zlink.bt.type=extra", Route.NORMAL))

    @Test fun `a host BLOCKED is ignored`() =
        assertEquals(Route.NORMAL, route(null, Route.BLOCKED))
}
