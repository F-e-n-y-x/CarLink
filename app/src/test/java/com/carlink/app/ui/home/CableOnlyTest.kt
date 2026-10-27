package com.carlink.app.ui.home

import com.carlink.app.gallery.HomeFakes
import com.carlink.core.core.LinkStage
import com.carlink.core.core.LinkState
import com.carlink.core.core.Engine
import com.carlink.core.core.Transport
import com.carlink.core.detect.BluetoothAvailability
import com.carlink.core.detect.StereoBluetooth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #6: idle Home on a stereo whose Bluetooth is an external module offers the cable, not a Connect that can only fail. */
class CableOnlyTest {
    private fun mode(ui: HomeUi) = homeMode(ui.home, ui.permissionsNeeded, ui.cableOnly)

    @Test fun externalModuleWithIphoneIsCableOnly() {
        val ui = HomeFakes.ui(stereo = StereoBluetooth.EXTERNAL_UNKNOWN)
        assertEquals(HomeMode.CABLE_ONLY, mode(ui))
        // Even with Android's Bluetooth off or no phone known: neither Bluetooth advice nor pairing helps.
        assertEquals(HomeMode.CABLE_ONLY, mode(HomeFakes.ui(stereo = StereoBluetooth.FYT_MODULE, bluetooth = BluetoothAvailability.OFF)))
        assertEquals(HomeMode.CABLE_ONLY, mode(HomeFakes.ui(stereo = StereoBluetooth.ZLINK_DAEMON, phones = emptyList())))
    }

    @Test fun moduleSettingUnblocksAndroidAutoOnly() {
        val on = HomeFakes.prefs.copy(wirelessOnExternalBluetooth = true)
        assertTrue(HomeFakes.ui(stereo = StereoBluetooth.EXTERNAL_UNKNOWN).copy(connection = on).cableOnly)
        assertFalse(HomeFakes.ui(stereo = StereoBluetooth.EXTERNAL_UNKNOWN, phones = listOf(HomeFakes.pixel)).copy(connection = on).cableOnly)
        // No phone known: wireless Android Auto is possible, so Home does not say "cable only".
        assertFalse(HomeFakes.ui(stereo = StereoBluetooth.EXTERNAL_UNKNOWN, phones = emptyList()).copy(connection = on).cableOnly)
    }

    @Test fun ordinaryStereoAndSessionsAreUnchanged() {
        assertEquals(HomeMode.READY_PHONE, mode(HomeFakes.ui(stereo = StereoBluetooth.NONE)))
        assertEquals(HomeMode.READY_PHONE, mode(HomeFakes.ui(stereo = StereoBluetooth.LIKELY_FALSE_POSITIVE)))
        assertEquals(HomeMode.READY_PHONE, mode(HomeFakes.ui(stereo = null)))
        val usb = LinkState(LinkStage.CONNECTING, Engine.CARPLAY, Transport.USB, "iPhone", 2, 6)
        assertEquals(HomeMode.CONNECTING, mode(HomeFakes.ui(link = usb, stereo = StereoBluetooth.EXTERNAL_UNKNOWN)))
        assertEquals(HomeMode.PERMISSION, mode(HomeFakes.ui(permissionsNeeded = true, stereo = StereoBluetooth.EXTERNAL_UNKNOWN)))
    }
}
