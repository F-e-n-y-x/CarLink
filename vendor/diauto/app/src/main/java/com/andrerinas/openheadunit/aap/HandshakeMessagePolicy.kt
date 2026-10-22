package com.andrerinas.openheadunit.aap

// CarLink: ported from open-headunit 67bcae27 (docs/VENDOR_CHANGES.md).

/** Classifies complete cleartext handshake frames: channel, flags, length (2), type (2), payload. */
internal object HandshakeMessagePolicy {
    /**
     * A VersionResponse (channel 0, type 2). A slow peer answers every version request it was
     * sent, a retried one included, and the extra answers land in the TLS phase, where the
     * SSLEngine would fail on them; the TLS reader skips them.
     */
    fun isLateVersionResponse(frame: ByteArray): Boolean =
        frame.size >= 6 && frame[0] == 0.toByte() && frame[4] == 0.toByte() && frame[5] == 2.toByte()

    /** The peer rejected framing (0xffff) or asked to end this session (ByeByeRequest). */
    fun isTerminalResponse(frame: ByteArray): Boolean = frame.size >= 6 && frame[0] == 0.toByte() &&
        ((frame[4] == (-1).toByte() && frame[5] == (-1).toByte()) ||
            (frame[4] == 0.toByte() && frame[5] == 15.toByte()))

    /**
     * CarLink (open-headunit 68c2bbb5): TCP delivers a request or drops the link, so a resend there
     * reaches the peer only as a duplicate, and after one Android Auto's head unit server stops
     * answering. USB can lose a request, so it keeps its retries.
     */
    fun versionRequests(isUsb: Boolean): Int = if (isUsb) 3 else 1
}
