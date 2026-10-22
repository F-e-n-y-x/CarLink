package com.andrerinas.openheadunit.aap

// CarLink: ported from open-headunit 67bcae27 and fbe128d0.

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HandshakeFrameReaderTest {
    private val version = byteArrayOf(0, 3, 0, 8, 0, 2, 0, 1, 0, 7, 0, 0)
    private val tls = byteArrayOf(0, 3, 0, 5, 0, 3, 22, 3, 3)

    @Test fun `stale encrypted envelopes survive every split and leave TLS untouched`() {
        for (flags in 0x08..0x0b) for (length in listOf(0, 1, 8)) {
            // The total is deliberately enormous: only this envelope's u16 length is skipped.
            val extra = if (flags == 9) byteArrayOf(-1, -1, -1, -1) else byteArrayOf()
            val stale = byteArrayOf(5, flags.toByte(), 0, length.toByte()) + extra + ByteArray(length)
            for (split in 1 until stale.size) {
                val stream = stale + version + tls
                var position = 0
                var now = 0L
                var pause = true
                val reader = HandshakeFrameReader({ now }, skipEncryptedFrames = true) { out, size, timeout ->
                    if (position == split && pause) { pause = false; now += timeout; 0 } else {
                        val count = minOf(size, if (position < split) split - position else stream.size - position)
                        stream.copyInto(out, 0, position, position + count)
                        position += count
                        count
                    }
                }
                assertNull(reader.readFrame(100))
                assertArrayEquals(version, reader.readFrame(200))
                assertEquals(stale.size + version.size, position)
                assertArrayEquals(tls, reader.readFrame(200))
            }
        }
    }

    @Test fun `skipping is bounded by envelope length and the original deadline`() {
        val stale = byteArrayOf(5, 9, -1, -1) + ByteArray(4) { -1 } + ByteArray(65535)
        val stream = stale + version
        var position = 0
        val reader = HandshakeFrameReader({ 0L }, skipEncryptedFrames = true) { out, size, _ ->
            assertTrue(size <= 65539)
            stream.copyInto(out, 0, position, position + size)
            position += size
            size
        }
        assertArrayEquals(version, reader.readFrame(100))
        assertEquals(stream.size, position)

        var now = 0L
        var reads = 0
        val endless = HandshakeFrameReader({ now }, skipEncryptedFrames = true) { out, size, _ ->
            assertEquals(4, size)
            byteArrayOf(5, 8, 0, 0).copyInto(out)
            now++
            reads++
            4
        }
        assertNull(endless.readFrame(10))
        assertEquals(10, reads)
    }

    @Test fun `version skipping still rejects unknown flags and malformed cleartext`() {
        for (flags in listOf(1, 2, 4, 7, 12, 255, 3)) {
            val reader = HandshakeFrameReader({ 0L }, skipEncryptedFrames = true) { out, _, _ ->
                byteArrayOf(0, flags.toByte(), 0, 1).copyInto(out)
                4
            }
            assertThrows(IOException::class.java) { reader.readFrame(100) }
        }
    }

    @Test fun `every split boundary survives a timeout and leaves the next phase untouched`() {
        for (split in 1 until version.size) {
            val stream = version + tls
            var position = 0
            var pause = true
            var now = 0L
            val reader = HandshakeFrameReader({ now }) { out, length, timeout ->
                if (position == split && pause) { pause = false; now += timeout; 0 }
                else {
                    val count = minOf(length, if (position < split) split - position else stream.size - position)
                    stream.copyInto(out, 0, position, position + count)
                    position += count
                    count
                }
            }
            assertNull("split=$split", reader.readFrame(10))
            assertArrayEquals(version, reader.readFrame(20))
            assertEquals(version.size, position)
            assertArrayEquals(tls, reader.readFrame(30))
        }
    }

    @Test fun `coalesced unrelated response and TLS remain separate frames`() {
        val unrelated = byteArrayOf(0, 3, 0, 2, 0, 11)
        val stream = unrelated + version + tls
        var position = 0
        val reader = HandshakeFrameReader({ 0L }) { out, length, _ ->
            val count = minOf(length, stream.size - position)
            stream.copyInto(out, 0, position, position + count)
            position += count
            count
        }
        assertArrayEquals(unrelated, reader.readFrame(1))
        assertArrayEquals(version, reader.readFrame(1))
        assertEquals(unrelated.size + version.size, position)
        assertArrayEquals(tls, reader.readFrame(1))
    }

    @Test fun `one byte reads share a deadline rather than renewing it`() {
        var now = 0L
        var position = 0
        val reader = HandshakeFrameReader({ now }) { out, _, timeout ->
            assertEquals((5 - now).toInt(), timeout)
            out[0] = version[position++]
            now++
            1
        }
        assertNull(reader.readFrame(5))
        assertEquals(5, position)
        assertNull(reader.readFrame(5))
        assertEquals(5, position)
    }

    @Test fun `invalid flags or lengths stop before reading a body`() {
        for (header in listOf(byteArrayOf(0, 3, 0, 0), byteArrayOf(0, 3, 0, 1),
                byteArrayOf(0, 1, 0, 8), byteArrayOf(0, 11, 0, 8))) {
            var reads = 0
            val reader = HandshakeFrameReader({ 0L }) { out, length, _ ->
                assertEquals(4, length)
                reads++
                header.copyInto(out)
                4
            }
            assertThrows(IOException::class.java) { reader.readFrame(1) }
            assertEquals(1, reads)
        }
    }

    @Test fun `USB zero length packet does not end the receive window`() {
        var reads = 0
        var position = 0
        val reader = HandshakeFrameReader({ 0L }) { out, length, _ ->
            if (reads++ == 0) 0 else {
                version.copyInto(out, 0, position, position + length)
                position += length
                length
            }
        }
        assertArrayEquals(version, reader.readFrame(1000))
        assertEquals(3, reads)
    }

    @Test fun `a receive count beyond the requested header or body is rejected`() {
        for (invalidRead in 1..2) {
            var reads = 0
            val reader = HandshakeFrameReader({ 0L }) { out, length, _ ->
                reads++
                if (reads == invalidRead) length + 1 else {
                    // Supply the valid header before exercising the body count guard.
                    version.copyInto(out, 0, 0, length)
                    length
                }
            }
            val failure = assertThrows(IOException::class.java) { reader.readFrame(1) }
            assertEquals("Handshake read exceeded requested length", failure.message)
            assertEquals(invalidRead, reads)
        }
    }

    @Test fun `USB does not start a bulk transfer with less than its minimum budget`() {
        var now = 0L
        var reads = 0
        val reader = HandshakeFrameReader({ now }, 100) { out, length, timeout ->
            assertTrue(timeout >= 100)
            version.copyInto(out, 0, 0, length)
            now = 950L
            reads++
            length
        }
        assertNull(reader.readFrame(1000))
        assertEquals(1, reads)
    }

    @Test fun `negative read preserves partial frame for the next window`() {
        var position = 0
        var error = true
        val reader = HandshakeFrameReader({ 0L }) { out, length, _ ->
            if (position == 4 && error) { error = false; -1 } else {
                version.copyInto(out, 0, position, position + length)
                position += length
                length
            }
        }
        assertNull(reader.readFrame(1000))
        assertArrayEquals(version, reader.readFrame(2000))
    }

    @Test fun `maximum unsigned wire length is bounded and accepted`() {
        val frame = byteArrayOf(0, 3, -1, -1) + ByteArray(65535)
        var position = 0
        val reader = HandshakeFrameReader({ 0L }) { out, length, _ ->
            frame.copyInto(out, 0, position, position + length)
            position += length
            length
        }
        assertArrayEquals(frame, reader.readFrame(1))
        assertEquals(65539, position)
    }
}
