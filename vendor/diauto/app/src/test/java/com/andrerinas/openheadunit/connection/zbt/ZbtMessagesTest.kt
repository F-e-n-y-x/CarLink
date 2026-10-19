// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0), app/src/test/.../nativeaa/zbt/ZbtMessagesTest.kt
package com.andrerinas.openheadunit.connection.zbt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every decode vector here is a body the vendor daemon actually sent, captured on the reporter's
 * unit on 2026-08-11 and 2026-08-12 and copied out of his exported logs. They are measurements, not
 * examples, which is what makes them worth pinning: the schema was recovered from a disassembly, and
 * these are the only bytes that have ever tested it.
 */
class ZbtMessagesTest {

    private fun bytes(hex: String): ByteArray =
        hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    // ---------------------------------------------------------------------------------------------
    // Encoders
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `varints match the protobuf encoding for the boundary values`() {
        // 127/128 straddle the first continuation bit, 16383/16384 the second. A varint that stops
        // one group early encodes a different number, and the peer would read a valid message with
        // the wrong contents rather than reporting an error.
        assertArrayEquals(byteArrayOf(0x00), ZbtMessages.varint(0))
        assertArrayEquals(byteArrayOf(0x01), ZbtMessages.varint(1))
        assertArrayEquals(byteArrayOf(0x7F), ZbtMessages.varint(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), ZbtMessages.varint(128))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F), ZbtMessages.varint(16383))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01), ZbtMessages.varint(16384))
    }

    @Test
    fun `RequestInit is two varint fields in field order`() {
        // 0x08 = field 1 varint, 0x10 = field 2 varint.
        assertArrayEquals(
            bytes("08 01 10 02"),
            ZbtMessages.encodeRequestInit(id = 1, enableType = ZbtMessages.ENABLE_TYPE_ANDROID_AUTO)
        )
    }

    @Test
    fun `RequestInit still encodes correctly when a field needs two groups`() {
        assertArrayEquals(
            bytes("08 80 01 10 00"),
            ZbtMessages.encodeRequestInit(id = 128, enableType = 0)
        )
    }

    @Test
    fun `RequestReconn is three varint fields in field order`() {
        // 0x18 = field 3 varint. Getting the field number wrong here would send enable_type as
        // is_bt_enable, which the daemon would read as a valid request to do something else.
        assertArrayEquals(
            bytes("08 94 02 10 01 18 02"),
            ZbtMessages.encodeRequestReconn(
                id = ZbtMessages.REQUEST_RECONN,
                isBtEnable = 1,
                enableType = ZbtMessages.ENABLE_TYPE_ANDROID_AUTO
            )
        )
    }

    @Test
    fun `a link info request carries only its own id`() {
        assertArrayEquals(bytes("08 83 02"), ZbtMessages.encodeLinkInfoRequest())
    }

    @Test
    fun `everything we encode round-trips through the parser`() {
        val reconn = ZbtMessages.parse(
            ZbtMessages.encodeRequestReconn(id = 0x114, isBtEnable = 1, enableType = 2)
        )!!
        assertEquals(0x114, reconn.int(1))
        assertEquals(1, reconn.int(2))
        assertEquals(2, reconn.int(3))
    }

    // ---------------------------------------------------------------------------------------------
    // Decoding real captures
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `InitInfo decodes to the module's identity`() {
        // 2026-08-12 17:44:59.553, id=0x102, 64 bytes. local_mac_address is the module's real
        // Bluetooth address on a unit where BluetoothAdapter.getAddress() returns 02:00:00:00:00:00.
        val body = bytes(
            "08 82 02 12 24 72 72 61 65 66 64 61 76 2d 73 70 64 61 2d 34 33 30 35 2d 39 38 34 62 " +
                "2d 61 65 74 64 36 39 37 32 32 30 30 31 1a 07 43 41 52 38 30 33 32 22 0c 30 30 30 " +
                "32 35 42 44 33 35 30 42 43"
        )
        assertEquals(64, body.size)
        val r = ZbtMessages.parse(body)!!
        assertEquals(258, r.int(1))  // field 1 is the message id: 258 == 0x102
        assertEquals("rraefdav-spda-4305-984b-aetd69722001", r.string(2))
        assertEquals("CAR8032", r.string(3))
        assertEquals("00025BD350BC", r.string(4))

        assertEquals(
            "InitInfo vendor_uuid=\"rraefdav-spda-4305-984b-aetd69722001\" " +
                "local_bt_name=\"CAR8032\" local_mac_address=\"00025BD350BC\"",
            ZbtMessages.describe(ZbtMessages.INIT_INFO, body)
        )
    }

    @Test
    fun `LinkInfo decodes, and is_connect is the field that moves`() {
        // The same message twice from the listen phase of 2026-08-12, 130 ms apart, differing only
        // in field 3. Pinned as a pair because reading the wrong field as is_connect would have made
        // a heartbeat look like a phone arriving.
        val connected = bytes("08 84 02 10 01 18 01 20 00 2a 0c 30 30 30 32 35 42 44 33 35 30 42 43")
        val gone = bytes("08 84 02 10 01 18 00 20 00 2a 0c 30 30 30 32 35 42 44 33 35 30 42 43")
        assertEquals(23, connected.size)

        val a = ZbtMessages.parse(connected)!!
        assertEquals(260, a.int(1))
        assertEquals(1, a.int(2))  // phone_type
        assertEquals(1, a.int(3))  // is_connect
        assertEquals(0, a.int(4))  // is_pair
        assertEquals("00025BD350BC", a.string(5))

        assertEquals(0, ZbtMessages.parse(gone)!!.int(3))
        assertEquals(
            "LinkInfo phone_type=1 is_connect=0 is_pair=0 local_mac_address=\"00025BD350BC\"",
            ZbtMessages.describe(ZbtMessages.LINK_INFO, gone)
        )
    }

    @Test
    fun `LinkInfo2 names the phone the app's own Bluetooth stack cannot see`() {
        // 2026-08-12 17:44:59.558, id=0x10c, 65 bytes. The logs of both rounds truncated the hex
        // dump at 64 bytes; byte 65 is the last character of phone_bt_name, fixed by that field's
        // own declared length of 17 and matching the 2026-08-11 decode, which read the name whole.
        // That truncation is why this round logs 0x105 bodies in full.
        val body = bytes(
            "08 8c 02 10 01 18 01 20 00 2a 0c 30 30 30 32 35 42 44 33 35 30 42 43 32 07 43 41 52 " +
                "38 30 33 32 3a 0c 43 30 35 37 32 34 37 36 44 37 34 46 42 11 48 4f 4e 4f 52 20 4d " +
                "61 67 69 63 38 20 4c 69 74 65"
        )
        assertEquals(65, body.size)
        val r = ZbtMessages.parse(body)!!
        assertEquals(268, r.int(1))
        assertEquals("00025BD350BC", r.string(5))
        assertEquals("CAR8032", r.string(6))
        assertEquals("C0572476D74F", r.string(7))
        assertEquals("HONOR Magic8 Lite", r.string(8))

        assertTrue(
            ZbtMessages.describe(ZbtMessages.LINK_INFO2, body)
                .endsWith("phone_bt_mac_addr=\"C0572476D74F\" phone_bt_name=\"HONOR Magic8 Lite\"")
        )
    }

    @Test
    fun `BLELinkInfo decodes with its empty strings intact`() {
        // 2026-08-12, id=0x109, 9 bytes. Two zero-length strings — a length-delimited field of zero
        // bytes is present, not absent, and a parser that dropped it would report a shorter message.
        val body = bytes("08 89 02 10 00 1a 00 22 00")
        val r = ZbtMessages.parse(body)!!
        assertEquals(265, r.int(1))
        assertEquals(0, r.int(2))
        assertEquals("", r.string(3))
        assertEquals("", r.string(4))
        assertEquals(listOf(1, 2, 3, 4), r.fieldNumbers)
    }

    @Test
    fun `HfplinkInfo decodes, and hands-free was down while the module held the phone`() {
        // 2026-08-12, id=0x115, 5 bytes. is_hfp_connect=0 alongside a LinkInfo2 reporting the phone
        // connected: the module is linked to a phone the app's Bluetooth stack cannot see, which is
        // this whole issue in two messages.
        val body = bytes("08 95 02 10 00")
        val r = ZbtMessages.parse(body)!!
        assertEquals(277, r.int(1))
        assertEquals(0, r.int(2))
        assertEquals("HfplinkInfo is_hfp_connect=0", ZbtMessages.describe(ZbtMessages.HFP_LINK_INFO, body))
    }

    @Test
    fun `PhoneLinkState reads as the arrival of a projection link`() {
        // Not yet seen on the wire — no run has produced one, which is itself the finding round two
        // could not state. Built from the schema so that when one does arrive it is logged as
        // something legible rather than as seven bytes of hex.
        val body = ZbtMessages.varintField(1, ZbtMessages.PHONE_LINK_STATE) +
            ZbtMessages.varintField(2, 1) + ZbtMessages.varintField(3, 2)
        assertEquals(
            "PhoneLinkState is_linked=1 link_type=2",
            ZbtMessages.describe(ZbtMessages.PHONE_LINK_STATE, body)
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Bad input
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `an empty body parses to a message with no fields`() {
        // Legal on this protocol: the reference sender omits the body write entirely for a null
        // body, so zero bytes is a message rather than a failure.
        val r = ZbtMessages.parse(ByteArray(0))!!
        assertEquals(emptyList<Int>(), r.fieldNumbers)
    }

    @Test
    fun `a length that runs past the end is refused rather than clamped`() {
        // Declares 12 bytes of string with 3 present. Clamping would invent a value and report it
        // as measured, which on this protocol means a MAC or a device name that was never sent.
        assertNull(ZbtMessages.parse(bytes("08 01 12 0c 41 42 43")))
    }

    @Test
    fun `a varint running off the end is refused`() {
        assertNull(ZbtMessages.parse(bytes("08 80 80")))
    }

    @Test
    fun `a group wire type is refused rather than guessed at`() {
        // Wire type 3 starts a group. Nothing in this schema uses one, so meeting one means the body
        // is not what we think it is.
        assertNull(ZbtMessages.parse(bytes("0b 08 01 0c")))
    }

    @Test
    fun `field zero is refused`() {
        // Tag 0 is not a legal protobuf field number, and a body starting with a zero byte is the
        // shape a desynchronised stream produces.
        assertNull(ZbtMessages.parse(bytes("00 01")))
    }

    @Test
    fun `describe never throws, whatever it is handed`() {
        // It runs on frames a peer sent us, and its output is the reporter's log. A message we
        // cannot read still has to reach the log, because "an unreadable frame arrived" is itself
        // information.
        assertTrue(ZbtMessages.describe(ZbtMessages.LINK_INFO2, bytes("08 01 12 0c 41")).startsWith("unparsed"))
        assertTrue(ZbtMessages.describe(0x999, bytes("08 07 12 02 68 69")).startsWith("unnamed"))
        assertTrue(ZbtMessages.describe(ZbtMessages.INIT_INFO, ByteArray(0)).startsWith("InitInfo"))
    }

    @Test
    fun `an rfcomm body is described by its size only`() {
        // CarLink: these bytes are the handshake, and WifiInfoResponse carries the Wi-Fi password.
        val body = ByteArray(200) { it.toByte() }
        assertEquals("RfcommData 200 bytes", ZbtMessages.describe(ZbtMessages.RFCOMM_DATA, body))
    }

    @Test
    fun `hex truncation reports the full length`() {
        assertEquals("00 01 02 … (5 bytes)", ZbtMessages.hex(ByteArray(5) { it.toByte() }, limit = 3))
        assertEquals("00 01 02 03 04", ZbtMessages.hex(ByteArray(5) { it.toByte() }, limit = 5))
    }
}
