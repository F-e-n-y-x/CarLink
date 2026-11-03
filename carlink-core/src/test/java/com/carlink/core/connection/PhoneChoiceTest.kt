package com.carlink.core.connection

import com.carlink.core.connection.PhoneChoice.Decision
import com.carlink.core.core.PhoneKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneChoiceTest {
    private val iphone = PhoneOption("AA:00", "iPhone", PhoneKind.IPHONE, present = false)
    private val pixel = PhoneOption("BB:00", "Pixel", PhoneKind.ANDROID, present = false)

    @Test fun bothKindsInReachAsks() {
        val both = listOf(iphone.copy(present = true), pixel.copy(present = true))
        assertEquals(Decision.Ask(both), PhoneChoice.decide(both, lastPhone = "BB:00"))
    }

    @Test fun theOnePhoneInReachStartsEvenIfNotTheLast() {
        val opts = listOf(iphone.copy(present = true), pixel)
        assertEquals(Decision.Start(opts[0]), PhoneChoice.decide(opts, lastPhone = "BB:00"))
    }

    @Test fun nobodyInReachFallsBackToTheLastPhone() {
        assertEquals(Decision.Start(pixel), PhoneChoice.decide(listOf(iphone, pixel), "bb:00"))
        assertEquals(Decision.Nothing, PhoneChoice.decide(listOf(iphone, pixel), null))
    }

    @Test fun onlyTheConflictingPhonesAreListed() {
        val watchingIphone = iphone.copy(present = true)
        val presentPixel = pixel.copy(present = true)
        val absent = PhoneOption("CC:00", "Old Pixel", PhoneKind.ANDROID, present = false)
        assertEquals(Decision.Ask(listOf(watchingIphone, presentPixel)),
            PhoneChoice.decide(listOf(watchingIphone, absent, presentPixel), "CC:00"))
        // Two Android phones in reach are one engine: no question, the last (or first) one starts.
        val pixel2 = pixel.copy(address = "CC:00", present = true)
        assertEquals(Decision.Start(pixel2), PhoneChoice.decide(listOf(presentPixel, pixel2), "CC:00"))
    }

    @Test fun unknownPhonesAreIgnored() {
        val unknown = PhoneOption("DD:00", "Watch", PhoneKind.UNKNOWN, present = true)
        assertEquals(Decision.Start(pixel), PhoneChoice.decide(listOf(unknown, pixel), "BB:00"))
    }
}
