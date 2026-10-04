package com.shilapi.xcertplay.network

import java.io.Closeable
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections

/** Bounded, passive startup observation. Never exports packets, names, TXT or peer addresses. */
internal class WirelessMdnsDiagnostics(private val addresses: List<InetAddress>) : Closeable {
    private class State(val v4: Boolean) {
        @Volatile var state = "not_started"
        var ownQuery = 0
        var ownResponse = 0
        var peerQuery = 0
        var peerResponse = 0
        var peerAirplay = 0
        var peerControl = 0
        var malformed = 0
    }

    // Observe both families even when P2P intentionally advertises only its IPv6 endpoint.
    private val states = if (addresses.isEmpty()) emptyList() else listOf(State(true), State(false))
    private val sockets = mutableListOf<MulticastSocket>()
    @Volatile private var closed = false
    private var started = false

    @Synchronized fun start() {
        if (started || closed) return
        started = true
        states.forEach { state ->
            Thread({ observe(state) }, "diplay-mdns-observer").apply { isDaemon = true; start() }
        }
    }

    fun snapshot(): String = states.joinToString("\n") { state -> synchronized(state) {
        "mdnsWire family=${if (state.v4) "IPv4" else "IPv6"} state=${state.state} " +
            "ownQuery=${state.ownQuery} ownResponse=${state.ownResponse} " +
            "peerQuery=${state.peerQuery} peerResponse=${state.peerResponse} " +
            "peerAirplay=${state.peerAirplay} peerControl=${state.peerControl} malformed=${state.malformed}"
    } }

    private fun observe(state: State) {
        var socket: MulticastSocket? = null
        try {
            val address = addresses.first()
            val iface = NetworkInterface.getByInetAddress(address)
                ?: throw java.io.IOException("Interface unavailable")
            val own = Collections.list(iface.inetAddresses)
            if (own.none { (it is Inet4Address) == state.v4 }) { state.state = "no_address"; return }
            val group = InetAddress.getByName(if (state.v4) "224.0.0.251" else "ff02::fb")
            socket = MulticastSocket(null)
            socket.apply {
                reuseAddress = true
                bind(InetSocketAddress(5353))
                networkInterface = iface
                soTimeout = 1000
                joinGroup(InetSocketAddress(group, 5353), iface)
            }
            synchronized(this) {
                if (closed) { socket.close(); return }
                sockets.add(socket)
            }
            state.state = "joined"
            val until = System.nanoTime() + 90_000_000_000L
            val bytes = ByteArray(9000)
            while (!closed && System.nanoTime() < until) {
                val packet = DatagramPacket(bytes, bytes.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                if ((packet.address is Inet4Address) != state.v4) continue
                val summary = MdnsPacketSummary.parse(bytes, packet.length)
                synchronized(state) {
                    if (summary == null) state.malformed++ else {
                        val local = own.any { it.address.contentEquals(packet.address.address) }
                        when {
                            local && summary.response -> state.ownResponse++
                            local -> state.ownQuery++
                            summary.response -> state.peerResponse++
                            else -> state.peerQuery++
                        }
                        if (!local && summary.airplay) state.peerAirplay++
                        if (!local && summary.control) state.peerControl++
                    }
                }
            }
            state.state = if (closed) "closed" else "complete"
        } catch (error: Exception) {
            state.state = if (closed) "closed" else "unavailable_${error.javaClass.simpleName}"
        } finally {
            socket?.close()
            synchronized(this) { sockets.remove(socket) }
        }
    }

    @Synchronized override fun close() {
        closed = true
        sockets.forEach { it.close() }
        sockets.clear()
    }
}

/** Only fixed service-type booleans leave this parser, including for compressed DNS names. */
internal data class MdnsPacketSummary(val response: Boolean, val airplay: Boolean, val control: Boolean) {
    companion object {
        fun parse(bytes: ByteArray, length: Int): MdnsPacketSummary? = try {
            require(length in 12..bytes.size)
            fun u16(offset: Int): Int {
                require(offset >= 0 && offset + 2 <= length)
                return ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
            }
            fun name(start: Int): Pair<String, Int> {
                val labels = mutableListOf<String>()
                val visited = mutableSetOf<Int>()
                var cursor = start
                var end = -1
                var size = 0
                while (true) {
                    require(cursor in 0 until length && visited.add(cursor) && visited.size <= 128)
                    val count = bytes[cursor].toInt() and 255
                    if (count == 0) { if (end < 0) end = cursor + 1; break }
                    if (count and 0xc0 == 0xc0) {
                        val pointer = u16(cursor) and 0x3fff
                        if (end < 0) end = cursor + 2
                        cursor = pointer
                    } else {
                        require(count <= 63 && cursor + count + 1 <= length)
                        size += count + 1
                        require(size <= 255)
                        labels.add(String(bytes, cursor + 1, count, Charsets.US_ASCII))
                        cursor += count + 1
                    }
                }
                return labels.joinToString(".").lowercase() to end
            }
            val questions = u16(4)
            val records = u16(6) + u16(8) + u16(10)
            require(questions + records <= 256)
            var cursor = 12
            var airplay = false
            var control = false
            repeat(questions + records) { index ->
                val (owner, end) = name(cursor)
                airplay = airplay || owner == "_airplay._tcp.local" || owner.endsWith("._airplay._tcp.local")
                control = control || owner == "_carplay-ctrl._tcp.local" || owner.endsWith("._carplay-ctrl._tcp.local")
                cursor = if (index < questions) end + 4 else end + 10 + u16(end + 8)
                require(cursor <= length)
            }
            MdnsPacketSummary(bytes[2].toInt() and 0x80 != 0, airplay, control)
        } catch (_: IllegalArgumentException) { null }
    }
}
