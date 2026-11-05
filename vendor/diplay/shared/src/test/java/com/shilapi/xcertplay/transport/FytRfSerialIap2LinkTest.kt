// CarLink: new file (see docs/VENDOR_CHANGES.md).
package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FytRfSerialIap2LinkTest {
    private val moduleSays = PipedOutputStream()
    private val sent = ByteArrayOutputStream()
    private val link = FytRfSerialIap2Link(PipedInputStream(moduleSays, 65_536), sent)

    private fun lines() = synchronized(sent) { sent.toString(Charsets.US_ASCII.name()) }.split("\r\n").filter { it.isNotEmpty() }
    private fun module(vararg lines: String) = lines.forEach { link.handle(it) }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what()) {
            check(System.currentTimeMillis() < until) { "timed out; sent ${lines()}" }
            Thread.sleep(10)
        }
    }

    @Test fun opensTheIphonesIap2ChannelAndCarriesBytesAsHex() {
        link.start()
        assertEquals(listOf("AT#VE", "AT#QN", "AT#QI"), lines())
        module("DBa1b2c3d4e5f6", "IB", "JH0C1D2E3F4A5B", "SAWife's iPhone",
            "US", "UU0000110100001000800000805F9B34FB", "UU00000000DECAFADEDECADEAFDECACAFE", "UE")
        assertEquals("A1:B2:C3:D4:E5:F6", link.moduleMacWithColons())
        assertEquals("0C:1D:2E:3F:4A:5B" to "Wife's iPhone", ModuleIap2Route.lastPhone)

        val pool = Executors.newSingleThreadExecutor()
        val up = pool.submit<Boolean> { link.awaitPhone(5_000) }
        waitFor { "AT#SP0C1D2E3F4A5B00000000DECAFADEDECADEAFDECACAFE" in lines() }
        module("SV")
        assertTrue(up.get(5, TimeUnit.SECONDS))
        pool.shutdown()

        val payload = ByteArray(250) { it.toByte() }
        link.access.outputStream()!!.write(payload)
        val sg = lines().filter { it.startsWith("AT#SG") }
        assertEquals(listOf(200, 200, 100), sg.map { it.length - 5 }) // 100 + 100 + 50 bytes, two hex digits each
        assertEquals("AT#SG" + FytRfSerialIap2Link.hex(payload, 0, 100), sg[0])

        module("SIff5A0102", "SIzz")
        val got = ByteArray(4)
        assertEquals(4, link.access.inputStream()!!.read(got))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x5A, 1, 2), got)

        module("SS") // the channel dropped: the stream ends
        assertEquals(-1, link.access.inputStream()!!.read(got))
    }

    @Test fun waitsForThePhoneAndAsksTheModuleAgain() {
        var now = 0L
        val timed = FytRfSerialIap2Link(PipedInputStream(PipedOutputStream()), sent, clock = { now })
        val pool = Executors.newSingleThreadExecutor()
        val up = pool.submit<Boolean> { timed.awaitPhone(30_000, rewakeMillis = 6_000) }
        Thread.sleep(50)
        now = 6_000
        waitFor { lines().count { it == "AT#QI" } == 1 }
        timed.handle("IB") // phone up, but no service list yet and no address: still no dial
        now = 7_000
        timed.handle("JH0C1D2E3F4A5B")
        Thread.sleep(100)
        assertFalse(lines().any { it.startsWith("AT#SP") })
        now = 7_000 + FytRfSerialIap2Link.SERVICES_GRACE_MILLIS // its services went by before we listened
        timed.handle("SA")
        waitFor { lines().any { it.startsWith("AT#SP0C1D2E3F4A5B") } }
        now = 40_000
        timed.handle("NAcar")
        assertFalse(up.get(5, TimeUnit.SECONDS))
        pool.shutdown()
    }

    @Test fun hexRoundTripsAndRejectsBrokenLines() {
        val bytes = byteArrayOf(0, 0x7F, 0x80.toByte(), 0xFF.toByte())
        assertEquals("007F80FF", FytRfSerialIap2Link.hex(bytes, 0, 4))
        assertArrayEquals(bytes, FytRfSerialIap2Link.unhex("007f80ff"))
        assertNull(FytRfSerialIap2Link.unhex("ABC"))
        assertNull(FytRfSerialIap2Link.unhex("GG"))
    }
}
