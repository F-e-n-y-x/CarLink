// CarLink: new test for ZbtIap2Link (see docs/VENDOR_CHANGES.md).
package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class ZbtIap2LinkTest {
    @Test fun headerIsBigEndianMagicVersionIdLength() {
        assertArrayEquals(
            byteArrayOf(0, 0, -1, -1, 0, 0, 1, 1, 0, 0, 1, 5, 0, 0, 3, -64),
            ZbtIap2Link.header(ZbtIap2Link.RFCOMM_DATA, 960),
        )
    }

    @Test fun linkInfo2RoundTripsThroughTheScalarParser() {
        val name = "iPhone".toByteArray()
        val body = ZbtIap2Link.varintField(1, ZbtIap2Link.LINK_INFO2) + ZbtIap2Link.varintField(3, 1) +
            byteArrayOf((8 shl 3 or 2).toByte(), name.size.toByte()) + name
        val fields = ZbtIap2Link.parse(body)!!
        assertEquals(ZbtIap2Link.LINK_INFO2.toLong(), fields.varints[1])
        assertEquals(1L, fields.varints[3])
        assertEquals("iPhone", fields.strings[8])
        // A length running past the end is refused rather than guessed.
        assertNull(ZbtIap2Link.parse(byteArrayOf((8 shl 3 or 2).toByte(), 9, 1)))
    }

    @Test fun moduleAddressGetsColons() {
        assertEquals("E8:48:B8:C8:20:00", ZbtIap2Link.withColons("e848b8c82000"))
        assertEquals("E8:48:B8:C8:20:00", ZbtIap2Link.withColons("E8:48:B8:C8:20:00"))
    }

    @Test fun aSilentPhoneIsAskedAgainAFewTimes() {
        // 2026-11-04: one RequestReconn per attempt; a phone that ignored it waited for a whole retry.
        val reconnects = AtomicInteger()
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val daemon = Thread {
                server.accept().use { client ->
                    val input = DataInputStream(client.getInputStream())
                    val header = ByteArray(ZbtIap2Link.HEADER_SIZE)
                    runCatching {
                        while (true) {
                            input.readFully(header)
                            val id = java.nio.ByteBuffer.wrap(header, 8, 4).int
                            input.readFully(ByteArray(java.nio.ByteBuffer.wrap(header, 12, 4).int))
                            if (id == ZbtIap2Link.REQUEST_INIT) {
                                val body = ZbtIap2Link.varintField(1, ZbtIap2Link.INIT_INFO)
                                client.getOutputStream().write(ZbtIap2Link.header(ZbtIap2Link.INIT_INFO, body.size) + body)
                            }
                            if (id == ZbtIap2Link.REQUEST_RECONN) reconnects.incrementAndGet()
                        }
                    }
                }
            }.apply { start() }
            ZbtIap2Link.open("127.0.0.1", server.localPort).use { link ->
                assertFalse(link.awaitPhone(timeoutMillis = 1_000, rewakeMillis = 100))
            }
            daemon.join(2_000)
        }
        assertEquals(1 + ZbtIap2Link.MAX_REWAKES, reconnects.get())
    }
}
