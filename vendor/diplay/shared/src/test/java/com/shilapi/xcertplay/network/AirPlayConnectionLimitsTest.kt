package com.shilapi.xcertplay.network

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayConnectionLimitsTest {
    private val a = InetAddress.getByName("192.0.2.10")
    private val b = InetAddress.getByName("192.0.2.11")
    private val dropped = mutableListOf<String>()
    private var now = 0L

    private fun limits(maxTotal: Int, maxPerPeer: Int) =
        AirPlayConnectionLimits(maxTotal, maxPerPeer, graceMillis = GRACE, clockMillis = { now })

    private fun AirPlayConnectionLimits.acquire(address: InetAddress, name: String = address.hostAddress!!) =
        tryAcquire(address) { dropped.add(name) }

    private fun pastGrace() { now += GRACE }

    @Test fun dropsTheOldestUnverifiedConnectionAtThePerPeerCap() {
        val limits = limits(maxTotal = 10, maxPerPeer = 2)
        limits.acquire(a, "first")
        now += 1
        limits.acquire(a, "second")
        pastGrace()
        assertNotNull(limits.acquire(a, "third"))
        assertEquals(listOf("first"), dropped)
        assertNotNull(limits.acquire(b))
    }

    @Test fun dropsTheOldestUnverifiedConnectionAtTheTotalCap() {
        val limits = limits(maxTotal = 2, maxPerPeer = 2)
        limits.acquire(a, "oldest")
        now += 1
        limits.acquire(b, "newer")
        pastGrace()
        assertNotNull(limits.acquire(InetAddress.getByName("192.0.2.12"), "newest"))
        assertEquals(listOf("oldest"), dropped)
    }

    @Test fun idleSocketsFromManyAddressesDoNotLockOutANewPeer() {
        val limits = limits(maxTotal = 4, maxPerPeer = 2)
        repeat(4) { limits.acquire(InetAddress.getByName("192.0.2.${100 + it}")) ; now += 1 }
        pastGrace()
        assertNotNull(limits.acquire(a))
        assertEquals(listOf("192.0.2.100"), dropped)
    }

    @Test fun totalCapDropsFromThePeerHoldingTheMostConnections() {
        val limits = limits(maxTotal = 4, maxPerPeer = 4)
        limits.acquire(a, "real iPhone")
        repeat(3) { limits.acquire(b, "flood $it") }
        pastGrace()
        assertNotNull(limits.acquire(InetAddress.getByName("192.0.2.12")))
        assertEquals(listOf("flood 0"), dropped)
    }

    @Test fun aVerifiedConnectionIsNeverDropped() {
        val limits = limits(maxTotal = 2, maxPerPeer = 2)
        limits.acquire(a, "verified")!!.verified = { true }
        limits.acquire(a, "idle")
        pastGrace()
        assertNotNull(limits.acquire(a, "new"))
        assertEquals(listOf("idle"), dropped)
    }

    @Test fun refusesWhenEveryConnectionIsVerified() {
        val limits = limits(maxTotal = 2, maxPerPeer = 2)
        limits.acquire(a)!!.verified = { true }
        limits.acquire(b)!!.verified = { true }
        pastGrace()
        assertNull(limits.acquire(InetAddress.getByName("192.0.2.12")))
        assertTrue(dropped.isEmpty())
    }

    @Test fun refusesTheNewConnectionWhileEveryOtherOneIsWithinTheGrace() {
        val limits = limits(maxTotal = 1, maxPerPeer = 1)
        limits.acquire(a, "young")
        assertNull(limits.acquire(b))
        assertTrue(dropped.isEmpty())
        pastGrace()
        assertNotNull(limits.acquire(b))
        assertEquals(listOf("young"), dropped)
    }

    @Test fun addressesInOneGlobalIpv6PrefixShareOnePerPeerCap() {
        val limits = limits(maxTotal = 100, maxPerPeer = 2)
        val accepted = (1..20).count { limits.acquire(InetAddress.getByName("2001:db8:0:1::$it")) != null }
        assertEquals(2, accepted)
        assertNotNull(limits.acquire(InetAddress.getByName("2001:db8:0:2::1")))
    }

    @Test fun releaseFreesASlotAndIsIdempotentAfterADrop() {
        val limits = limits(maxTotal = 1, maxPerPeer = 1)
        val first = limits.acquire(a)!!
        pastGrace()
        val second = limits.acquire(a)!!
        limits.release(first)
        limits.release(second)
        assertNotNull(limits.acquire(b))
        assertEquals(1, dropped.size)
    }

    @Test fun unrelatedLinkLocalAndUniqueLocalNeighboursDoNotShareACap() {
        listOf("fe80::1" to "fe80::2", "fd00::1" to "fd00::2").forEach { (first, second) ->
            val limits = limits(maxTotal = 10, maxPerPeer = 2)
            repeat(2) { limits.acquire(InetAddress.getByName(first))!!.verified = { true } }
            assertNotNull("$second was refused because of $first", limits.acquire(InetAddress.getByName(second)))
        }
    }

    @Test fun aConnectionThatIsStillPairingSurvivesAStrangerFlood() {
        val limits = limits(maxTotal = 4, maxPerPeer = 4)
        limits.acquire(a, "real iPhone")
        repeat(6) { limits.acquire(InetAddress.getByName("198.51.100.${it + 1}"), "stranger $it") }
        assertTrue("real iPhone was dropped: $dropped", "real iPhone" !in dropped)
        assertTrue("a stranger was dropped inside the grace: $dropped", dropped.isEmpty())
    }

    @Test fun aJustAcceptedConnectionIsNotDroppedBeforeItsSetupFinishes() {
        val limits = limits(maxTotal = 1, maxPerPeer = 1)
        limits.acquire(a, "fresh")
        limits.acquire(b, "next")
        assertTrue("fresh connection was dropped: $dropped", dropped.isEmpty())
    }

    @Test fun aPeerThatVanishedCannotLockItselfOutWithVerifiedConnections() {
        val limits = limits(maxTotal = 100, maxPerPeer = 2)
        limits.acquire(a, "zombie 1")!!.verified = { true }
        now += 1
        limits.acquire(a, "zombie 2")!!.verified = { true }
        assertNotNull(limits.acquire(a, "reconnect"))
        assertEquals(listOf("zombie 1"), dropped)
    }

    @Test fun atTheTotalCapOnlyTheRequestersOwnVerifiedConnectionIsDropped() {
        val limits = limits(maxTotal = 2, maxPerPeer = 8)
        limits.acquire(a, "a oldest")!!.verified = { true }
        now += 1
        limits.acquire(a, "a newer")!!.verified = { true }
        assertNotNull(limits.acquire(a, "a reconnect"))
        assertEquals(listOf("a oldest"), dropped)
    }

    @Test fun aVerifiedConnectionOfAnotherPeerIsNeverDropped() {
        val limits = limits(maxTotal = 2, maxPerPeer = 8)
        limits.acquire(a, "iPhone 1")!!.verified = { true }
        limits.acquire(a, "iPhone 2")!!.verified = { true }
        pastGrace()
        assertNull(limits.acquire(b, "stranger"))
        assertTrue(dropped.isEmpty())
    }

    @Test fun anUnverifiedConnectionIsDroppedBeforeAVerifiedOneOfTheSamePeer() {
        val limits = limits(maxTotal = 100, maxPerPeer = 2)
        limits.acquire(a, "verified")!!.verified = { true }
        now += 1
        limits.acquire(a, "idle")
        pastGrace()
        assertNotNull(limits.acquire(a, "new"))
        assertEquals(listOf("idle"), dropped)
    }

    @Test fun aStrangerInTheSameIpv6PrefixCannotDropTheIphonesVerifiedConnection() {
        val limits = limits(maxTotal = 100, maxPerPeer = 3)
        val iphone = InetAddress.getByName("2001:db8:0:1::1")
        val stranger = InetAddress.getByName("2001:db8:0:1::99")
        limits.acquire(iphone, "iPhone")!!.verified = { true }
        now += 1
        repeat(6) { limits.acquire(stranger, "stranger $it") }
        assertTrue("the iPhone's verified connection was dropped: $dropped", "iPhone" !in dropped)
    }

    @Test fun theIphoneReconnectingFromTheSameAddressStillReplacesItsOwnZombie() {
        val limits = limits(maxTotal = 100, maxPerPeer = 2)
        val iphone = InetAddress.getByName("2001:db8:0:1::1")
        limits.acquire(iphone, "zombie 1")!!.verified = { true }
        now += 1
        limits.acquire(iphone, "zombie 2")!!.verified = { true }
        assertNotNull(limits.acquire(iphone, "reconnect"))
        assertEquals(listOf("zombie 1"), dropped)
    }

    private companion object { const val GRACE = 1_000L }
}
