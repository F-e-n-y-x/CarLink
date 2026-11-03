package com.carlink.app.ui.home

import com.carlink.app.gallery.HomeFakes
import com.carlink.app.ui.offersAndroidAutoOnScreen
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home offers "Run Android Auto on this screen" only with the switch on, Android Auto installed, and Home idle. */
class AndroidAutoOnScreenTest {
    @Test fun offeredOnlyWhenSwitchedOnInstalledAndIdle() {
        val on = HomeFakes.prefs.copy(androidAutoOnScreen = true, androidAutoInstalled = true, androidAutoCanRunCar = true)
        val offeredIn = HomeMode.entries.filter { on.offersAndroidAutoOnScreen(it.isIdle()) }.toSet()
        assertEquals(setOf(HomeMode.READY_EMPTY, HomeMode.READY_PHONE, HomeMode.CABLE_ONLY, HomeMode.BLUETOOTH_OFF), offeredIn)
        assertEquals(false, on.copy(androidAutoOnScreen = false).offersAndroidAutoOnScreen(true))
        assertEquals(false, on.copy(androidAutoInstalled = false).offersAndroidAutoOnScreen(true))
        // Installed but Google switched its car mode off here (a Samsung tablet): no button that can only fail.
        assertEquals(false, on.copy(androidAutoCanRunCar = false).offersAndroidAutoOnScreen(true))
    }
}
