package com.carlink.app.connection.aa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.carlink.app.settings.VehicleProfile
import java.util.concurrent.Executors

/**
 * Runs in the ":aa" process (declared in the manifest, so a command starts the process). Commands
 * run one at a time on one background thread, so a stop never interleaves with a start.
 */
class AaCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AaProtocol.ACTION_COMMAND) return
        val command = intent.getStringExtra(AaProtocol.EXTRA_COMMAND) ?: return
        val app = context.applicationContext
        Log.i(TAG, "command=$command")
        val pending = goAsync()
        executor.execute {
            try {
                run(app, command, intent)
            } catch (e: Exception) {
                Log.e(TAG, "command $command failed: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun run(app: Context, command: String, intent: Intent) {
        val handler = handler(app)
        val profile = intent.getStringExtra(AaProtocol.EXTRA_VEHICLE_PROFILE)
            ?.let { runCatching { VehicleProfile.valueOf(it) }.getOrNull() } ?: VehicleProfile.GENERIC
        val address = intent.getStringExtra(AaProtocol.EXTRA_ADDRESS)
        when (command) {
            AaProtocol.COMMAND_START_WIRELESS -> handler.startWireless(address, profile)
            AaProtocol.COMMAND_START_USB -> handler.startUsb(usbDevice(intent), address, profile)
            AaProtocol.COMMAND_POKE -> address?.let { handler.poke(it) }
            AaProtocol.COMMAND_STOP -> handler.stop(intent.getLongExtra(AaProtocol.EXTRA_TOKEN, 0L))
            AaProtocol.COMMAND_REPORT -> AaEngineHooks.report(app)
            AaProtocol.COMMAND_USB_PERMISSION ->
                handler.onUsbPermission(usbDevice(intent), intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
        }
    }

    private fun usbDevice(intent: Intent): UsbDevice? {
        val key = if (intent.hasExtra(AaProtocol.EXTRA_USB_DEVICE)) AaProtocol.EXTRA_USB_DEVICE else UsbManager.EXTRA_DEVICE
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, UsbDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(key)
    }

    companion object {
        private const val TAG = "CarLink-AA2"
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "carlink-aa2-commands") }

        private fun handler(app: Context) = AaCommandHandler(
            engine = DiAutoEngineAccess(app),
            monitor = AaEngineMonitor.shared,
            emit = { kind, detail -> AaEngineHooks.sendEvent(app, kind, detail) },
            ack = { token, serviceStopped, groupReleased ->
                app.sendBroadcast(Intent(AaProtocol.ACTION_STOPPED).setPackage(app.packageName)
                    .putExtra(AaProtocol.EXTRA_TOKEN, token)
                    .putExtra(AaProtocol.EXTRA_SERVICE_STOPPED, serviceStopped)
                    .apply { if (groupReleased != null) putExtra(AaProtocol.EXTRA_GROUP_RELEASED, groupReleased) })
            },
            report = { AaEngineHooks.report(app) },
        )
    }
}
