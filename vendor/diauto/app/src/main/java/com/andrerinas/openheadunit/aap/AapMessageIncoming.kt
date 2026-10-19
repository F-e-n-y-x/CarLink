package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.MsgType
import com.andrerinas.openheadunit.utils.AppLog

internal class AapMessageIncoming(header: EncryptedHeader, ba: ByteArrayWithLimit)
    : AapMessage(header.chan, header.flags.toByte(), Utils.bytesToInt(ba.data, 0, true), calcOffset(header), ba.limit, ba.data) {

    internal class EncryptedHeader {

        var chan: Int = 0
        var flags: Int = 0
        var enc_len: Int = 0
        var msg_type: Int = 0
        var buf = ByteArray(SIZE)

        fun decode() {
            this.chan = buf[0].toInt()
            this.flags = buf[1].toInt()

            // Encoded length of bytes to be decrypted (minus 4/8 byte headers)
            this.enc_len = Utils.bytesToInt(buf, 2, true)
        }

        companion object {
            const val SIZE = 4
        }

    }

    companion object {

        fun decrypt(header: EncryptedHeader, offset: Int, buf: ByteArray, ssl: AapSsl): AapMessage? {
            if (header.flags and 0x08 != 0x08) {
                AppLog.e("WRONG FLAG: enc_len: %d  chan: %d %s flags: 0x%02x  msg_type: 0x%02x %s",
                        header.enc_len, header.chan, Channel.name(header.chan), header.flags, header.msg_type, MsgType.name(header.msg_type, header.chan))
                return null
            }

            val ba = ssl.decrypt(offset, header.enc_len, buf) ?: return null

            // The payload length is ba.limit, not ba.data.size: the SSL layer hands back one reused
            // buffer whose length is the session ceiling, so data.size is always large enough and
            // this guard would never fire. Two bytes are needed for the message type read below.
            // CarLink: only a first or single fragment (flag bit 0) carries the type; a middle or last
            // video fragment is raw payload and may be 1 byte. Rejecting it truncated the frame and
            // glitched the picture until the next keyframe (S25, 2560x1440 HEVC, 2026-10-20).
            if (ba.limit < minimumPayload(header.flags)) {
                AppLog.e("Decrypted payload too short: " + ba.limit)
                return null
            }

            val msg = AapMessageIncoming(header, ba)

            if (AppLog.LOG_VERBOSE) {
                AppLog.d("RECV: %s", msg.toString())
            }
            return msg
        }

        /** Bytes a decrypted payload needs: a first or single fragment (flag bit 0) starts with its 2-byte type. */
        internal fun minimumPayload(flags: Int): Int = if (flags and 0x01 != 0) 2 else 1

        fun calcOffset(header: EncryptedHeader): Int {
            return 2
        }
    }
}
