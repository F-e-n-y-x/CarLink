package com.shilapi.xcertplay.network

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.hud.BydAdbShell
import com.shilapi.xcertplay.hud.BydParcel
import java.lang.reflect.Field
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Pauses the head unit's automatic Wi-Fi network search while wireless CarPlay runs.
 *
 * BYD DiLink 3 (Android 10) scans every Wi-Fi band every 10 s whenever the car's own Wi-Fi client is
 * not connected to a network. Each scan takes the single radio off the CarPlay channel for 3-6 s, so
 * the stream stutters unless the car happens to be joined to a hotspot. Through the approved local
 * adb shell, `IWifiManager.enableWifiConnectivityManager(false)` stops those scans (shell holds
 * CONNECTIVITY_INTERNAL) and [close] turns them back on. Nothing happens without adb approval.
 *
 * The binder transaction number differs between firmware builds, so it is read from the framework
 * itself instead of being hard-coded; when it cannot be read, scans are left alone.
 */
internal class WifiScanPause(
    context: Context,
    private val log: (String) -> Unit,
    private val transactionCode: () -> Int? = ::enableConnectivityManagerTransaction,
    testShell: ((String) -> String?)? = null,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, TAG) },
) {
    private val app = context.applicationContext
    private val adb = if (testShell == null) BydAdbShell(TAG) else null
    private val shell: (String) -> String? = testShell ?: { command -> adb?.run(app, command) }
    private var paused = false
    private var closed = false

    /** Asynchronously stops the periodic scans; repeated calls are harmless. */
    @Synchronized
    fun pause() {
        if (closed) return
        executor.execute {
            if (paused) return@execute
            val code = transactionCode()
            if (code == null) {
                log("Wi-Fi scan pause unavailable: transaction code unknown")
                return@execute
            }
            paused = accepted(shell(command(code, enabled = false)))
            log("Wi-Fi connectivity scans paused=$paused")
        }
    }

    /** Restores the scans if this instance paused them, then releases the adb link. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        executor.execute {
            try {
                if (paused) {
                    val code = transactionCode()
                    val restored = code != null && accepted(shell(command(code, enabled = true)))
                    paused = !restored
                    log("Wi-Fi connectivity scans restored=$restored")
                }
            } finally {
                adb?.close()
            }
        }
        executor.shutdown()
    }

    internal companion object {
        private const val TAG = "DiPlayWifiScan"

        fun command(code: Int, enabled: Boolean): String {
            require(code > 0)
            return "service call wifi $code i32 ${if (enabled) 1 else 0}"
        }

        /** `void` replies carry only a zero exception word. */
        fun accepted(output: String?): Boolean = BydParcel.words(output).firstOrNull() == 0L

        /**
         * Android 10 blocks direct reflection on this hidden constant but still allows the lookup when
         * it goes through Class.getDeclaredField itself; later releases close that path, so only Q is
         * attempted, which is what DiLink 3 runs.
         */
        fun enableConnectivityManagerTransaction(): Int? {
            if (Build.VERSION.SDK_INT != Build.VERSION_CODES.Q) return null
            return runCatching {
                val stub = Class.forName("android.net.wifi.IWifiManager\$Stub")
                val lookup = Class::class.java.getDeclaredMethod("getDeclaredField", String::class.java)
                val field = lookup.invoke(stub, "TRANSACTION_enableWifiConnectivityManager") as Field
                field.isAccessible = true
                field.getInt(null)
            }.getOrNull()?.takeIf { it > 0 }
        }
    }
}
