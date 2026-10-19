package com.carlink.core.connection

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pages a bonded phone over Bluetooth with a service-discovery (SDP) query, so the baseband link
 * is up when the engine dials it a moment later (RELIABILITY_AUDIT K3, plan item 7).
 *
 * Wireless CarPlay starts with an RFCOMM dial to the iPhone. With no link up that dial must page
 * the iPhone first: 5.8-6.7 s on the tablet, against 0.2 s when its Bluetooth was already
 * connected. [SessionManager] runs this while the previous engine stops (1.5-4 s) and starts DiPlay
 * when it is done (or after [SessionManager.WARM_UP_MAX_MILLIS]), so the page overlaps the stop
 * and the two never page the phone at the same time (an SDP query racing an RFCOMM connect can
 * fail the connect). Nothing is opened: the query only reads the phone's service list.
 */
internal object BluetoothWarmup {
    private const val TAG = "CarLink-BT"
    /** Cleans the receiver up when the stack never answers. */
    private const val GIVE_UP_MILLIS = 10_000L

    /** Starts the page; [done] runs once (main thread) when the phone answered or gave up. False: nothing started. */
    @SuppressLint("MissingPermission") // Checked below.
    fun start(context: Context, address: String, done: () -> Unit): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 31 &&
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled) return false
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false
        if (runCatching { device.bondState }.getOrNull() != BluetoothDevice.BOND_BONDED) return false
        // A phone already linked answers the dial in 0.2 s: nothing to gain.
        if (isConnected(device)) return false
        val main = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val started = android.os.SystemClock.uptimeMillis()
        lateinit var receiver: BroadcastReceiver
        fun finish(how: String, report: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            runCatching { app.unregisterReceiver(receiver) }
            Log.i(TAG, "warm-up $how after ${android.os.SystemClock.uptimeMillis() - started} ms")
            if (report) main.post(done)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val from = intentDevice(intent) ?: return
                if (!from.address.equals(address, ignoreCase = true)) return
                // The page is what the dial needs; the service list can come seconds later (tablet
                // 2026-10-20 #s4: link up at +0.25 s, UUIDs at +6.0 s, the engine waited for them).
                finish(if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) "linked" else "answered", report = true)
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(BluetoothDevice.ACTION_UUID).apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }, ContextCompat.RECEIVER_EXPORTED)
        val asked = runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false)
        if (!asked) {
            finish("could not start", report = false)
            return false
        }
        main.postDelayed({ finish("got no answer", report = true) }, GIVE_UP_MILLIS)
        return true
    }

    /** [address]'s Bluetooth link to this device is up (false when unknown). */
    @SuppressLint("MissingPermission")
    fun linked(context: Context, address: String): Boolean = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)?.let(::isConnected) == true
    }.getOrDefault(false)

    private fun isConnected(device: BluetoothDevice): Boolean = runCatching {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as? Boolean == true
    }.getOrDefault(false)

    private fun intentDevice(intent: Intent): BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }
}
