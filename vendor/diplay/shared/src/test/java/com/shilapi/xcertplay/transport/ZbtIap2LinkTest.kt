// CarLink: new test for ZbtIap2Link (see docs/VENDOR_CHANGES.md).
package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
