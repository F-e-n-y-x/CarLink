package com.carlink.app.entry

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.carlink.app.core.PhoneKind
import com.carlink.app.detect.PhoneClassifier
import com.carlink.app.engine.EngineCoordinator

/**
 * CarLink's only USB_DEVICE_ATTACHED handler (both engines' filters are removed from the merged
 * manifest). Accepting the system prompt here grants USB access to the whole package, so both
 * engine processes can open the phone. Peripherals are ignored without showing anything.
 */
class UsbRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        route(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
        finish()
    }

    private fun route(intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = usbDevice(intent) ?: return
        val kind = PhoneClassifier.classifyUsb(describe(device))
        Log.i(TAG, "USB ${"%04x:%04x".format(device.vendorId, device.productId)} -> $kind")
        if (kind == PhoneKind.UNKNOWN) return
        val name = runCatching { device.productName }.getOrNull()
        // The attach intent carries the UsbDevice: DiPlay reads it for a wired iPhone, and the
        // Android Auto engine switches that exact phone to accessory mode.
        EngineCoordinator.get(this).onUsbPhone(kind, name, attachIntent = intent)
    }

    companion object {
        private const val TAG = "CarLink-USB"

        fun usbDevice(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

        fun describe(device: UsbDevice) = PhoneClassifier.UsbDeviceInfo(
            vendorId = device.vendorId,
            productId = device.productId,
            interfaces = (0 until device.interfaceCount).map { index ->
                val usbInterface = device.getInterface(index)
                PhoneClassifier.UsbInterfaceInfo(
                    interfaceClass = usbInterface.interfaceClass,
                    subclass = usbInterface.interfaceSubclass,
                    protocol = usbInterface.interfaceProtocol,
                    hasBulkEndpoint = (0 until usbInterface.endpointCount)
                        .any { usbInterface.getEndpoint(it).type == UsbConstants.USB_ENDPOINT_XFER_BULK },
                )
            },
        )
    }
}
