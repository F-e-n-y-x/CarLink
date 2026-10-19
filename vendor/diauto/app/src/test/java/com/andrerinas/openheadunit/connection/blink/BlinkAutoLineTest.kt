// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0), app/src/test/.../nativeaa/blink/BlinkAutoLineTest.kt
package com.andrerinas.openheadunit.connection.blink

import com.andrerinas.openheadunit.aap.WppFraming
import com.andrerinas.openheadunit.aap.WppMessageType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every line here is copied from a stock Carlink session on an FYT UIS7862 unit. */
class BlinkAutoLineTest {

    @Test
    fun `the channel-open line is recognised`() {
        assertTrue(BlinkAutoLine.parse("AA") is BlinkAutoLine.Event.ChannelOpened)
        assertTrue(BlinkAutoLine.parse("AA\r") is BlinkAutoLine.Event.ChannelOpened)
    }

    @Test
    fun `lines lowercased by the FYT tty are recognised`() {
        // Exact bytes captured from /dev/auto_serial with IUCLC enabled on DUDUOS.
        assertTrue(BlinkAutoLine.parse("aa") is BlinkAutoLine.Event.ChannelOpened)
        assertEquals(
            BlinkAutoLine.Event.PhoneLinked("14:0B:9E:97:77:4E"),
            BlinkAutoLine.parse("jh140b9e97774e")
        )
    }

    @Test
    fun `the channel-close line is recognised case-insensitively`() {
        assertTrue(BlinkAutoLine.parse("SS") is BlinkAutoLine.Event.ChannelClosed)
        assertTrue(BlinkAutoLine.parse("ss") is BlinkAutoLine.Event.ChannelClosed)
    }

    @Test
    fun `the hands-free line carries the phone address`() {
        assertEquals(
            BlinkAutoLine.Event.PhoneLinked("14:0B:9E:97:77:4E"),
            BlinkAutoLine.parse("JH140B9E97774E")
        )
    }

    @Test
    fun `a phone frame keeps its header`() {
        val event = BlinkAutoLine.parse("ZB000200060800") as BlinkAutoLine.Event.Frame
        assertEquals(2, WppFraming.decodePayloadSize(event.bytes))
        assertEquals(WppMessageType.CONNECT_STATUS, WppFraming.decodeType(event.bytes))
    }

    @Test
    fun `a frame is sent as uppercase hex behind AT#ZA`() {
        val versionRequest = BlinkAutoLine.decodeHex("0804100318012000")!!
        assertEquals(
            "AT#ZA000800040804100318012000\r\n",
            BlinkAutoLine.encode(WppFraming.encodeFrame(versionRequest, WppMessageType.VERSION_REQUEST))
        )
    }

    @Test
    fun `a ping is echoed byte for byte as the stock client does`() {
        val ping = (BlinkAutoLine.parse("ZB0007000808E3CD92C48D34") as BlinkAutoLine.Event.Frame).bytes
        assertEquals(
            "AT#ZA0009000908E3CD92C48D341200\r\n",
            BlinkAutoLine.encode(BlinkAutoLine.idleReply(ping)!!)
        )
    }

    @Test
    fun `an empty type-1 message is answered with already-started`() {
        val frame = (BlinkAutoLine.parse("ZB00000001") as BlinkAutoLine.Event.Frame).bytes
        assertEquals(
            "AT#ZA000B000718FCFFFFFFFFFFFFFFFF01\r\n",
            BlinkAutoLine.encode(BlinkAutoLine.idleReply(frame)!!)
        )
    }

    @Test
    fun `other traffic gets no idle reply`() {
        val frame = (BlinkAutoLine.parse("ZB000200060800") as BlinkAutoLine.Event.Frame).bytes
        assertNull(BlinkAutoLine.idleReply(frame))
    }

    @Test
    fun `junk is passed through as other`() {
        assertEquals(BlinkAutoLine.Event.Other("ZBxyz"), BlinkAutoLine.parse("ZBxyz"))
        assertEquals(BlinkAutoLine.Event.Other("IA"), BlinkAutoLine.parse("IA"))
        assertEquals(BlinkAutoLine.Event.Other("ZB"), BlinkAutoLine.parse("ZB"))
    }

    @Test
    fun `a record shorter than a header is still delivered so a split frame keeps its tail`() {
        val tail = BlinkAutoLine.parse("ZB0800") as BlinkAutoLine.Event.Frame
        assertEquals("0800", BlinkAutoLine.toHex(tail.bytes))
        assertNull(BlinkAutoLine.idleReply(tail.bytes))
    }

    @Test
    fun `long frames are split into 50-byte lines like the stock client`() {
        val frame = ByteArray(120) { it.toByte() }
        val lines = BlinkAutoLine.encode(frame).split("\r\n").filter { it.isNotEmpty() }
        assertEquals(listOf(100, 100, 40), lines.map { it.removePrefix("AT#ZA").length })
        assertEquals(BlinkAutoLine.toHex(frame), lines.joinToString("") { it.removePrefix("AT#ZA") })
    }

    @Test
    fun `an exact multiple of the chunk size sends no trailing empty line`() {
        val encoded = BlinkAutoLine.encode(ByteArray(100))
        assertEquals(2, encoded.split("\r\n").count { it.isNotEmpty() })
        assertEquals(false, encoded.contains("AT#ZA\r\n"))
    }
}
