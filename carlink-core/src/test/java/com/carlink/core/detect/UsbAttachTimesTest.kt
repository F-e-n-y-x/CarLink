package com.carlink.core.detect

import org.junit.Assert.assertEquals
import org.junit.Test

/** The wait for Android's own USB grant: counted from the attach, following a re-enumerating phone. */
class UsbAttachTimesTest {
    private var clock = 10_000L

    /** [nodes]: which node the phone is on from each time on; [attaches]: when each node attached (absent: not heard). */
    private fun await(
        start: String,
        nodes: List<Pair<Long, String>>,
        attaches: Map<String, Long>,
        grantedAt: Map<String, Long> = emptyMap(),
    ): String = UsbAttachTimes.awaitDefaultGrant(
        device = start,
        current = { nodes.last { (from, _) -> from <= clock }.second },
        nameOf = { it },
        granted = { node -> grantedAt[node]?.let { clock >= it } == true },
        attachedAt = { attaches[it] },
        now = { clock },
        sleep = { clock += it },
    )

    @Test fun aPhoneOnTheBusLongerThanTheWaitIsAskedAtOnce() {
        val node = await("004", listOf(0L to "004"), attaches = mapOf("004" to clock - 3_000))
        assertEquals("004", node)
        assertEquals(10_000L, clock)
    }

    @Test fun aJustAttachedPhoneWaitsOnlyTheRestOfTheWait() {
        await("004", listOf(0L to "004"), attaches = mapOf("004" to clock - 1_500))
        assertEquals(10_500L, clock)
    }

    @Test fun anUnheardAttachGetsTheFullWaitAsBefore() {
        await("004", listOf(0L to "004"), attaches = emptyMap())
        assertEquals(12_000L, clock)
    }

    @Test fun theSystemsGrantEndsTheWait() {
        val node = await("004", listOf(0L to "004"), attaches = mapOf("004" to clock), grantedAt = mapOf("004" to 10_300L))
        assertEquals("004", node)
        assertEquals(10_300L, clock)
    }

    @Test fun aPhoneThatReEnumeratesIsFollowedAndItsNewNodeAsked() {
        // Galaxy S25, 2026-11-03: 003 left at +1 s and 004 took its place; the dialog went to 003.
        val node = await("003", listOf(0L to "003", 11_000L to "004"),
            attaches = mapOf("003" to 9_000L, "004" to 11_000L))
        assertEquals("004", node)
        assertEquals(13_000L, clock) // 004's own 2 s, not 003's
    }

    @Test fun aPhoneThatKeepsReEnumeratingIsAskedWithinTwiceTheWait() {
        val nodes = (0 until 40).map { 10_000L + it * 500 to "n$it" }
        val attaches = nodes.associate { (at, node) -> node to at }
        await("n0", nodes, attaches)
        assertEquals(14_000L, clock)
    }
}
