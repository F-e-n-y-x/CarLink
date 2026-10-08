package com.carlink.app.detect

import com.carlink.app.core.Engine
import com.carlink.app.core.PhoneKind
import com.carlink.app.detect.PhoneClassifier.BluetoothDeviceInfo
import com.carlink.app.detect.PhoneClassifier.UsbDeviceInfo
import com.carlink.app.detect.PhoneClassifier.UsbInterfaceInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class PhoneClassifierTest {
    private val handsFree = UUID.fromString("0000111f-0000-1000-8000-00805f9b34fb")
    private val a2dpSource = UUID.fromString("0000110a-0000-1000-8000-00805f9b34fb")

    private fun usb(vendor: Int, product: Int = 0x1234, vararg interfaces: UsbInterfaceInfo) =
        UsbDeviceInfo(vendor, product, interfaces.toList())

    private fun bt(name: String?, uuids: Set<UUID> = emptySet(), major: Int? = PhoneClassifier.BLUETOOTH_MAJOR_PHONE) =
        BluetoothDeviceInfo("AA:BB:CC:DD:EE:FF", name, uuids, major)

    @Test fun anyAppleUsbDeviceIsCarPlay() {
        assertEquals(PhoneKind.IPHONE, PhoneClassifier.classifyUsb(usb(0x05AC, 0x12A8)))
        assertEquals(Engine.CARPLAY, PhoneClassifier.classifyUsb(usb(0x05AC)).engine)
    }

    @Test fun appleWinsEvenWithAndroidLikeInterfaces() {
        // iPhones expose PTP; that must not route them to Android Auto.
        val ptp = UsbInterfaceInfo(0x06, 0x01, 0x01)
        assertEquals(PhoneKind.IPHONE, PhoneClassifier.classifyUsb(usb(0x05AC, 0x12A8, ptp)))
    }

    @Test fun accessoryModePhoneIsAndroidAuto() {
        assertEquals(PhoneKind.ANDROID, PhoneClassifier.classifyUsb(usb(0x18D1, 0x2D00)))
        assertEquals(PhoneKind.ANDROID, PhoneClassifier.classifyUsb(usb(0x18D1, 0x2D01)))
    }

    @Test fun androidPhoneInterfacesAreRecognised() {
        val samsung = 0x04E8
        listOf(
            UsbInterfaceInfo(0x08, 0x06, 0x01), // MTP
            UsbInterfaceInfo(0xFF, 0x42, 0x01), // ADB
            UsbInterfaceInfo(0xE0, 0x01, 0x03), // RNDIS
            UsbInterfaceInfo(0xEF, 0x04, 0x01), // IAD
            UsbInterfaceInfo(0x06, 0x01, 0x01), // PTP
            UsbInterfaceInfo(0xFF, 0xFF, 0x00), // AOA vendor interface with bulk endpoints
        ).forEach { iface ->
            assertEquals("$iface", PhoneKind.ANDROID, PhoneClassifier.classifyUsb(usb(samsung, 0x6860, iface)))
        }
    }

    @Test fun peripheralsAreIgnored() {
        val massStorage = UsbInterfaceInfo(0x08, 0x06, 0x50)
        assertEquals(PhoneKind.UNKNOWN, PhoneClassifier.classifyUsb(usb(0x0781, 0x5581, massStorage)))
        // CH341 USB-I2C bridge used for an external MFi coprocessor.
        assertEquals(PhoneKind.UNKNOWN, PhoneClassifier.classifyUsb(usb(0x1A86, 0x5512, UsbInterfaceInfo(0xFF, 0x01, 0x02))))
        // A vendor interface without bulk endpoints is not an accessory channel.
        assertEquals(PhoneKind.UNKNOWN,
            PhoneClassifier.classifyUsb(usb(0x2717, 0xFF48, UsbInterfaceInfo(0xFF, 0xFF, 0x00, hasBulkEndpoint = false))))
    }

    @Test fun rememberedKindWinsOverEverything() {
        val renamedIphone = bt("Car phone", setOf(handsFree))
        assertEquals(PhoneKind.IPHONE, PhoneClassifier.classifyBluetooth(renamedIphone, PhoneKind.IPHONE))
        assertEquals(PhoneKind.ANDROID,
            PhoneClassifier.classifyBluetooth(bt("iPhone of Sam", emptySet()), PhoneKind.ANDROID))
    }

    @Test fun unknownMemoryIsIgnored() {
        assertEquals(PhoneKind.IPHONE, PhoneClassifier.classifyBluetooth(bt("iPhone"), PhoneKind.UNKNOWN))
    }

    @Test fun iap2ServiceMeansIphoneWhateverTheName() {
        val info = bt("Work phone", setOf(handsFree, PhoneClassifier.IAP2_BLUETOOTH_UUID))
        assertEquals(PhoneKind.IPHONE, PhoneClassifier.classifyBluetooth(info, null))
    }

    @Test fun iphoneNameWithoutSdpCacheIsIphone() {
        assertEquals(PhoneKind.IPHONE, PhoneClassifier.classifyBluetooth(bt("Alex’s iPhone"), null))
        assertEquals(PhoneKind.IPHONE, PhoneClassifier.classifyBluetooth(bt("IPAD"), null))
    }

    @Test fun phoneWithSdpRecordsButNoIap2IsAndroid() {
        assertEquals(PhoneKind.ANDROID, PhoneClassifier.classifyBluetooth(bt("Pixel 9", setOf(handsFree, a2dpSource)), null))
        assertEquals(PhoneKind.ANDROID,
            PhoneClassifier.classifyBluetooth(bt(null, setOf(PhoneClassifier.ANDROID_AUTO_BLUETOOTH_UUID), major = null), null))
    }

    @Test fun customNamedPhoneWithoutSdpCacheWaits() {
        assertEquals(PhoneKind.UNKNOWN, PhoneClassifier.classifyBluetooth(bt("Galaxy"), null))
    }

    @Test fun nonPhonesAreNotAndroidAuto() {
        val audioVideo = 0x0400
        val headphones = bt("Car kit", setOf(handsFree, a2dpSource), major = audioVideo)
        assertEquals(PhoneKind.UNKNOWN, PhoneClassifier.classifyBluetooth(headphones, null))
    }
}
