package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Test

class AapMessageIncomingTest {
    @Test fun aOneByteMiddleOrLastVideoFragmentIsKept() {
        assertEquals(1, AapMessageIncoming.minimumPayload(8)) // middle
        assertEquals(1, AapMessageIncoming.minimumPayload(10)) // last
    }

    @Test fun aFirstOrSingleFragmentNeedsItsTwoByteType() {
        assertEquals(2, AapMessageIncoming.minimumPayload(9)) // first
        assertEquals(2, AapMessageIncoming.minimumPayload(11)) // single
    }
}
