package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.connection.NativeCreateWindow.RefreshAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// CarLink: a refresh during a create must wait for it, not start a second bring-up.
class NativeCreateWindowTest {
    @Test fun aCreateIsInFlightForItsGraceOnly() {
        assertFalse(NativeCreateWindow.inFlight(0L, 10_000L))
        assertTrue(NativeCreateWindow.inFlight(10_000L, 10_000L))
        assertTrue(NativeCreateWindow.inFlight(10_000L, 24_999L))
        assertFalse(NativeCreateWindow.inFlight(10_000L, 25_000L))
        assertFalse(NativeCreateWindow.inFlight(10_000L, 9_000L)) // clock went back: not trusted
    }

    @Test fun theRaceThatMadeTwoCreates() {
        // The manual poke found no credentials 300 ms into the bring-up and asked for a refresh;
        // the sticky "not connected" broadcast had already cleared isGroupCreatingOrCreated.
        val inFlight = NativeCreateWindow.inFlight(1_000L, 1_300L)
        assertEquals(RefreshAction.WAIT,
            NativeCreateWindow.refresh(groupExists = false, createInFlight = inFlight, creatingOrCreated = false))
    }

    @Test fun refreshRedeliversAnExistingGroupAndCreatesOnlyWhenNothingIsPending() {
        assertEquals(RefreshAction.REDELIVER, NativeCreateWindow.refresh(true, createInFlight = true, creatingOrCreated = true))
        assertEquals(RefreshAction.WAIT, NativeCreateWindow.refresh(false, createInFlight = false, creatingOrCreated = true))
        assertEquals(RefreshAction.CREATE, NativeCreateWindow.refresh(false, createInFlight = false, creatingOrCreated = false))
    }

    @Test fun aWaitAsksAgainOnceTheGraceIsUp() {
        assertEquals(15_500L, NativeCreateWindow.recheckDelayMs(1_000L, 1_000L))
        assertEquals(500L + 5_000L, NativeCreateWindow.recheckDelayMs(1_000L, 11_000L))
        assertEquals(500L, NativeCreateWindow.recheckDelayMs(1_000L, 99_000L))
    }
}
