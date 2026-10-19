// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0), connection/wifi/modes/nativeaa/blink/BlinkAutoLine.kt
// (see docs/VENDOR_CHANGES.md).
package com.andrerinas.openheadunit.connection.blink

import com.andrerinas.openheadunit.aap.WppFraming
import com.andrerinas.openheadunit.aap.WppMessageType

/**
 * The line protocol FYT's `blink` daemon speaks on `/dev/auto_serial`.
 *
 * `blink` drives the external BLINK Bluetooth module on FYT UIS7862 units running DUDUOS (the one
 * phones see as "DUDUAUTO"). It owns the Android Auto RFCOMM service on that module and relays it to
 * a pseudo-terminal, which the vendor's `com.syu.carlink` normally holds. Every line ends in CRLF.
 *
 * From the daemon, as captured from a stock Carlink session:
 * - `JH<12 hex>` - a phone's hands-free link came up; the phone's address, no separators.
 * - `AA` - the phone opened the Android Auto RFCOMM channel. The head unit speaks first after this.
 * - `SS` - the phone closed the Android Auto RFCOMM channel.
 * - `ZB<hex>` - one WiFi Projection Protocol frame from the phone, header included, as hex text.
 *
 * To the daemon:
 * - `AT#ZA<hex>` - one frame to the phone, header included, uppercase hex text.
 *
 * Pure, so the parsing can be pinned against the captured lines without a unit.
 */
object BlinkAutoLine {

    sealed class Event {
        /** A phone's hands-free link is up. [address] is canonical `AA:BB:CC:DD:EE:FF`. */
        data class PhoneLinked(val address: String) : Event()

        /** The phone opened the Android Auto channel on the module. */
        object ChannelOpened : Event()

        /** The phone closed the Android Auto channel on the module. */
        object ChannelClosed : Event()

        /** One frame from the phone. */
        class Frame(val bytes: ByteArray) : Event()

        /** Anything else. Kept so the log can show it. */
        data class Other(val line: String) : Event()
    }

    /** One line from the daemon, CR/LF already stripped or not. */
    fun parse(raw: String): Event {
        // DUDUOS ships /dev/auto_serial with IUCLC enabled, so the tty line discipline turns the
        // daemon's `AA`, `JH...`, and `ZB...` into lowercase before userspace sees them. Keep the
        // parser tolerant even though the channel also tries to clear that flag.
        val original = raw.trim()
        val line = original.uppercase()
        if (line == "AA") return Event.ChannelOpened
        if (line == "SS") return Event.ChannelClosed
        if (line.startsWith("JH") && line.length == 14) {
            val hex = line.substring(2)
            if (isHex(hex)) return Event.PhoneLinked(hex.chunked(2).joinToString(":").uppercase())
        }
        if (line.startsWith("ZB")) {
            // Any valid hex, even shorter than a WPP header: the handshake reads a byte stream, so
            // a frame split across two records must not lose its tail. Only idleReply needs whole
            // frames, and it checks for itself.
            val bytes = decodeHex(line.substring(2))
            if (bytes != null && bytes.isNotEmpty()) return Event.Frame(bytes)
        }
        return Event.Other(original)
    }

    /**
     * The lines that send [frame] (header included) to the phone.
     *
     * The stock client never sends a frame in one line: it splits the hex into
     * [MAX_HEX_PER_LINE]-character `AT#ZA` lines. RFCOMM is a byte stream, so the split is invisible
     * to the phone, and matching it keeps longer lines away from module firmware that may not take
     * them. Unlike the stock client, a hex length that is an exact multiple of the chunk size does
     * not produce a trailing empty `AT#ZA`.
     */
    fun encode(frame: ByteArray): String =
        toHex(frame).chunked(MAX_HEX_PER_LINE).joinToString("") { "AT#ZA$it\r\n" }

    /** 50 bytes per line, as the stock client sends. */
    const val MAX_HEX_PER_LINE = 100

    /**
     * What to answer on our own while no handshake owns the channel, or null.
     *
     * After the WiFi session is up the phone keeps talking over Bluetooth, and the stock client
     * answers two things there, byte for byte as below: a ping is echoed back as a ping response
     * with field 2 = 0 appended, and an empty type-1 message is answered with type 7 carrying
     * status -4 (PROJECTION_ALREADY_STARTED). Captured from a stock Carlink session that stayed
     * connected.
     */
    fun idleReply(frame: ByteArray): ByteArray? {
        if (frame.size < WppFraming.HEADER_SIZE) return null
        val type = WppFraming.decodeType(frame)
        val size = WppFraming.decodePayloadSize(frame)
        if (frame.size != WppFraming.HEADER_SIZE + size) return null
        val payload = frame.copyOfRange(WppFraming.HEADER_SIZE, frame.size)
        return when {
            type == WppMessageType.PING_REQUEST ->
                WppFraming.encodeFrame(payload + PING_TRAILER, WppMessageType.PING_RESPONSE)
            type == WppMessageType.START_REQUEST && size == 0 ->
                WppFraming.encodeFrame(ALREADY_STARTED, WppMessageType.START_RESPONSE)
            else -> null
        }
    }

    /** Field 3, varint -4: what the stock client answers an empty type-1 message with. */
    private val ALREADY_STARTED = decodeHex("18FCFFFFFFFFFFFFFFFF01")!!

    /** Field 2, varint 0: appended to every ping echo by the stock client. */
    private val PING_TRAILER = byteArrayOf(0x12, 0x00)

    fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    fun decodeHex(text: String): ByteArray? {
        if (text.length % 2 != 0 || !isHex(text)) return null
        return ByteArray(text.length / 2) { i ->
            ((Character.digit(text[i * 2], 16) shl 4) or Character.digit(text[i * 2 + 1], 16)).toByte()
        }
    }

    private fun isHex(text: String): Boolean = text.all { Character.digit(it, 16) >= 0 }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
