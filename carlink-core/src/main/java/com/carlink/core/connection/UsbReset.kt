package com.carlink.core.connection

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.andrerinas.openheadunit.connection.UsbNative

/**
 * A USB port reset: the phone sees an unplug and re-enumerates, ending its old session. Used by
 * Android Auto (`AaEngineAccess.resetUsb`, `:aa`) and wired CarPlay (DiPlay's
 * `CarPlayController.resetWiredUsb`, main process).
 */
internal object UsbReset {
    // USBDEVFS_RESET through libusb on our own fd; SELinux lets apps ioctl a usb_device they opened
    // (open-headunit eafe3261 recovers a stale accessory the same way).
    fun reset(context: Context, device: UsbDevice): Boolean {
        val connection = context.getSystemService(UsbManager::class.java)?.openDevice(device) ?: return false
        return try {
            val native = UsbNative()
            if (!native.wrap(connection, 0, 0)) return false
            native.reset()
            native.close()
            true
        } catch (e: Throwable) {
            Log.w("CarLink-UsbReset", "USB reset threw ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            connection.close()
        }
    }
}
