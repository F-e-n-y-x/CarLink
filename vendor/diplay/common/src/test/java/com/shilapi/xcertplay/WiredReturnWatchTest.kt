package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CarLink: the early wired retry fires only after the iPhone left USB and came back. */
class WiredReturnWatchTest {
    @Test fun anIphoneThatNeverLeftIsNotBack() {
        val watch = WiredReturnWatch()
        assertFalse(watch.back(true))
        assertFalse(watch.back(true))
    }

    @Test fun anIphoneThatLeftAndReturnedIsBack() {
        val watch = WiredReturnWatch()
        assertFalse(watch.back(false))
        assertFalse(watch.back(false))
        assertTrue(watch.back(true))
    }
}
