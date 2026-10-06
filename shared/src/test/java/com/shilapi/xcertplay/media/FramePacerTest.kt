package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {
    private val ms = 1_000_000L
    private val frame = 16_666_667L
    private val clockOffset = 5_000_000_000L // iPhone clock vs System.nanoTime

    @Test fun evenSenderTimesStayEvenDespiteUnevenArrival() {
        val pacer = FramePacer()
        val jitter = longArrayOf(0, 25, 3, 40, 0, 12, 0, 30, 5, 0)
        val targets = (0 until 300).map { i ->
            val sender = i * frame
            pacer.target(sender, sender + clockOffset + 10 * ms + jitter[i % jitter.size] * ms, 90 * ms)
        }
        // After the first window settles, consecutive targets are exactly one frame apart.
        targets.drop(60).zipWithNext().forEach { (a, b) -> assertEquals(frame, b - a) }
        // The base delay is the fastest transit (10 ms) plus the fixed 90 ms.
        assertEquals(299 * frame + clockOffset + 10 * ms + 90 * ms, targets.last())
    }

    @Test fun aFastSampleLeavingTheWindowDoesNotJumpTheOffset() {
        val pacer = FramePacer(window = 60, refreshEvery = 1)
        var previous = 0L
        for (i in 0 until 400) {
            val sender = i * frame
            // One unusually fast frame at i = 10, then everything 20 ms slower.
            val transit = if (i == 10) 0L else 20 * ms
            val target = pacer.target(sender, sender + clockOffset + transit, 90 * ms)
            if (i > 0) assertTrue("step at $i was ${target - previous}", target - previous in frame - ms..frame + ms)
            previous = target
        }
    }

    @Test fun aClockJumpFallsBackToImmediateThenReanchors() {
        val pacer = FramePacer()
        repeat(100) { i -> pacer.target(i * frame, i * frame + clockOffset, 90 * ms) }
        // The iPhone's clock moves back by two seconds: old-offset targets would be two seconds late.
        val jumped = (100 until 500).map { i -> pacer.target(i * frame, i * frame + clockOffset + 2_000 * ms, 90 * ms) }
        assertEquals(0L, jumped.first()) // shown as soon as decoded, not two seconds late
        assertEquals(499 * frame + clockOffset + 2_000 * ms + 90 * ms, jumped.last())
    }

    @Test fun aFrameFarBehindItsTimeIsShownAtOnce() {
        val pacer = FramePacer()
        repeat(100) { i -> pacer.target(i * frame, i * frame + clockOffset, 90 * ms) }
        // One frame arrives 1.5 s late: its own time has long passed.
        assertEquals(0L, pacer.target(100 * frame, 100 * frame + clockOffset + 1_500 * ms, 90 * ms))
    }

    @Test fun aSlowFirstFrameDoesNotSetTheBase() {
        val pacer = FramePacer()
        val targets = (0 until 60).map { i ->
            val sender = i * frame
            val transit = if (i == 0) 50 * ms else 10 * ms // the first frame, a keyframe, arrives late
            pacer.target(sender, sender + clockOffset + transit, 90 * ms)
        }
        // By frame 30 the base is the 10 ms transit, not frame 0's 50 ms.
        assertEquals(30 * frame + clockOffset + 10 * ms + 90 * ms, targets[30])
    }

    @Test fun aForwardClockStepNeverHoldsFramesLong() {
        val pacer = FramePacer()
        repeat(100) { i -> pacer.target(i * frame, i * frame + clockOffset + 10 * ms, 90 * ms) }
        // The iPhone's clock jumps 700 ms forward: old-offset targets would be 700 ms early... or held.
        for (i in 100 until 400) {
            val sender = i * frame + 700 * ms
            val arrival = i * frame + clockOffset + 10 * ms
            val target = pacer.target(sender, arrival, 90 * ms)
            assertTrue("frame $i held ${(target - arrival) / ms} ms", target == 0L || target - arrival <= 190 * ms)
            if (i >= 130) assertEquals(arrival + 90 * ms, target)
        }
    }
}
