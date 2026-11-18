// CarLink: new file (DiAuto issue #77).
package com.andrerinas.openheadunit.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenerReopenPolicyTest {
    @Test fun `a failed listener is reopened with a growing delay and then given up`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), (1..5).map { ListenerReopenPolicy.delayMillis(it) })
        assertNull(ListenerReopenPolicy.delayMillis(6))
    }

    @Test fun `a listener that stayed up starts a fresh count`() {
        assertEquals(4, ListenerReopenPolicy.failuresInRow(3, livedMillis = 500))
        assertEquals(1, ListenerReopenPolicy.failuresInRow(5, livedMillis = ListenerReopenPolicy.STABLE_MILLIS))
    }
}
