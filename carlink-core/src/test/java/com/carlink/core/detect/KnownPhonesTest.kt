package com.carlink.core.detect

import android.app.Application
import com.carlink.core.core.PhoneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class KnownPhonesTest {
    private val phones get() = KnownPhones(RuntimeEnvironment.getApplication())

    @Test fun guessedAndroidTypesAreDroppedOnceAndProvenOnesKept() { // module stereo, 2026-11-08
        phones.remember("AA:AA:AA:AA:AA:01", PhoneKind.ANDROID) // an iPhone guessed Android, never connected
        phones.remember("AA:AA:AA:AA:AA:02", PhoneKind.ANDROID) // Android Auto connected wirelessly
        phones.remember("AA:AA:AA:AA:AA:03", PhoneKind.IPHONE)

        val dropped = phones.dropUnprovenAndroid { it == "AA:AA:AA:AA:AA:02" }

        assertEquals(listOf("AA:AA:AA:AA:AA:01"), dropped)
        assertNull(phones.kindOf("AA:AA:AA:AA:AA:01"))
        assertEquals(PhoneKind.ANDROID, phones.kindOf("AA:AA:AA:AA:AA:02"))
        assertEquals(PhoneKind.IPHONE, phones.kindOf("AA:AA:AA:AA:AA:03"))

        // Once only: a type the driver sets afterwards stays.
        phones.remember("AA:AA:AA:AA:AA:01", PhoneKind.ANDROID)
        assertEquals(emptyList<String>(), phones.dropUnprovenAndroid { false })
        assertEquals(PhoneKind.ANDROID, phones.kindOf("AA:AA:AA:AA:AA:01"))
    }

    @Test fun androidAutosAutoStartListIsNotProofOfAnAndroidPhone() { // CarLink writes every attempt there
        val app = RuntimeEnvironment.getApplication()
        com.andrerinas.openheadunit.utils.Settings.syncAutoStartBtMacsToDeviceStorage(app, setOf("AA:AA:AA:AA:AA:09"))
        assertEquals(setOf("AA:AA:AA:AA:AA:09"), com.andrerinas.openheadunit.utils.Settings.getAutoStartBtMacs(app))
        assertNull(phones.kindOf("AA:AA:AA:AA:AA:09"))
        assertEquals(null, phones.all()["AA:AA:AA:AA:AA:09"])
    }
}
