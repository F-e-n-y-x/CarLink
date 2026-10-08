package com.carlink.app.entry

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.carlink.app.core.PhoneKind
import com.carlink.app.detect.KnownPhones
import com.carlink.app.detect.PhoneClassifier
import com.carlink.app.settings.CarLinkPreferences
import com.carlink.app.ui.HomeActivity

/**
 * CarLink's boot and Bluetooth entry points (each engine's own receivers are removed from the
 * merged manifest). A phone is classified here; the engine is started by HomeActivity in the
 * foreground, which Android allows on every version, rather than from this background receiver.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT ->
                if (CarLinkPreferences(context).startOnBoot) HomeLauncher.open(context, HomeLauncher.Reason.BOOT)
            BluetoothDevice.ACTION_ACL_CONNECTED -> onPhoneLink(context, intent, sdpDone = false)
            BluetoothDevice.ACTION_UUID -> onPhoneLink(context, intent, sdpDone = true)
        }
    }

    @SuppressLint("MissingPermission") // Checked in hasBluetoothPermission().
    private fun onPhoneLink(context: Context, intent: Intent, sdpDone: Boolean) {
        val preferences = CarLinkPreferences(context)
        if (!preferences.autoConnectBluetooth || preferences.autoConnectPaused) return
        if (!hasBluetoothPermission(context)) return
        val device = bluetoothDevice(intent) ?: return
        val address = device.address ?: return
        val uuids = if (sdpDone) parcelUuids(intent) else device.uuids?.map { it.uuid }?.toSet().orEmpty()
        val info = PhoneClassifier.BluetoothDeviceInfo(
            address = address,
            name = runCatching { device.name }.getOrNull(),
            uuids = uuids,
            majorClass = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull(),
        )
        val kind = PhoneClassifier.classifyBluetooth(info, KnownPhones(context).kindOf(address))
        Log.i(TAG, "Bluetooth link sdp=$sdpDone -> $kind")
        if (kind == PhoneKind.UNKNOWN) {
            // No cached service records yet: ask once, and decide when ACTION_UUID arrives.
            if (!sdpDone) runCatching { device.fetchUuidsWithSdp() }
            return
        }
        if (!debounce(context, address)) return
        HomeLauncher.open(context, HomeLauncher.Reason.PHONE, address, kind, info.name)
    }

    /** Cars send several ACL events per wake-up; act on the first one per phone. */
    private fun debounce(context: Context, address: String): Boolean {
        val prefs = context.getSharedPreferences("carlink_bluetooth_wake", Context.MODE_PRIVATE)
        val key = KnownPhones.normalize(address)
        val now = SystemClock.elapsedRealtime()
        val previous = prefs.getLong(key, -1)
        if (previous in 0..now && now - previous < DEBOUNCE_MILLIS) return false
        prefs.edit().putLong(key, now).commit()
        return true
    }

    private fun hasBluetoothPermission(context: Context) = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun bluetoothDevice(intent: Intent): BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

    private fun parcelUuids(intent: Intent): Set<java.util.UUID> {
        val values = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)?.toList()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)?.filterIsInstance<ParcelUuid>()
        }
        return values.orEmpty().map { it.uuid }.toSet()
    }

    companion object {
        private const val TAG = "CarLink-BT"
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        private const val DEBOUNCE_MILLIS = 15_000L
    }
}
