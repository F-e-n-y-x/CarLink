package com.andrerinas.openheadunit.hud

import org.junit.Assert.*
import org.junit.Test

class StandaloneHudPacketsTest {
    @Test fun stateMatchesHalCharEncoding() {
        assertEquals("43,E0,00,3A,01,02", StandaloneHudPackets.start())
        assertEquals("43,E0,00,3A,01,01", StandaloneHudPackets.clear())
    }
    @Test fun left500MatchesHalIntArrayEncoding() {
        assertEquals("43,F0,10,18,04,00,00,01,F4,43,F0,10,10,04,00,00,00,01,43,F0,10,30,04,00,00,00,01",
            StandaloneHudPackets.guidance(1, 500))
    }
    @Test fun right800MatchesHalIntArrayEncoding() {
        assertEquals("43,F0,10,18,04,00,00,03,20,43,F0,10,10,04,00,00,00,02,43,F0,10,30,04,00,00,00,02",
            StandaloneHudPackets.guidance(2, 800))
    }
    @Test fun rejectsOtherTurnsAndInvalidDistanceBeforeSending() {
        for (turn in listOf(Int.MIN_VALUE, -1, 0, 3, 4, 49, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { StandaloneHudPackets.guidance(turn, 500) }
        }
        for (distance in listOf(Int.MIN_VALUE, -1, 10000, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { StandaloneHudPackets.guidance(1, distance) }
        }
    }
}
