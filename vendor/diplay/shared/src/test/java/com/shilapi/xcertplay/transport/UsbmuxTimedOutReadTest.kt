package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * A USBMUX read that completes between its timeout and the cancel ended the whole session every
 * few minutes on a Telechips head unit (#610). Upstream drains the cancelled read and keeps its
 * bytes. CarLink: the USBMUX read request is persistent (IphoneUsbHost.kt, docs/VENDOR_CHANGES.md),
 * so a timed-out read is never cancelled; it stays queued and its bytes arrive with the next read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [CompatibilityUsbConnectionShadow::class, CompatibilityUsbRequestShadow::class])
class UsbmuxTimedOutReadTest {
    @Before fun reset() { UsbQueueReplay.reset() }

    @Test fun aReadThatCompletesAfterItsTimeoutKeepsTheSessionAndItsBytes() {
        UsbQueueReplay.timeouts = 1
        UsbQueueReplay.cancelResult = false
        val pipe = pipe()
        try {
            assertNull(pipe.read(100))
            // The same request, still queued, carries the bytes; nothing was cancelled.
            assertArrayEquals(UsbReadQueueCompatibilityTest.payload, pipe.read(100))
            assertEquals(1, UsbQueueReplay.sizes.size)
            assertEquals(0, UsbQueueReplay.cancels)
            // The pipe stays usable afterwards.
            assertArrayEquals(UsbReadQueueCompatibilityTest.payload, pipe.read(100))
        } finally { pipe.close() }
    }

    @Test fun aTimeoutIsAnOrdinaryTimeoutAndCancelsNothing() {
        UsbQueueReplay.timeouts = 2
        val pipe = pipe()
        try {
            assertNull(pipe.read(100))
            assertNull(pipe.read(100))
            assertEquals(0, UsbQueueReplay.cancels)
        } finally { pipe.close() }
        assertTrue(UsbQueueReplay.cancels >= 1)
    }

    private fun pipe() = Iap2UsbSession(connection(), endpoint(0x04), endpoint(0x85))
    private fun connection(): UsbDeviceConnection = ReflectionHelpers.callConstructor(
        UsbDeviceConnection::class.java, ClassParameter.from(UsbDevice::class.java, null))
    private fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
        UsbEndpoint::class.java, ClassParameter.from(Int::class.javaPrimitiveType, address),
        ClassParameter.from(Int::class.javaPrimitiveType, 2),
        ClassParameter.from(Int::class.javaPrimitiveType, 512),
        ClassParameter.from(Int::class.javaPrimitiveType, 0))
}
