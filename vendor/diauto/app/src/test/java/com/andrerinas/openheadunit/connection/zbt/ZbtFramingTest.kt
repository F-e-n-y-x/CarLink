// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0), app/src/test/.../nativeaa/zbt/ZbtFramingTest.kt
package com.andrerinas.openheadunit.connection.zbt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZbtFramingTest {

    @Test
    fun `header bytes are pinned, big-endian, for a hand-worked example`() {
        // Magic 0x0000FFFF, version 0x00000101, message 0x101 (RequestInit), 4-byte body.
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(),
                0x00, 0x00, 0x01, 0x01,
                0x00, 0x00, 0x01, 0x01,
                0x00, 0x00, 0x00, 0x04
            ),
            ZbtFraming.encodeHeader(bodySize = 4, msgId = 0x101)
        )
    }

    @Test
    fun `the frame is the header followed by the body unchanged`() {
        val body = byteArrayOf(0x08, 0x01, 0x10, 0x02)
        assertArrayEquals(ZbtFraming.encodeHeader(4, 0x105) + body, ZbtFraming.encodeFrame(body, 0x105))
    }

    @Test
    fun `an empty body is legal and declares length zero`() {
        // The reference sender omits the body write entirely for a null body, so a header-only
        // frame is something we both send and must be able to read.
        val header = ZbtFraming.encodeFrame(ByteArray(0), 0x106)
        assertEquals(ZbtFraming.HEADER_SIZE, header.size)
        assertEquals(0, ZbtFraming.decodeBodySize(header))
    }

    @Test
    fun `sizes round-trip through the header, including ones that use the high bytes`() {
        // 255/256 and 65535/65536 straddle byte boundaries a naive encoder gets wrong; 990 is the
        // frame size the peer negotiates elsewhere, so it is a plausible real body.
        for (size in listOf(0, 1, 255, 256, 990, 65535, 65536, 1 shl 20)) {
            val header = ZbtFraming.encodeHeader(size, 0x111)
            assertEquals("size $size", size, ZbtFraming.decodeBodySize(header))
            assertEquals("size $size", 0x111, ZbtFraming.decodeMsgId(header))
        }
    }

    @Test
    fun `message ids round-trip, including every id seen on the wire`() {
        for (id in listOf(0x101, 0x103, 0x105, 0x106, 0x107, 0x10e, 0x111, 0x114, 0x11f, 0x151, 0x201, 0x305)) {
            assertEquals(id, ZbtFraming.decodeMsgId(ZbtFraming.encodeHeader(0, id)))
        }
    }

    @Test
    fun `every header we build carries the magic and version the peer expects`() {
        val header = ZbtFraming.encodeHeader(7, 0x105)
        assertTrue(ZbtFraming.hasValidMagic(header))
        assertEquals(ZbtFraming.MAGIC, ZbtFraming.decodeMagic(header))
        assertEquals(ZbtFraming.VERSION, ZbtFraming.decodeVersion(header))
    }

    @Test
    fun `a header with the wrong magic is unusable`() {
        // The magic is the only field the peer validates, so it is the only one that can tell us
        // we have lost sync with the stream rather than merely misread one frame.
        val header = ZbtFraming.encodeHeader(4, 0x101)
        header[3] = 0x00
        assertFalse(ZbtFraming.hasValidMagic(header))
        assertEquals(-1, ZbtFraming.decodeBodySize(header))
    }

    @Test
    fun `a negative declared length is refused rather than returned`() {
        // A length with the top bit set reads as negative and the peer rejects it. Returning -1
        // keeps the caller from allocating against an attacker-controlled or corrupt size.
        val header = ZbtFraming.encodeHeader(0, 0x101)
        header[12] = 0xFF.toByte()
        assertEquals(-1, ZbtFraming.decodeBodySize(header))
    }

    @Test
    fun `building a frame with a negative body size fails loudly`() {
        try {
            ZbtFraming.encodeHeader(bodySize = -1, msgId = 0x101)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("-1"))
        }
    }

    @Test
    fun `decoding a short header fails loudly rather than reading past the end`() {
        // Guards the caller that trusted one read to deliver the whole header. The header and body
        // are separate writes on the wire, so a short read is ordinary, not exceptional.
        try {
            ZbtFraming.decodeMsgId(ByteArray(ZbtFraming.HEADER_SIZE - 1))
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("${ZbtFraming.HEADER_SIZE}"))
        }
    }
}
