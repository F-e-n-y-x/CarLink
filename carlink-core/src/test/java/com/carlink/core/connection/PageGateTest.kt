package com.carlink.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class PageGateTest {
    private val clock = AtomicLong(1_000)

    @Test fun noPageMeansNoWait() {
        assertEquals(0, PageGate(6_000) { clock.get() }.await("AA:BB"))
    }

    @Test fun aDialWaitsUntilThePageEnds() {
        val gate = PageGate(6_000) { System.nanoTime() / 1_000_000 }
        gate.begin("aa:bb")
        Thread { Thread.sleep(150); gate.end("AA:BB") }.start()
        val waited = gate.await("AA:BB")
        assertTrue("waited $waited ms", waited in 100..3_000)
        assertEquals("the page is over", 0, gate.await("AA:BB"))
    }

    @Test fun theWaitEndsAtTheCapCountedFromThePageStart() {
        val gate = PageGate(6_000) { clock.get() }
        gate.begin("AA:BB")
        clock.addAndGet(6_000) // the page has used its whole window already
        assertEquals(0, gate.await("AA:BB"))
    }

    @Test fun aPageThatNeverEndsIsBoundedByTheCap() {
        val gate = PageGate(200) { System.nanoTime() / 1_000_000 }
        gate.begin("AA:BB")
        val waited = gate.await("AA:BB")
        assertTrue("waited $waited ms", waited in 150..2_000)
    }
}
