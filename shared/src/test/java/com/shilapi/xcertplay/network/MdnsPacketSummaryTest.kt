package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class MdnsPacketSummaryTest {
    private fun name(value: String): ByteArray = value.split('.').fold(ByteArray(0)) { bytes, label ->
        bytes + byteArrayOf(label.length.toByte()) + label.toByteArray()
    } + byteArrayOf(0)

    @Test fun queriesExposeOnlyRecognizedServiceTypes() {
        val query = byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0) +
            name("Alice._carplay-ctrl._tcp.local") + byteArrayOf(0, 12, 0, 1)
        assertEquals(MdnsPacketSummary(false, false, true), MdnsPacketSummary.parse(query, query.size))
        assertFalse(MdnsPacketSummary.parse(query, query.size).toString().contains("Alice"))
    }

    @Test fun compressedAnswerOwnerIsRecognizedWithoutExportingTxt() {
        val packet = byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 1, 0, 1, 0, 0, 0, 0) +
            name("Alice._airplay._tcp.local") + byteArrayOf(0, 16, 0, 1) +
            byteArrayOf(0xc0.toByte(), 12, 0, 16, 0, 1, 0, 0, 0, 120, 0, 7) + "secret!".toByteArray()
        assertEquals(MdnsPacketSummary(true, true, false), MdnsPacketSummary.parse(packet, packet.size))
        assertFalse(MdnsPacketSummary.parse(packet, packet.size).toString().contains("secret"))
    }

    @Test fun shortPacketsTruncatedRecordsAndCompressionLoopsAreRejected() {
        assertNull(MdnsPacketSummary.parse(ByteArray(5), 5))
        val loop = byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0xc0.toByte(), 12, 0, 12, 0, 1)
        assertNull(MdnsPacketSummary.parse(loop, loop.size))
        val truncated = byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0) +
            name("_airplay._tcp.local") + byteArrayOf(0, 16, 0, 1, 0, 0, 0, 120, 0, 7)
        assertNull(MdnsPacketSummary.parse(truncated, truncated.size))
    }
}
