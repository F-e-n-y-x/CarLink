package com.andrerinas.openheadunit.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class BydClusterOutputTest {
    @Test
    fun plainLettersDropsSpanishDiacritics() {
        assertEquals("Ferreteria", BydClusterOutput.plainLetters("Ferretería"))
        assertEquals("Avenida Espana", BydClusterOutput.plainLetters("Avenida España"))
        assertEquals("AEIOU N u", BydClusterOutput.plainLetters("ÁÉÍÓÚ Ñ ü"))
        assertEquals("Calle 10", BydClusterOutput.plainLetters("Calle 10"))
    }
}
