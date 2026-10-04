package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * Optional, needs ADB over network.
 *
 * On DiLink 4.0 the car's own map ([STOCK_MAP]) draws onto the very same cluster projection surface
 * DiPlay mirrors to, and the two together are reported to make the cluster reboot after a while.
 * Killing that app is not enough: it comes straight back. So while DiPlay mirrors the cluster the
 * map is *disabled*, and enabled again the moment the mirror stops. How it is disabled — the whole
 * package, or only its cluster projection — is the driver's choice, see [BydOemClusterHold].
 *
 * Every command runs over the same loopback adbd the other BYD features use, so the car's approval
 * dialog cannot appear while driving, and a refused or absent adbd is simply logged.
 */
object BydOemClusterNavi {
    private const val TAG = "DiPlay-BYD-OemCluster"
    private const val PREFS = "diplay_oem_cluster"
    private const val KEY_HELD = "stock_map_held"

    /** The car's own map, held down while DiPlay owns the cluster. */
    internal const val STOCK_MAP = "com.byd.automap"

    /** The one activity that app draws the cluster with; disabling it alone stops the projection. */
    internal const val STOCK_MAP_CLUSTER_ACTIVITY = "$STOCK_MAP.extra.MeterActivity"

    private val shell = BydAdbShell(TAG)

    /** A single thread, so a hold is always fully applied before its release. */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diplay-oem-cluster").apply { isDaemon = true }
    }

    /** Whether the car ships a map that draws on the cluster at all. */
    fun applicable(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(STOCK_MAP, 0) }.isSuccess

    /** Disables the car's map for as long as DiPlay mirrors the cluster, the chosen way. */
    fun hold(context: Context) {
        val app = context.applicationContext
        val hold = BydOutputSettings.oemClusterHold(app)
        if (hold == BydOemClusterHold.OFF) {
            // The switch may have been turned off while a hold was still in place; undo it.
            if (held(app)) release(app)
            return
        }
        if (!applicable(app)) return
        worker.execute {
            var refused = false
            disableCommands(hold).forEach { command ->
                val output = shell.run(app, command)
                val bad = output == null || failed(output)
                refused = refused || bad
                Log.i(TAG, "hold $command refused=$bad ${output?.trim()?.take(160).orEmpty()}")
            }
            // A missing or refused adb leaves the flag alone, so a later run tries again.
            if (!refused) remember(app, true)
        }
    }

    /** Puts the car's map back. Harmless when nothing was held. */
    fun release(context: Context) {
        val app = context.applicationContext
        if (!held(app)) return
        worker.execute {
            // Enable both, whichever was disabled: enabling is idempotent, so the setting may change
            // between a hold and its release without stranding one of them.
            var reachable = true
            enableCommands().forEach { command ->
                val output = shell.run(app, command)
                if (output == null) reachable = false
                Log.i(TAG, "release $command ${output?.trim()?.take(160).orEmpty()}")
            }
            // Keep the flag when adb was unreachable, so the next open retries the release.
            if (reachable) remember(app, false)
        }
    }

    private fun disableCommands(hold: BydOemClusterHold): List<String> = when (hold) {
        BydOemClusterHold.OFF -> emptyList()
        BydOemClusterHold.COMPONENT -> listOf("pm disable-user --user 0 $STOCK_MAP_CLUSTER_ACTIVITY")
        BydOemClusterHold.PACKAGE -> listOf("pm disable-user --user 0 $STOCK_MAP")
    }

    private fun enableCommands(): List<String> = listOf(
        "pm enable --user 0 $STOCK_MAP",
        "pm enable --user 0 $STOCK_MAP_CLUSTER_ACTIVITY",
    )

    /**
     * Releases a hold that never got undone, because the app died or the car was switched off while
     * mirroring. Run once when the app opens, before any new session.
     */
    fun restoreIfNeeded(context: Context) {
        val app = context.applicationContext
        if (!held(app)) return
        Log.i(TAG, "releasing a stock map left disabled by an earlier run")
        release(app)
    }

    private fun held(context: Context): Boolean = prefs(context).getBoolean(KEY_HELD, false)

    private fun remember(context: Context, held: Boolean) = prefs(context).edit().putBoolean(KEY_HELD, held).apply()

    private fun failed(output: String): Boolean =
        output.contains("error", ignoreCase = true) || output.contains("Exception")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
