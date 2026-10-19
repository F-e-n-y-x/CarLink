// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0), connection/wifi/modes/nativeaa/zbt/ZbtFraming.kt
// (see docs/VENDOR_CHANGES.md).
package com.andrerinas.openheadunit.connection.zbt

/**
 * The wire framing of the vendor Bluetooth daemon's protocol, as spoken over a loopback socket to
 * the process that owns a head unit's external Bluetooth module.
 *
 * Every message is a sixteen byte header followed by a protobuf body:
 *
 * ```
 *   0   1   2   3   4   5   6   7   8   9  10  11  12  13  14  15  16 ...
 * +---------------+---------------+---------------+---------------+-----------+
 * |    magic      |    version    |    msg id     |   body len    | protobuf  |
 * |  uint32 BE    |  uint32 BE    |  uint32 BE    |  uint32 BE    |   body    |
 * +---------------+---------------+---------------+---------------+-----------+
 * ```
 *
 * All four fields are big-endian. [MAGIC] is the only one the peer validates; it reads the version
 * into a local and never checks it, so a wrong version is accepted today and may not be tomorrow.
 * A body length below zero is rejected, and a length of zero is legal — the peer writes the header
 * alone for a message with no body.
 *
 * The header and the body arrive as **separate writes**, so neither is guaranteed to land in one
 * read. Callers must accumulate to a byte count rather than trusting a single read to deliver a
 * whole header, which is why this object only encodes and decodes: the reading loop belongs to
 * whoever owns the socket.
 *
 * Pure and tested because it is the one part of the exchange that fails silently. A header we build
 * wrong is not rejected with an error; the peer either drops the frame or, worse, misreads the
 * length and desynchronises for the rest of the session.
 */
object ZbtFraming {

    /** Bytes of header in front of every body. */
    const val HEADER_SIZE = 16

    /** Constant first field. The peer rejects any frame whose magic is not this. */
    const val MAGIC = 0x0000FFFF

    /**
     * Constant second field, written by the reference implementation.
     *
     * The peer parses it and then ignores it, so this is what to send rather than what to require.
     */
    const val VERSION = 0x00000101

    /**
     * The sixteen header bytes for a [bodySize]-byte body of message [msgId].
     *
     * @throws IllegalArgumentException if [bodySize] is negative. Loud rather than coerced: the peer
     *   rejects a negative length outright, and a caller that reached here with one has a bug worth
     *   seeing at the point it happened.
     */
    fun encodeHeader(bodySize: Int, msgId: Int): ByteArray {
        require(bodySize >= 0) { "ZBT body size $bodySize is negative" }
        val header = ByteArray(HEADER_SIZE)
        putUInt32(header, 0, MAGIC)
        putUInt32(header, 4, VERSION)
        putUInt32(header, 8, msgId)
        putUInt32(header, 12, bodySize)
        return header
    }

    /** A complete frame: header for [body] of message [msgId], then the body itself. */
    fun encodeFrame(body: ByteArray, msgId: Int): ByteArray = encodeHeader(body.size, msgId) + body

    /** Whether a [HEADER_SIZE]-byte header carries the magic the peer requires. */
    fun hasValidMagic(header: ByteArray): Boolean = decodeMagic(header) == MAGIC

    /** The magic declared by a [HEADER_SIZE]-byte header, for reporting one that does not match. */
    fun decodeMagic(header: ByteArray): Int = getUInt32(header, 0)

    /** The version declared by a [HEADER_SIZE]-byte header. Informational; nothing validates it. */
    fun decodeVersion(header: ByteArray): Int = getUInt32(header, 4)

    /** The message id declared by a [HEADER_SIZE]-byte header. */
    fun decodeMsgId(header: ByteArray): Int = getUInt32(header, 8)

    /**
     * The body length declared by a [HEADER_SIZE]-byte header, or -1 when the header is unusable —
     * either the magic is wrong or the length is negative, both of which the peer rejects.
     *
     * Returns -1 rather than throwing because this decides whether to keep reading a socket, and a
     * peer sending us nonsense is a runtime condition rather than a programming error.
     */
    fun decodeBodySize(header: ByteArray): Int {
        if (!hasValidMagic(header)) return -1
        val size = getUInt32(header, 12)
        return if (size < 0) -1 else size
    }

    private fun putUInt32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    private fun getUInt32(source: ByteArray, offset: Int): Int {
        require(source.size >= HEADER_SIZE) {
            "ZBT header needs $HEADER_SIZE bytes, got ${source.size}"
        }
        return ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)
    }
}
