package com.carlink.app.engine

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.UsbAccessoryMode
import com.andrerinas.openheadunit.connection.UsbDeviceCompat
import com.carlink.app.engine.AndroidAutoProtocol as P

/**
 * Runs in the ":aa" process. Turns CarLink's engine commands into the AapService intents
 * DiAuto's own entry points send, reading DiAuto's settings in the process that owns them.
 */
class AndroidAutoCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != P.ACTION_COMMAND) return
        val command = intent.getStringExtra(P.EXTRA_COMMAND) ?: return
        Log.i(TAG, "command=$command")
        when (command) {
            P.COMMAND_START_USB -> startUsb(context, usbDevice(intent))
            P.COMMAND_START_WIRELESS -> {
                val pending = goAsync()
                Thread({
                    try {
                        startWireless(context, intent.getStringExtra(P.EXTRA_ADDRESS))
                    } finally {
                        pending.finish()
                    }
                }, "carlink-aa-wireless").start()
            }
            P.COMMAND_DISCONNECT -> if (isServiceRunning(context)) startService(context, AapService.ACTION_DISCONNECT)
            // Sending STOP_SERVICE to a stopped AapService would first run its onCreate, which
            // enables car mode and opens the wireless stack.
            P.COMMAND_STOP -> if (isServiceRunning(context)) startService(context, AapService.ACTION_STOP_SERVICE)
            P.COMMAND_REPORT -> AndroidAutoStateReporter.report(context)
        }
    }

    /**
     * CarLink already identified this device as an Android phone and the driver allows starting on
     * plug-in, so it counts as an allowed device for DiAuto (whose default only switches Google
     * devices and phones on its allowlist). DiAuto's last-session check then requests permission
     * and switches it to accessory mode; when the driver turned that check off, switch it here.
     */
    private fun startUsb(context: Context, device: UsbDevice?) {
        if (device != null && !UsbDeviceCompat.isInAccessoryMode(device)) {
            val settings = App.provide(context).settings
            val name = UsbDeviceCompat(device).uniqueName
            if (!settings.isConnectingDevice(UsbDeviceCompat(device))) settings.allowedDevices = settings.allowedDevices + name
            val usb = context.getSystemService(UsbManager::class.java)
            if (!settings.autoConnectLastSession && usb != null && usb.hasPermission(device)) {
                val pending = goAsync()
                Thread({
                    try {
                        // AapService's own USB receiver picks up the phone when it re-enumerates.
                        startService(context, AapService.ACTION_CHECK_USB)
                        Log.i(TAG, "AOA switch result=${UsbAccessoryMode(usb).connectAndSwitch(device, settings.useLibusb)}")
                    } finally {
                        pending.finish()
                    }
                }, "carlink-aoa-switch").start()
                return
            }
        }
        startService(context, AapService.ACTION_CHECK_USB)
    }

    private fun usbDevice(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(P.EXTRA_USB_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(P.EXTRA_USB_DEVICE)
    }

    /**
     * A wireless start the driver asked for. If an earlier attempt is stuck (not connected), it
     * is stopped and any Wi-Fi Direct group left behind is removed first: DiAuto only reuses a
     * group whose name it recorded, and a group from an earlier CarLink attempt with a newer name
     * makes it stand down for good ("Another app owns Wi-Fi Direct"). CarLink runs one engine at a
     * time, so every group on this head unit's P2P interface was created by CarLink.
     */
    private fun startWireless(context: Context, address: String?) {
        if (App.provide(context).commManager.isConnected) return
        if (isServiceRunning(context)) {
            startService(context, AapService.ACTION_STOP_SERVICE)
            waitUntil(STOP_WAIT_MILLIS) { !isServiceRunning(context) }
        }
        WifiDirectReset.removeGroup(context)
        context.getSharedPreferences("p2p_ownership", Context.MODE_PRIVATE).edit().clear().commit()
        val nativeWireless = App.provide(context).settings.wifiConnectionMode == NATIVE_WIRELESS_MODE
        if (address != null && nativeWireless) {
            // What DiAuto's phone list does: arm Native AA and wake this phone over Bluetooth.
            startService(context, AapService.ACTION_NATIVE_AA_POKE) { putExtra(AapService.EXTRA_MAC, address) }
        } else {
            // What DiAuto's Bluetooth auto-start does: (re-)arm the configured wireless mode.
            startService(context, AapService.ACTION_BT_AUTO_START)
        }
    }

    private fun startService(context: Context, action: String, extras: Intent.() -> Unit = {}) {
        val service = Intent(context, AapService::class.java).setAction(action).apply(extras)
        runCatching { ContextCompat.startForegroundService(context, service) }
            .onFailure { Log.w(TAG, "AapService $action refused: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(100)
    }

    companion object {
        private const val TAG = "CarLink-AA"
        private const val NATIVE_WIRELESS_MODE = 3
        private const val STOP_WAIT_MILLIS = 3_000L

        @Suppress("DEPRECATION") // Still returns the caller's own services.
        fun isServiceRunning(context: Context): Boolean {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return false
            val name = AapService::class.java.name
            return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == name && it.started }
        }
    }
}
