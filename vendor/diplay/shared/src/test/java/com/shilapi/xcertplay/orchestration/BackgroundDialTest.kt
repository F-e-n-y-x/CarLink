package com.shilapi.xcertplay.orchestration

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// CarLink: the wireless RFCOMM dial runs while the access point and listener start.
class BackgroundDialTest {
    @Test fun theDialRunsWhileTheCallerDoesOtherWork() {
        val dialing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dial = BackgroundDial("test-dial") {
            dialing.countDown()
            release.await()
        }
        // The caller is not blocked: it observes the dial in progress and keeps working.
        assertTrue(dialing.await(2, TimeUnit.SECONDS))
        assertFalse(dial.isDone)
        release.countDown()
        assertTrue(dial.await(2_000))
        assertTrue(dial.isDone)
    }

    @Test fun aDialThatFinishedEarlyIsCollectedAtOnce() {
        val dial = BackgroundDial("test-dial") {}
        assertTrue(dial.await(2_000))
        assertTrue(dial.await(0)) // completed: no time left is not a timeout
    }

    @Test fun theConnectFailureIsRethrownToTheCaller() {
        val failure = IOException("read failed, socket might closed or timeout")
        val dial = BackgroundDial("test-dial") { throw failure }
        try {
            dial.await(2_000)
            fail("expected the dial failure")
        } catch (error: IOException) {
            assertSame(failure, error)
        }
    }

    @Test fun theTimeoutCountsFromTheDialsStartNotFromTheWait() {
        var now = 0L
        val release = CountDownLatch(1)
        val dial = BackgroundDial("test-dial", nanoTime = { now }) { release.await() }
        // 15 s budget, 15 s of bring-up already elapsed while dialing: no extra wait.
        now = 15_000L * 1_000_000L
        val started = System.nanoTime()
        assertFalse(dial.await(15_000))
        assertTrue((System.nanoTime() - started) / 1_000_000L < 1_000L)
        release.countDown()
    }

    @Test fun recordsWhenTheDialFinished() {
        var now = 5L * 1_000_000L
        val dial = BackgroundDial("test-dial", nanoTime = { now }) { now = 6_605L * 1_000_000L }
        assertTrue(dial.await(2_000))
        assertEquals(6_600L, (dial.finishedNanos!! - dial.startedNanos) / 1_000_000L)
    }
}
