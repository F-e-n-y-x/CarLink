package com.carlink.app.entry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.carlink.app.core.PhoneKind
import com.carlink.app.detect.PhoneClassifier
import com.carlink.app.engine.EngineCoordinator

/**
 * Finds phones on USB while CarLink is open, without relying on the system's attach dispatch.
 *
 * Android only launches UsbRouterActivity when CarLink wins the USB_DEVICE_ATTACHED resolution.
 * An iPhone also exposes a PTP (photo) interface, so Photos and the MTP host compete for it and
 * the system may show a chooser or pick another app. While CarLink runs, it therefore scans the
 * connected devices and listens for attach/detach itself; each engine then asks for USB access.
 */
class UsbWatcher(context: Context) {
    private val app = context.applicationContext
    private val usb = app.getSystemService(UsbManager::class.java)
    private val handled = mutableSetOf<String>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = UsbRouterActivity.usbDevice(intent) ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> route(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> handled -= device.deviceName
            }
        }
    }

    /** Call from onResume: route phones already plugged in and watch for new ones. */
    fun start() {
        if (!registered) {
            registered = true
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }
        val present = usb?.deviceList?.values.orEmpty()
        handled.retainAll(present.map { it.deviceName }.toSet())
        present.forEach(::route)
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { app.unregisterReceiver(receiver) }
    }

    private fun route(device: UsbDevice) {
        if (!handled.add(device.deviceName)) return
        val kind = PhoneClassifier.classifyUsb(UsbRouterActivity.describe(device))
        Log.i(TAG, "USB ${"%04x:%04x".format(device.vendorId, device.productId)} -> $kind")
        if (kind == PhoneKind.UNKNOWN) return
        val attach = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        EngineCoordinator.get(app).onUsbPhone(kind, runCatching { device.productName }.getOrNull(), attach)
    }

    private companion object {
        const val TAG = "CarLink-USB"
    }
}
