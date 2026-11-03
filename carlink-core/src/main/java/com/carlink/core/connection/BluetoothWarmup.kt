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
 * the iPhone first: 4.5-6.7 s on the tablet, against 0.2 s when its Bluetooth was already
 * connected. [SessionManager] starts this the moment it decides on wireless CarPlay, so the page
 * overlaps the previous engine's stop and the projection's launch (0.5-1.3 s). The engine is not
 * held for it: only DiPlay's dial waits ([awaitPage], via
 * `WirelessStartupTimeline.beforeBluetoothDial`), so the two never page the phone at the same time
 * (an SDP query racing an RFCOMM connect can fail the connect). Holding the whole engine cost up to
 * 5.3 s past the stop (tablet 2026-11-04 #s2/#s3). Nothing is opened: the query only reads the
 * phone's service list.
 */
internal object BluetoothWarmup {
    private const val TAG = "CarLink-BT"
    /** Cleans the receiver up when the stack never answers. */
    private const val GIVE_UP_MILLIS = 10_000L
    /** The longest a dial waits for a page from its start (one Bluetooth page window plus SDP). */
    const val DIAL_WAIT_MAX_MILLIS = 6_000L

    private val pages = PageGate(DIAL_WAIT_MAX_MILLIS)

    /** Blocks (dial thread) until this device's page of [address] finished, at most [DIAL_WAIT_MAX_MILLIS] from its start. */
    fun awaitPage(address: String) {
        val waited = pages.await(address)
        if (waited > 0) Log.i(TAG, "dial waited $waited ms for the warm-up page")
    }

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
            pages.end(address)
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
        pages.begin(address)
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

/**
 * The Bluetooth pages in flight, per address: a dial waits for its phone's page to end, but never
 * longer than [maxMillis] from the page's start. Thread-safe; [now] is injectable for tests.
 */
internal class PageGate(private val maxMillis: Long, private val now: () -> Long = android.os.SystemClock::uptimeMillis) {
    private class Page(val startedAt: Long) { val done = java.util.concurrent.CountDownLatch(1) }
    private val pages = java.util.concurrent.ConcurrentHashMap<String, Page>()

    fun begin(address: String) {
        pages.put(address.uppercase(), Page(now()))?.done?.countDown()
    }

    fun end(address: String) {
        pages.remove(address.uppercase())?.done?.countDown()
    }

    /** Returns how long it waited (0: no page in flight). */
    fun await(address: String): Long {
        val page = pages[address.uppercase()] ?: return 0
        val left = page.startedAt + maxMillis - now()
        if (left <= 0) return 0
        val from = now()
        runCatching { page.done.await(left, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return now() - from
    }
}
