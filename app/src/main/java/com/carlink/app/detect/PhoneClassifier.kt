package com.carlink.app.detect

import com.carlink.app.core.PhoneKind
import java.util.Locale
import java.util.UUID

/**
 * Decides whether a USB or Bluetooth device is an iPhone (CarPlay) or an Android phone
 * (Android Auto). Pure functions over plain data so every rule is unit tested.
 */
object PhoneClassifier {
    const val APPLE_VENDOR_ID = 0x05AC
    const val GOOGLE_VENDOR_ID = 0x18D1

    /** Product IDs a phone uses after switching to Android Open Accessory mode (AOA 1.0/2.0). */
    private val AOA_PRODUCT_IDS = 0x2D00..0x2D05

    /** iAP2 over Bluetooth: the RFCOMM service DiPlay connects to on a paired iPhone. */
    val IAP2_BLUETOOTH_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")

    /** Android Auto wireless setup service. */
    val ANDROID_AUTO_BLUETOOTH_UUID: UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")

    /** Bluetooth major device class for phones (BluetoothClass.Device.Major.PHONE). */
    const val BLUETOOTH_MAJOR_PHONE = 0x0200

    data class UsbInterfaceInfo(
        val interfaceClass: Int,
        val subclass: Int,
        val protocol: Int,
        val hasBulkEndpoint: Boolean = true,
    )

    data class UsbDeviceInfo(
        val vendorId: Int,
        val productId: Int,
        val interfaces: List<UsbInterfaceInfo>,
    )

    data class BluetoothDeviceInfo(
        val address: String,
        val name: String?,
        /** Service UUIDs from the last SDP query; empty when the stack has none cached. */
        val uuids: Set<UUID>,
        /** BluetoothClass major device class, or null when unknown. */
        val majorClass: Int?,
    )

    /**
     * Any Apple device goes to CarPlay, as in DiPlay. Otherwise the device must look like an
     * Android phone: already in accessory mode, or exposing one of the interfaces DiAuto's
     * UsbDeviceCompat accepts (AOA, MTP, ADB, RNDIS, IAD, PTP). Flash drives, the CH341 MFi
     * bridge and other peripherals stay UNKNOWN and are ignored.
     */
    fun classifyUsb(device: UsbDeviceInfo): PhoneKind = when {
        device.vendorId == APPLE_VENDOR_ID -> PhoneKind.IPHONE
        isAccessoryMode(device) -> PhoneKind.ANDROID
        device.interfaces.any(::isAndroidInterface) -> PhoneKind.ANDROID
        // A bare vendor-specific bulk interface is what some phones show in charging-only mode,
        // but USB Ethernet adapters (Realtek r8152 in a USB-C dock) look the same, so it only
        // counts for phone makers (found on the user's tablet, 2026-10-09).
        device.vendorId in ANDROID_PHONE_VENDOR_IDS && device.interfaces.any(::isVendorBulk) -> PhoneKind.ANDROID
        else -> PhoneKind.UNKNOWN
    }

    private fun isVendorBulk(i: UsbInterfaceInfo) =
        i.interfaceClass == 0xFF && i.subclass == 0xFF && i.protocol == 0x00 && i.hasBulkEndpoint

    /** Phone makers whose devices may show only a vendor-specific interface (keep in step with xml/carlink_usb_device_filter). */
    private val ANDROID_PHONE_VENDOR_IDS = setOf(
        0x04E8, // Samsung
        0x18D1, // Google
        0x2717, // Xiaomi / Redmi / POCO
        0x2A70, // OnePlus
        0x22D9, // OPPO / realme
        0x2D95, // vivo
        0x22B8, // Motorola
        0x0FCE, // Sony
        0x12D1, // Huawei / Honor
    )

    fun isAccessoryMode(device: UsbDeviceInfo): Boolean =
        device.vendorId == GOOGLE_VENDOR_ID && device.productId in AOA_PRODUCT_IDS

    private fun isAndroidInterface(i: UsbInterfaceInfo): Boolean = when {
        i.interfaceClass == 0xFF && i.subclass == 0x42 && i.protocol == 0x01 -> true // ADB
        i.interfaceClass == 0xE0 && i.subclass == 0x01 && i.protocol == 0x03 -> true // RNDIS
        i.interfaceClass == 0xEF && i.subclass == 0x04 && i.protocol == 0x01 -> true // IAD
        i.interfaceClass == 0x06 && i.subclass == 0x01 && i.protocol == 0x01 -> true // PTP
        else -> false
    }

    /**
     * Strongest evidence first:
     * 1. a phone the driver or a previous session already identified ([remembered]);
     * 2. the iAP2 service UUID, which only Apple devices publish;
     * 3. an "iPhone"/"iPad" device name (the default name, and DiPlay's own picker rule);
     * 4. an SDP record set from a phone that lacks iAP2, or the Android Auto service UUID.
     * A phone with no cached SDP record and a custom name stays UNKNOWN until SDP completes.
     */
    fun classifyBluetooth(device: BluetoothDeviceInfo, remembered: PhoneKind?): PhoneKind {
        if (remembered != null && remembered != PhoneKind.UNKNOWN) return remembered
        if (IAP2_BLUETOOTH_UUID in device.uuids) return PhoneKind.IPHONE
        val name = device.name.orEmpty().lowercase(Locale.ROOT)
        if ("iphone" in name || "ipad" in name) return PhoneKind.IPHONE
        if (ANDROID_AUTO_BLUETOOTH_UUID in device.uuids) return PhoneKind.ANDROID
        val isPhone = device.majorClass == null || device.majorClass == BLUETOOTH_MAJOR_PHONE
        if (isPhone && device.uuids.isNotEmpty()) return PhoneKind.ANDROID
        return PhoneKind.UNKNOWN
    }
}
