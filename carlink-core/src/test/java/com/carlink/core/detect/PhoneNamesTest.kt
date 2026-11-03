package com.carlink.core.detect

import com.carlink.core.core.PhoneKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNamesTest {
    @Test fun phonesWithTheSameNameGetTheirAddressEnding() {
        val list = PhonesRepository.distinguishSameNames(listOf(
            PairedPhone("A8:4A:28:1E:FA:43", "iPhone", PhoneKind.IPHONE, false),
            PairedPhone("28:FF:3C:14:84:19", "iPhone", PhoneKind.IPHONE, false),
            PairedPhone("5C:51:36:59:85:86", "Ayush's S25 Ultra", PhoneKind.ANDROID, false),
        ))
        assertEquals(listOf("iPhone · …FA:43", "iPhone · …84:19", "Ayush's S25 Ultra"), list.map { it.name })
    }
}
