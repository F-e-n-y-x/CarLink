package com.carlink.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class SystemWifiTest {
    private fun softApConf(version: Int, ssid: String, keyType: Int, key: String?) = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).run {
            writeInt(version); writeUTF(ssid)
            if (version >= 2) { writeInt(1); writeInt(36) }
            if (version >= 3) writeBoolean(false)
            writeInt(keyType); key?.let(::writeUTF)
        }
    }.toByteArray()

    @Test fun softApConfAllVersions() {
        assertEquals(WifiDetails("Car AP", "secret12"), SystemWifi.parseSoftApConf(softApConf(3, "Car AP", 4, "secret12")))
        assertEquals(WifiDetails("Car AP", "secret12"), SystemWifi.parseSoftApConf(softApConf(2, "Car AP", 4, "secret12")))
        assertEquals(WifiDetails("Car AP", ""), SystemWifi.parseSoftApConf(softApConf(1, "Car AP", 0, null)))
        assertNull(SystemWifi.parseSoftApConf(softApConf(9, "Car AP", 4, "secret12")))
        assertNull(SystemWifi.parseSoftApConf(ByteArray(3)))
    }

    @Test fun softApXmlOldAndNewNames() {
        assertEquals(WifiDetails("Car <AP>", "p&ss\"word"), SystemWifi.parseSoftApXml(
            """<SoftAp><string name="SSID">Car &lt;AP&gt;</string><int name="Band" value="1" />""" +
                """<string name="Passphrase">p&amp;ss&quot;word</string></SoftAp>"""))
        assertEquals(WifiDetails("Car AP", "secret12"), SystemWifi.parseSoftApXml(
            """<string name="WifiSsid">&quot;Car AP&quot;</string><string name="Passphrase">secret12</string>"""))
        assertEquals(WifiDetails("Open", ""), SystemWifi.parseSoftApXml("""<string name="SSID">Open</string>"""))
        // A hex (non-UTF-8) WifiSsid cannot be typed on a phone: skipped.
        assertNull(SystemWifi.parseSoftApXml("""<string name="WifiSsid">4361722041</string>"""))
    }

    @Test fun savedPasswordOfTheNamedNetworkOnly() {
        val xml = """
            <NetworkList>
            <Network><WifiConfiguration>
            <string name="SSID">&quot;Neighbour&quot;</string>
            <string name="PreSharedKey">&quot;other-pass&quot;</string>
            </WifiConfiguration></Network>
            <Network><WifiConfiguration>
            <string name="SSID">&quot;Home&quot;</string>
            <string name="PreSharedKey">&quot;home&amp;pass&quot;</string>
            </WifiConfiguration></Network>
            <Network><WifiConfiguration>
            <string name="SSID">&quot;Raw&quot;</string>
            <string name="PreSharedKey">${"a".repeat(64)}</string>
            </WifiConfiguration></Network>
            </NetworkList>
        """.trimIndent()
        assertEquals("home&pass", SystemWifi.parseSavedPassword(xml, "Home"))
        assertNull(SystemWifi.parseSavedPassword(xml, "Raw"))
        assertNull(SystemWifi.parseSavedPassword(xml, "Missing"))
    }
}
