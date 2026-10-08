package com.carlink.app.entry

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.carlink.app.core.PhoneKind
import com.carlink.app.detect.KnownPhones
import com.carlink.app.detect.PhoneClassifier
import com.carlink.app.engine.EngineCoordinator
import com.carlink.app.settings.CarLinkPreferences

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
            // Only delivered here on Android 7.x: since 8.0 an implicit ACTION_UUID never reaches
            // a manifest receiver, so the SDP answer is awaited at runtime (awaitSdp).
            BluetoothDevice.ACTION_UUID -> onPhoneLink(context, intent, sdpDone = true)
        }
    }

    @SuppressLint("MissingPermission") // Checked in hasBluetoothPermission().
    private fun onPhoneLink(context: Context, intent: Intent, sdpDone: Boolean) {
        val preferences = CarLinkPreferences(context)
        if (!preferences.autoConnectBluetooth || preferences.autoConnectPaused) return
        if (!hasBluetoothPermission(context)) {
            Log.i(TAG, "Bluetooth link ignored: Nearby devices permission not granted")
            return
        }
        val device = bluetoothDevice(intent) ?: return
        val uuids = if (sdpDone) parcelUuids(intent) else device.uuids?.map { it.uuid }?.toSet().orEmpty()
        val kind = classify(context, device, uuids)
        Log.i(TAG, "Bluetooth link sdp=$sdpDone -> $kind")
        if (kind == PhoneKind.UNKNOWN) {
            // No cached service records yet: ask once, and decide when the answer arrives.
            if (!sdpDone) awaitSdp(context, device)
            return
        }
        act(context, device, kind)
    }

    @SuppressLint("MissingPermission")
    private fun classify(context: Context, device: BluetoothDevice, uuids: Set<java.util.UUID>): PhoneKind {
        val info = PhoneClassifier.BluetoothDeviceInfo(
            address = device.address,
            name = runCatching { device.name }.getOrNull(),
            uuids = uuids,
            majorClass = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull(),
        )
        return PhoneClassifier.classifyBluetooth(info, KnownPhones(context).kindOf(device.address))
    }

    @SuppressLint("MissingPermission")
    private fun act(context: Context, device: BluetoothDevice, kind: PhoneKind) {
        val address = device.address
        // The phone of a running session reconnects its Bluetooth profiles (wireless CarPlay
        // and Android Auto use Bluetooth themselves); bringing Home forward would cover the picture.
        val running = EngineCoordinator.get(context).state.value
        if (running.isActive) {
            Log.i(TAG, "Bluetooth $kind link during a ${running.engine} session; Home stays in the background")
            return
        }
        if (!debounce(context, address)) {
            Log.i(TAG, "Bluetooth $kind link debounced")
            return
        }
        HomeLauncher.open(context, HomeLauncher.Reason.PHONE, address, kind, runCatching { device.name }.getOrNull())
    }

    /**
     * Starts an SDP query and waits (bounded) for its ACTION_UUID with a runtime receiver, which
     * Android still delivers to, keeping this broadcast alive meanwhile.
     */
    @SuppressLint("MissingPermission")
    private fun awaitSdp(context: Context, device: BluetoothDevice) {
        val app = context.applicationContext
        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        lateinit var receiver: BroadcastReceiver
        val finish = {
            if (!finished) {
                finished = true
                runCatching { app.unregisterReceiver(receiver) }
                pending.finish()
            }
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val answered = bluetoothDevice(intent) ?: return
                if (!answered.address.equals(device.address, ignoreCase = true)) return
                val kind = classify(app, answered, parcelUuids(intent))
                Log.i(TAG, "Bluetooth SDP answer -> $kind")
                if (kind != PhoneKind.UNKNOWN) act(app, answered, kind)
                finish()
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(BluetoothDevice.ACTION_UUID),
            ContextCompat.RECEIVER_EXPORTED)
        handler.postDelayed({
            if (!finished) Log.i(TAG, "Bluetooth SDP gave no answer in ${SDP_TIMEOUT_MILLIS / 1000} s")
            finish()
        }, SDP_TIMEOUT_MILLIS)
        if (runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false) != true) {
            Log.i(TAG, "Bluetooth SDP query refused")
            handler.removeCallbacksAndMessages(null)
            finish()
        }
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

    companion object {
        private const val TAG = "CarLink-BT"
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        private const val DEBOUNCE_MILLIS = 15_000L

        /** SDP usually answers in 1–5 s; stays well inside a background broadcast's 60 s limit. */
        private const val SDP_TIMEOUT_MILLIS = 12_000L

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
    }
}
