package com.carlink.core.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class QzdHeadUnitTest {
    @Test fun streamsFollowTheUnitsZLinkSettings() {
        // QCM6125 factory values (joying-qcm6125.md §4): calls on stream 2, where 0 is the ring.
        val props = mapOf("rw.zlink.audiostream.tts" to "4", "rw.zlink.audiostream.speech" to "7",
            "rw.zlink.audiostream.phonecall" to "2")
        assertEquals(mapOf("NAVIGATION" to 4, "ASSISTANT" to 7, "PHONE" to 2), QzdHeadUnit.audioStreams(props::get))
    }

    @Test fun withoutTheSettingsCallsKeepTheirOwnStream() {
        assertEquals(mapOf("NAVIGATION" to 4, "ASSISTANT" to 7), QzdHeadUnit.audioStreams { null })
        // 0 or junk is no stream choice.
        assertEquals(mapOf("NAVIGATION" to 4, "ASSISTANT" to 7),
            QzdHeadUnit.audioStreams { if (it.endsWith("phonecall")) "0" else "x" })
    }
}
