// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0), connection/wifi/modes/nativeaa/zbt/ZbtMessages.kt
// (see docs/VENDOR_CHANGES.md).
package com.andrerinas.openheadunit.connection.zbt

/**
 * The message ids and payloads of the vendor Bluetooth daemon's protocol, and just enough protobuf
 * to read them.
 *
 * Every message in this schema is proto2 with scalar fields only — varints and length-delimited
 * strings or bytes, nothing nested and nothing repeated — so a hundred lines of hand-rolled codec
 * covers all of it. That is deliberate rather than lazy: this repo has **no protobuf Gradle plugin**
 * (`app/build.gradle.kts` carries only the runtime dependency, and the generated Java under
 * `aap/protocol/proto/` is committed by `app/src/main/proto/gen.sh`), so adopting the schema properly
 * means committing thirty-two generated messages to read the five we actually look at. When the
 * transport ships and needs the rest, that trade flips; today it does not.
 *
 * By this protocol's own convention **field 1 of every message is the message id**, in both
 * directions. The daemon does not enforce it — it answered a `RequestInit` carrying `id = 1` — but
 * every reply it sends follows it, so anything new we send follows it too.
 *
 * The ids and the schema were recovered by disassembling the vendor library, then checked against
 * the frames a real daemon sent — which is what the tests here decode.
 */
object ZbtMessages {

    // ---------------------------------------------------------------------------------------------
    // Message ids
    //
    // Ids travel in both directions and the same id can mean "read" one way and "write" the other,
    // so these are named for the message rather than for a direction. Where a direction is settled
    // it is in the KDoc.
    // ---------------------------------------------------------------------------------------------

    /** `RequestInit { id, enable_type }`. Opens a session. The daemon answers with [INIT_INFO]. */
    const val REQUEST_INIT = 0x101

    /** `InitInfo { id, vendor_uuid, local_bt_name, local_mac_address }`. Daemon → client. */
    const val INIT_INFO = 0x102

    /**
     * Asks the daemon to re-send link state. Read-only: its handler ignores the body entirely and
     * calls the same routine the connect path uses to emit the opening state burst.
     */
    const val LINK_INFO_REQUEST = 0x103

    /** `LinkInfo { id, phone_type, is_connect, is_pair, local_mac_address }`. Daemon → client. */
    const val LINK_INFO = 0x104

    /**
     * The RFCOMM byte channel, **in both directions, with the raw bytes as the whole body** — there
     * is no protobuf on this id.
     *
     * `libzbt_rfcomm_data_send` builds a `0x105` frame from the caller's pointer and length with no
     * pack step, and the receive dispatcher hands a `0x105` body straight to the callback registered
     * by `libzbt_rfcomm_data_recv_CB_init`, again with no unpack. `gocsdk_zj` resolves both of those
     * symbols, so the daemon writes this id to clients and reads it from clients. The daemon owns the
     * Android Auto RFCOMM channel on the module itself, which is why nothing has to be registered
     * first: `enable_type = 2` on [REQUEST_INIT] is the whole setup.
     */
    const val RFCOMM_DATA = 0x105

    /** `BLELinkInfo { id, is_connect, local_mac_address, phone_mac_address }`. Daemon → client. */
    const val BLE_LINK_INFO = 0x109

    /**
     * `LinkInfo2 { id, phone_type, is_connect, is_pair, local_mac_address, local_bt_name,
     * phone_bt_mac_addr, phone_bt_name }`. Daemon → client, and the one that names the phone.
     */
    const val LINK_INFO2 = 0x10c

    /**
     * `RequestReconn { id, is_bt_enable, enable_type }`. Client → daemon, and the only lever we have
     * that acts on the module: it asks it to bring Bluetooth up for a given link type.
     *
     * This is the module-side equivalent of `NativeAaHandshakeManager.triggerPoke()`, which cannot
     * help on this hardware because it goes out over the radio the phone is ignoring. It is also the
     * one handler in the daemon that has actually been read — the `is_bt_enable: %d, enable_type: %08x`
     * code — so its argument meanings are measured rather than inferred.
     *
     * It changes module state while the vendor's own client is connected, so it is not something to
     * send speculatively.
     */
    const val REQUEST_RECONN = 0x114

    /** `HfplinkInfo { id, is_hfp_connect }`. Daemon → client. */
    const val HFP_LINK_INFO = 0x115

    /**
     * `PhoneLinkState { id, is_linked, link_type }`. Daemon → client here: `gocsdk_zj` resolves
     * `libzbt_phonelink_state` (which sends it) and not `libzbt_phone_link_state_CB_init` (which
     * would receive it).
     *
     * This is the arrival of a projection link, and it is the signal that separates "the phone never
     * started Android Auto" from "it did, and the bytes went somewhere else" — an ambiguity that cost
     * a whole reporter round.
     */
    const val PHONE_LINK_STATE = 0x11f

    /**
     * Android Auto, as [REQUEST_INIT] and [REQUEST_RECONN] mean it.
     *
     * The daemon accepts exactly 1, 2, 3 and 8; anything else takes a branch that logs "error type".
     * 2 is confirmed accepted on device. 3 means "work it out from what is already connected", which
     * is no use to us, and 8 is valid but unidentified.
     */
    const val ENABLE_TYPE_ANDROID_AUTO = 2

    /** Whether [msgId] carries raw bytes rather than a protobuf body. */
    fun isRawBytes(msgId: Int): Boolean = msgId == RFCOMM_DATA

    // ---------------------------------------------------------------------------------------------
    // Encoding
    // ---------------------------------------------------------------------------------------------

    /** `RequestInit { required int32 id = 1; required int32 enable_type = 2; }`. */
    fun encodeRequestInit(id: Int, enableType: Int): ByteArray =
        varintField(1, id) + varintField(2, enableType)

    /**
     * The body of a [LINK_INFO_REQUEST].
     *
     * The daemon's handler never looks at it, so this exists only to follow the field-1-is-the-id
     * convention rather than to carry anything.
     */
    fun encodeLinkInfoRequest(id: Int = LINK_INFO_REQUEST): ByteArray = varintField(1, id)

    /** `RequestReconn { required int32 id = 1; required int32 is_bt_enable = 2; required int32 enable_type = 3; }`. */
    fun encodeRequestReconn(id: Int, isBtEnable: Int, enableType: Int): ByteArray =
        varintField(1, id) + varintField(2, isBtEnable) + varintField(3, enableType)

    /** A varint field: the tag for [field] with wire type 0, then the value. */
    fun varintField(field: Int, value: Int): ByteArray =
        varint(field shl 3) + varint(value)

    /**
     * Base 128 varint, low group first, continuation bit set on every group but the last.
     *
     * Takes the value as unsigned, so a negative [value] encodes as ten groups the way protobuf
     * requires for a negative int32 rather than as a truncated positive.
     */
    fun varint(value: Int): ByteArray {
        var remaining = value.toLong() and 0xFFFFFFFFL
        val out = ArrayList<Byte>(5)
        do {
            var group = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining != 0L) group = group or 0x80
            out.add(group.toByte())
        } while (remaining != 0L)
        return out.toByteArray()
    }

    // ---------------------------------------------------------------------------------------------
    // Decoding
    // ---------------------------------------------------------------------------------------------

    /**
     * The scalar fields of one message body, by field number.
     *
     * A record is only ever built from a body that parsed cleanly to its last byte, so a missing
     * field means the sender omitted it rather than that parsing gave up part way.
     */
    class Record internal constructor(
        private val varints: Map<Int, Long>,
        private val chunks: Map<Int, ByteArray>
    ) {
        /** Field numbers present, in ascending order. Useful for reporting a message we cannot name. */
        val fieldNumbers: List<Int> get() = (varints.keys + chunks.keys).sorted()

        fun long(field: Int): Long? = varints[field]

        fun int(field: Int): Int? = varints[field]?.toInt()

        /** The bytes of a length-delimited field, or null if it is absent or was sent as a varint. */
        fun bytes(field: Int): ByteArray? = chunks[field]

        /**
         * A length-delimited field read as UTF-8. Every string in this schema is ASCII — MACs without
         * separators, device names, UUIDs — so a body that decodes to replacement characters is a
         * sign the field is really `bytes`, not a reason to fail.
         */
        fun string(field: Int): String? = chunks[field]?.toString(Charsets.UTF_8)
    }

    /**
     * Parse a scalar proto2 body, or return null if it is not one.
     *
     * Null rather than an exception because this runs on frames a peer sent us: a body we cannot read
     * is a thing to log and carry on from, not a programming error. Anything that would need real
     * protobuf — groups, or a length that runs past the end — gives up rather than guessing.
     */
    fun parse(body: ByteArray): Record? {
        val varints = LinkedHashMap<Int, Long>()
        val chunks = LinkedHashMap<Int, ByteArray>()
        var i = 0
        while (i < body.size) {
            val tag = readVarint(body, i) ?: return null
            i = tag.next
            val field = (tag.value ushr 3).toInt()
            if (field <= 0) return null
            when ((tag.value and 0x7L).toInt()) {
                0 -> {
                    val v = readVarint(body, i) ?: return null
                    i = v.next
                    varints[field] = v.value
                }
                1 -> {
                    if (i + 8 > body.size) return null
                    i += 8  // fixed64: no field in this schema uses it, but skipping keeps the rest readable
                }
                2 -> {
                    val len = readVarint(body, i) ?: return null
                    i = len.next
                    val size = len.value
                    if (size < 0 || size > body.size - i) return null
                    chunks[field] = body.copyOfRange(i, i + size.toInt())
                    i += size.toInt()
                }
                5 -> {
                    if (i + 4 > body.size) return null
                    i += 4  // fixed32, likewise
                }
                else -> return null  // groups: not in this schema, and not worth guessing at
            }
        }
        return Record(varints, chunks)
    }

    private class VarintAt(val value: Long, val next: Int)

    private fun readVarint(body: ByteArray, from: Int): VarintAt? {
        var result = 0L
        var shift = 0
        var i = from
        while (i < body.size) {
            val b = body[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return VarintAt(result, i)
            shift += 7
            if (shift > 63) return null
        }
        return null  // ran off the end mid-varint
    }

    // ---------------------------------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------------------------------

    /**
     * A one-line description of a frame, for the log a reporter attaches to an issue.
     *
     * Named fields where the message is known, `field=value` pairs where it is not, and the raw hex
     * only as a last resort. Reading `phone_bt_name="HONOR Magic8 Lite" is_connect=1` out of a bug
     * report beats decoding sixty-five bytes of hex by hand, and every previous round of this
     * investigation was read that way.
     *
     * Never throws. A frame we cannot describe still has to reach the log.
     */
    fun describe(msgId: Int, body: ByteArray): String = try {
        describeOrThrow(msgId, body)
    } catch (e: Exception) {
        "undescribable (${e.javaClass.simpleName}) ${hex(body, limit = 64)}"
    }

    private fun describeOrThrow(msgId: Int, body: ByteArray): String {
        // CarLink: size only. These bytes are the handshake itself, and WifiInfoResponse carries the
        // Wi-Fi password, which must never reach a log.
        if (isRawBytes(msgId)) return "RfcommData ${body.size} bytes"
        val r = parse(body) ?: return "unparsed ${body.size} bytes ${hex(body, limit = 64)}"
        return when (msgId) {
            INIT_INFO -> "InitInfo vendor_uuid=${q(r.string(2))} local_bt_name=${q(r.string(3))} " +
                "local_mac_address=${q(r.string(4))}"

            LINK_INFO -> "LinkInfo phone_type=${r.int(2)} is_connect=${r.int(3)} is_pair=${r.int(4)} " +
                "local_mac_address=${q(r.string(5))}"

            LINK_INFO2 -> "LinkInfo2 phone_type=${r.int(2)} is_connect=${r.int(3)} is_pair=${r.int(4)} " +
                "local_mac_address=${q(r.string(5))} local_bt_name=${q(r.string(6))} " +
                "phone_bt_mac_addr=${q(r.string(7))} phone_bt_name=${q(r.string(8))}"

            BLE_LINK_INFO -> "BLELinkInfo is_connect=${r.int(2)} local_mac_address=${q(r.string(3))} " +
                "phone_mac_address=${q(r.string(4))}"

            HFP_LINK_INFO -> "HfplinkInfo is_hfp_connect=${r.int(2)}"

            PHONE_LINK_STATE -> "PhoneLinkState is_linked=${r.int(2)} link_type=${r.int(3)}"

            else -> "unnamed " + r.fieldNumbers.joinToString(" ") { f ->
                val s = r.string(f)
                if (s != null) "$f=${q(s)}" else "$f=${r.long(f)}"
            }
        }
    }

    private fun q(value: String?): String = if (value == null) "-" else "\"$value\""

    /** Space-separated lower-case hex, truncated past [limit] bytes with the full length noted. */
    fun hex(bytes: ByteArray, limit: Int): String {
        val shown = if (bytes.size > limit) bytes.copyOfRange(0, limit) else bytes
        val text = shown.joinToString(" ") { String.format("%02x", it) }
        return if (bytes.size > limit) "$text … (${bytes.size} bytes)" else text
    }
}
