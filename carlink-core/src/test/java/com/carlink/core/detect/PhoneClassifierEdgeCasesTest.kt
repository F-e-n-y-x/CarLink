package com.carlink.core.detect

import com.carlink.core.core.PhoneKind
import com.carlink.core.detect.PhoneClassifier.BluetoothDeviceInfo
import com.carlink.core.detect.PhoneClassifier.UsbDeviceInfo
import com.carlink.core.detect.PhoneClassifier.UsbInterfaceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Edge cases of [PhoneClassifier] beyond [PhoneClassifierTest]. */
class PhoneClassifierEdgeCasesTest {
    private val handsFree = UUID.fromString("0000111f-0000-1000-8000-00805f9b34fb")
    private val a2dpSink = UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb")
    private val mtp = UsbInterfaceInfo(0x06, 0x01, 0x01)

    private fun usb(vendor: Int, product: Int, vararg interfaces: UsbInterfaceInfo) =
        UsbDeviceInfo(vendor, product, interfaces.toList())

    private fun bt(name: String?, uuids: Set<UUID> = emptySet(), major: Int? = PhoneClassifier.BLUETOOTH_MAJOR_PHONE) =
        BluetoothDeviceInfo("AA:BB:CC:DD:EE:FF", name, uuids, major)

    @Test fun appleDeviceWithNoInterfacesIsStillAnIphone() {
        // Before configuration (or mid re-enumeration) an iPhone may list no interfaces at all.
        assertEquals(PhoneKind.IPHONE, PhoneClassifier.classifyUsb(usb(PhoneClassifier.APPLE_VENDOR_ID, 0x12A8)))
        assertEquals(PhoneKind.IPHONE, PhoneClassifier.classifyUsb(usb(0x05AC, 0x0000)))
    }

    @Test fun aoaProductRangeIsExactly2D00To2D05() {
        (0x2D00..0x2D05).forEach { product ->
            val device = usb(PhoneClassifier.GOOGLE_VENDOR_ID, product)
            assertTrue("%04X".format(product), PhoneClassifier.isAccessoryMode(device))
            assertEquals("%04X".format(product), PhoneKind.ANDROID, PhoneClassifier.classifyUsb(device))
        }
        val beyond = usb(PhoneClassifier.GOOGLE_VENDOR_ID, 0x2D06)
        assertFalse(PhoneClassifier.isAccessoryMode(beyond))
        assertEquals(PhoneKind.UNKNOWN, PhoneClassifier.classifyUsb(beyond))
        assertFalse(PhoneClassifier.isAccessoryMode(usb(PhoneClassifier.GOOGLE_VENDOR_ID, 0x2CFF)))
        // A Google device outside the AOA range is still Android when it shows phone interfaces.
        assertEquals(PhoneKind.ANDROID, PhoneClassifier.classifyUsb(usb(PhoneClassifier.GOOGLE_VENDOR_ID, 0x4EE1, mtp)))
    }

    @Test fun aoaProductIdOnlyCountsForGoogleVendor() {
        assertFalse(PhoneClassifier.isAccessoryMode(usb(0x04E8, 0x2D01)))
        assertEquals(PhoneKind.UNKNOWN, PhoneClassifier.classifyUsb(usb(0x04E8, 0x2D01)))
    }

    @Test fun nonAppleDeviceWithNoInterfacesIsIgnored() {
        assertEquals(PhoneKind.UNKNOWN, PhoneClassifier.classifyUsb(usb(0x04E8, 0x6860)))
    }

    @Test fun nearMissInterfacesAreNotAndroid() {
        listOf(
            UsbInterfaceInfo(0x08, 0x06, 0x50), // mass storage (bulk-only), not MTP
            UsbInterfaceInfo(0xFF, 0x42, 0x02), // not the ADB protocol
            UsbInterfaceInfo(0x06, 0x01, 0x02), // not PTP's protocol
            UsbInterfaceInfo(0x03, 0x01, 0x01), // HID keyboard
            UsbInterfaceInfo(0x01, 0x01, 0x00), // audio
        ).forEach { assertEquals("$it", PhoneKind.UNKNOWN, PhoneClassifier.classifyUsb(usb(0x1234, 0x5678, it))) }
    }

    @Test fun oneAndroidInterfaceAmongOthersIsEnough() {
        val hid = UsbInterfaceInfo(0x03, 0x01, 0x01)
        assertEquals(PhoneKind.ANDROID, PhoneClassifier.classifyUsb(usb(0x2717, 0xFF48, hid, mtp)))
    }

    @Test fun iphoneNameVariants() {
        listOf("Sara’s iPhone 15 Pro", "Sara's iPhone", "IPHONE", "iphone", "My iPhone-SE", "IPAD", "Kids iPad Air", "iPad")
            .forEach { assertEquals(it, PhoneKind.IPHONE, PhoneClassifier.classifyBluetooth(bt(it), null)) }
    }

    @Test fun lookalikeNamesAreNotIphones() {
        listOf("Sara’s Apple Watch", "Sara’s AirPods Pro", "Galaxy S24", "i Phone", "").forEach {
            assertEquals(it, PhoneKind.UNKNOWN, PhoneClassifier.classifyBluetooth(bt(it), null))
        }
        assertEquals(PhoneKind.UNKNOWN, PhoneClassifier.classifyBluetooth(bt(null), null))
    }

    @Test fun wearablesAndHeadphonesWithServicesAreNotAndroidAuto() {
        val wearable = 0x0700
        val audioVideo = 0x0400
        val computer = 0x0100
        listOf(wearable, audioVideo, computer).forEach { major ->
            val device = bt("Galaxy Watch", setOf(handsFree, a2dpSink), major)
            assertEquals("major=%04X".format(major), PhoneKind.UNKNOWN, PhoneClassifier.classifyBluetooth(device, null))
        }
    }

    @Test fun iap2OrAndroidAutoServiceWinsOverDeviceClass() {
        val wearable = 0x0700
        assertEquals(PhoneKind.IPHONE,
            PhoneClassifier.classifyBluetooth(bt("Watch", setOf(PhoneClassifier.IAP2_BLUETOOTH_UUID), wearable), null))
        assertEquals(PhoneKind.ANDROID,
            PhoneClassifier.classifyBluetooth(bt("Car phone", setOf(PhoneClassifier.ANDROID_AUTO_BLUETOOTH_UUID), wearable), null))
    }

    @Test fun iap2BeatsAnAndroidAutoServiceAndAnAndroidName() {
        val both = setOf(PhoneClassifier.IAP2_BLUETOOTH_UUID, PhoneClassifier.ANDROID_AUTO_BLUETOOTH_UUID)
        assertEquals(PhoneKind.IPHONE, PhoneClassifier.classifyBluetooth(bt("Pixel 9", both), null))
    }

    @Test fun phoneClassWithoutSdpRecordsWaits() {
        assertEquals(PhoneKind.UNKNOWN, PhoneClassifier.classifyBluetooth(bt("Pixel 9", emptySet()), null))
    }

    @Test fun usbCDockPartsAreNotPhones() {
        // The user's USB-C dock, descriptors read on the tablet (2026-10-09).
        val ethernet = PhoneClassifier.UsbDeviceInfo(0x0BDA, 0x8153, listOf(
            PhoneClassifier.UsbInterfaceInfo(0xFF, 0xFF, 0x00), PhoneClassifier.UsbInterfaceInfo(0x02, 0x06, 0x00),
            PhoneClassifier.UsbInterfaceInfo(0x0A, 0x00, 0x00)))
        val cardReader = PhoneClassifier.UsbDeviceInfo(0x05E3, 0x0749, listOf(PhoneClassifier.UsbInterfaceInfo(0x08, 0x06, 0x50)))
        val audio = PhoneClassifier.UsbDeviceInfo(0x1B3F, 0x2008, listOf(
            PhoneClassifier.UsbInterfaceInfo(0x01, 0x01, 0x00), PhoneClassifier.UsbInterfaceInfo(0x03, 0x00, 0x00)))
        listOf(ethernet, cardReader, audio).forEach { assertEquals("$it", PhoneKind.UNKNOWN, PhoneClassifier.classifyUsb(it)) }
    }

    @Test fun phoneMakersVendorInterfaceStillCounts() {
        val samsungChargingOnly = PhoneClassifier.UsbDeviceInfo(0x04E8, 0x6860, listOf(PhoneClassifier.UsbInterfaceInfo(0xFF, 0xFF, 0x00)))
        assertEquals(PhoneKind.ANDROID, PhoneClassifier.classifyUsb(samsungChargingOnly))
    }

    @Test fun anIpadOrIpodIsNotACarPlayPhone() {
        assertEquals(PhoneKind.UNKNOWN, PhoneClassifier.classifyUsb(UsbDeviceInfo(0x05AC, 0x12AB, emptyList(), "iPad")))
        assertEquals(PhoneKind.UNKNOWN, PhoneClassifier.classifyUsb(UsbDeviceInfo(0x05AC, 0x12AB, emptyList())))
        assertEquals(PhoneKind.UNKNOWN, PhoneClassifier.classifyUsb(UsbDeviceInfo(0x05AC, 0x12AA, emptyList(), "iPod")))
        assertEquals(PhoneKind.IPHONE, PhoneClassifier.classifyUsb(UsbDeviceInfo(0x05AC, 0x12A8, emptyList(), "iPhone")))
    }
}
