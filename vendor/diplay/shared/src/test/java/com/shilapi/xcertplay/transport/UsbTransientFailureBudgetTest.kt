package com.shilapi.xcertplay.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// CarLink: the bounded budget for null reaps.
class UsbTransientFailureBudgetTest {
    private var now = 0L

    @Test fun allowsAFewConsecutiveFailuresThenGivesUp() {
        val budget = UsbTransientFailureBudget(maxConsecutive = 3, windowMillis = 2_000) { now }
        repeat(3) { assertTrue(budget.recordFailure()) }
        assertFalse(budget.recordFailure())
    }

    @Test fun givesUpWhenFailuresLastLongerThanTheWindow() {
        val budget = UsbTransientFailureBudget(maxConsecutive = 10, windowMillis = 2_000) { now }
        assertTrue(budget.recordFailure())
        now += 2_000_000_000L
        assertFalse(budget.recordFailure())
    }

    @Test fun successResetsTheBudget() {
        val budget = UsbTransientFailureBudget(maxConsecutive = 1, windowMillis = 2_000) { now }
        assertTrue(budget.recordFailure())
        budget.reset()
        now += 5_000_000_000L
        assertTrue(budget.recordFailure())
    }
}
