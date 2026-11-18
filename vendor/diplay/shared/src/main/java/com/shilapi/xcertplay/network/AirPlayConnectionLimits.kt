// CarLink: ported from upstream DiPlay PR #1130
package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Bounds the control connections the AirPlay listener keeps open, in total and per peer.
 * At a cap an unverified connection older than [graceMillis] is dropped first, so idle sockets cannot keep a
 * new peer out. At the total cap the victim comes from the peer that holds the most connections.
 * Without such a connection the oldest verified connection from the requester's exact address is dropped.
 * A phone that vanished without a reset leaves one behind, and must not lock itself out. Another address in
 * the same IPv6 prefix cannot drop it. A younger connection, or a verified
 * connection of another peer, is never dropped: the new connection is refused instead.
 */
internal class AirPlayConnectionLimits(
    private val maxTotal: Int = MAX_TOTAL,
    private val maxPerPeer: Int = MAX_PER_PEER,
    private val graceMillis: Long = GRACE_MILLIS,
    private val clockMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    class Slot internal constructor(
        internal val peer: List<Byte>,
        internal val address: InetAddress,
        internal val createdMillis: Long,
        private val drop: () -> Unit,
    ) {
        @Volatile var verified: () -> Boolean = { false }
        internal fun dropConnection() = runCatching(drop)
    }

    // Insertion order is connection age.
    private val slots = LinkedHashSet<Slot>()

    /** Returns null when the connection must be refused. [drop] closes the connection. */
    @Synchronized
    fun tryAcquire(address: InetAddress, drop: () -> Unit): Slot? {
        val peer = peerKey(address)
        if (slots.count { it.peer == peer } >= maxPerPeer && !evict(peer, address, samePeerOnly = true)) return null
        if (slots.size >= maxTotal && !evict(peer, address, samePeerOnly = false)) return null
        return Slot(peer, address, clockMillis(), drop).also(slots::add)
    }

    @Synchronized
    fun release(slot: Slot) {
        slots.remove(slot)
    }

    @Synchronized
    fun openCount(): Int = slots.size

    private fun evict(requester: List<Byte>, address: InetAddress, samePeerOnly: Boolean): Boolean {
        val now = clockMillis()
        val victim = slots
            .filter { (!samePeerOnly || it.peer == requester) && !it.verified() && now - it.createdMillis >= graceMillis }
            .groupBy { it.peer }
            .maxByOrNull { (peer, _) -> slots.count { it.peer == peer } }
            ?.value?.first()
            ?: slots.firstOrNull { it.address == address && it.verified() }
            ?: return false
        slots.remove(victim)
        victim.dropConnection()
        return true
    }

    // Link-local and unique-local neighbours can share a /64 without being related, so only global addresses
    // are grouped by prefix. A scope id keeps the same link-local address on two interfaces apart.
    private fun peerKey(address: InetAddress): List<Byte> {
        val bytes = address.address.toList()
        if (address !is Inet6Address) return bytes
        val local = address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isLoopbackAddress || (bytes[0].toInt() and ULA_MASK) == ULA_PREFIX
        return if (local) bytes + address.scopeId.toString().toByteArray().toList() else bytes.take(IPV6_PREFIX_BYTES)
    }

    companion object {
        // One iPhone holds one control connection; the margin covers a stale one before keepalive reaps it.
        const val MAX_PER_PEER = 8
        const val MAX_TOTAL = 32
        private const val IPV6_PREFIX_BYTES = 8
        private const val ULA_MASK = 0xfe
        private const val ULA_PREFIX = 0xfc
        private const val NANOS_PER_MILLI = 1_000_000L

        // Real pairing finishes within about 150 ms of the first request. The grace is unmeasured on slow links.
        const val GRACE_MILLIS = 2_000L

        // The first pairing runs SRP-3072 and Ed25519 in Java, which takes seconds on slow head units. The wired start limit is 30 to 45 s.
        const val PRE_PAIR_VERIFY_TIMEOUT_MILLIS = 30_000L
    }
}
