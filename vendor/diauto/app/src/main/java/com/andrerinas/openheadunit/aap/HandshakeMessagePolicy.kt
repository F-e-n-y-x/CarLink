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
}
