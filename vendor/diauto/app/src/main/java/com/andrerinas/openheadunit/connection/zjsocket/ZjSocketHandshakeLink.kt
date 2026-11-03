// CarLink: ported from open-headunit 21087973/754e6c01 (PR #1168, AGPL-3.0), connection/wifi/modes/
// nativeaa/zjsocket/ZjSocketHandshakeLink.kt (see docs/VENDOR_CHANGES.md). radioLabel and
// peerReportedPresent are left out, as for the other links (this HandshakeLink has neither).
package com.andrerinas.openheadunit.connection.zjsocket

import android.net.LocalSocket
import com.andrerinas.openheadunit.connection.HandshakeLink
import java.io.InputStream
import java.io.OutputStream

/**
 * A [HandshakeLink] over `/dev/socket/zj_bt_socket`: on Unisoc units with the ZJ daemon and no
 * 3152 listener, the daemon owns the module's RFCOMM server and passes the phone's byte stream
 * through this socket with no ZBT framing.
 */
class ZjSocketHandshakeLink(
    private val socket: LocalSocket,
    override val peerName: String? = null,
    override val peerAddress: String? = null,
) : HandshakeLink {

    override val input: InputStream by lazy { socket.inputStream }
    override val output: OutputStream by lazy { socket.outputStream }

    /** False: android.bluetooth neither reaches nor owns the phone behind the daemon. */
    override val persistPeerForAutoStart: Boolean = false

    /** The daemon may open the socket before the phone's RFCOMM stream is up, so the opener is repeated. */
    override val retransmitsWhileSilent: Boolean = true

    override fun close() = ZjSocketConnector.close(socket)
}
