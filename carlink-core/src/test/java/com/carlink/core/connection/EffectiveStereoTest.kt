package com.carlink.core.connection

import com.carlink.core.detect.StereoBluetooth
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's Bluetooth chooser: picking this screen's own Bluetooth hides the module from every path. */
class EffectiveStereoTest {
    @Test fun androidBluetoothIgnoresTheModule() {
        assertEquals(StereoBluetooth.NONE, DeviceEnvironment.effective(true, StereoBluetooth.ZLINK_DAEMON))
        assertEquals(StereoBluetooth.ZLINK_DAEMON, DeviceEnvironment.effective(false, StereoBluetooth.ZLINK_DAEMON))
    }
}
