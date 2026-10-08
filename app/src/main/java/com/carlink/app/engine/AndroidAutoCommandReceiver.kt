package com.carlink.app.engine

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
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
            P.COMMAND_START_USB -> startService(context, AapService.ACTION_CHECK_USB)
            P.COMMAND_START_WIRELESS -> startWireless(context, intent.getStringExtra(P.EXTRA_ADDRESS))
            P.COMMAND_DISCONNECT -> if (isServiceRunning(context)) startService(context, AapService.ACTION_DISCONNECT)
            // Sending STOP_SERVICE to a stopped AapService would first run its onCreate, which
            // enables car mode and opens the wireless stack.
            P.COMMAND_STOP -> if (isServiceRunning(context)) startService(context, AapService.ACTION_STOP_SERVICE)
            P.COMMAND_REPORT -> AndroidAutoStateReporter.report(context)
        }
    }

    private fun startWireless(context: Context, address: String?) {
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

    companion object {
        private const val TAG = "CarLink-AA"
        private const val NATIVE_WIRELESS_MODE = 3

        @Suppress("DEPRECATION") // Still returns the caller's own services.
        fun isServiceRunning(context: Context): Boolean {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return false
            val name = AapService::class.java.name
            return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == name && it.started }
        }
    }
}
