package com.shilapi.xcertplay.hud

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.util.Log

/**
 * Optional, needs ADB over network: while CarPlay runs, the phone keeps its hands-free link to
 * the car's system Bluetooth, so an incoming call rings on both the car's Bluetooth phone and
 * CarPlay. Suspending the car's Bluetooth for the session routes calls through CarPlay alone;
 * it is resumed when the session ends and woken before the next wireless handshake, so the
 * Bluetooth pairing itself is never touched.
 */
object BydBluetoothSuspend {
    private const val TAG = "DiPlay-BT-Suspend"
    private const val PREFS = "diplay_bt_suspend"
    private const val KEY_SUSPENDED_BY_US = "suspended_by_us"

    private val shell = BydAdbShell(TAG)
    private val worker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "diplay-bt-suspend").apply { isDaemon = true }
    }
    @Volatile private var pending: java.util.concurrent.ScheduledFuture<*>? = null

    /**
     * Disable the car's Bluetooth over adb after [delayMillis]. Suspending the instant the
     * session reports active races the phone's Wi-Fi association: some units drop CarPlay
     * entirely when Bluetooth vanishes that early, so the default waits ten seconds.
     */
    fun suspend(context: Context, delayMillis: Long = 10_000L) {
        val app = context.applicationContext
        if (isSuspendedByUs(app)) return
        pending?.cancel(false)
        pending = worker.schedule({
            if (isSuspendedByUs(app)) return@schedule
            val done = shell.run(app, "svc bluetooth disable") != null
            if (!done) {
                Log.w(TAG, "could not suspend the car Bluetooth; is ADB over network on?")
                return@schedule
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_SUSPENDED_BY_US, true).apply()
            Log.i(TAG, "car Bluetooth suspended ${delayMillis}ms after the session became active")
        }, delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Re-enable the car's Bluetooth if we suspended it; safe to call repeatedly. */
    fun resume(context: Context) {
        val app = context.applicationContext
        pending?.cancel(false)
        pending = null
        if (!isSuspendedByUs(app)) return
        val done = shell.run(app, "svc bluetooth enable") != null
        if (done) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_SUSPENDED_BY_US, false).apply()
            Log.i(TAG, "car Bluetooth resumed")
        }
    }

    /**
     * Before a wireless handshake the adapter must be on: wake the Bluetooth we suspended and
     * wait briefly for it. Returns false when it did not come back in time; the normal
     * "Bluetooth is not enabled" path then reports it.
     */
    fun resumeAndWait(context: Context, adapter: BluetoothAdapter?, timeoutMillis: Long = 8_000): Boolean {
        val app = context.applicationContext
        if (!isSuspendedByUs(app)) return true
        resume(app)
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (adapter?.isEnabled == true) return true
            android.os.SystemClock.sleep(250)
        }
        return adapter?.isEnabled == true
    }

    fun isSuspendedByUs(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SUSPENDED_BY_US, false)
}
