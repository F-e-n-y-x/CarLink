// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0), connection/wifi/modes/nativeaa/
// HandshakeLink.kt and ExternalModuleCarrier.kt (see docs/VENDOR_CHANGES.md). The fields this
// DiAuto's handshake does not read (radioLabel, peerReportedPresent) are left out.
package com.andrerinas.openheadunit.connection

import android.bluetooth.BluetoothSocket
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * One phone-side connection the wireless handshake runs over: two streams, a close, and enough
 * identity for a log line. Everything above it (WppFraming, WppHandshakeSession, the reader
 * coroutine, the stage deadlines) is written against InputStream/OutputStream and never mentions
 * Bluetooth, so a stereo's external Bluetooth module can carry the same exchange.
 */
interface HandshakeLink : Closeable {
    /** Bytes from the phone. */
    val input: InputStream

    /** Bytes to the phone. */
    val output: OutputStream

    /** The phone's name, when the transport knows it. Logging only. */
    val peerName: String?

    /** The phone's Bluetooth address as `AA:BB:CC:DD:EE:FF`, or null. */
    val peerAddress: String?

    /**
     * Whether [peerAddress] is remembered as an auto-start/poke device. False behind a module:
     * nothing in android.bluetooth can dial that address.
     */
    val persistPeerForAutoStart: Boolean

    /**
     * Whether the opening message is repeated while the phone has said nothing. True on the ZBT
     * module, where the daemon owns the accept and a message sent before the phone's channel exists
     * is dropped unseen.
     */
    val retransmitsWhileSilent: Boolean
}

/** The ordinary route: a phone that connected to one of this unit's own RFCOMM listeners. */
class BluetoothSocketLink(private val socket: BluetoothSocket) : HandshakeLink {
    override val input: InputStream by lazy { socket.inputStream }
    override val output: OutputStream by lazy { socket.outputStream }
    override val peerName: String? get() = runCatching { socket.remoteDevice?.name }.getOrNull()
    override val peerAddress: String? get() = runCatching { socket.remoteDevice?.address }.getOrNull()
    override val persistPeerForAutoStart: Boolean get() = true
    override val retransmitsWhileSilent: Boolean get() = false
    override fun close() = socket.close()
}

/** A route that carries the handshake over the stereo's external Bluetooth module. */
interface ExternalModuleCarrier {
    /** Watches the module and hands each phone to the handshake until [close] or the manager stops. */
    suspend fun run()

    /** Ask the module side to bring the phone's Android Auto link up. Safe from any thread. */
    fun requestWake()

    /** End the carrier and unblock whatever is reading. Safe from any thread. */
    fun close()
}
