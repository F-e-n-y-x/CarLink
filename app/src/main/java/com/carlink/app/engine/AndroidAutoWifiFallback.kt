package com.carlink.app.engine

import android.content.Context
import android.content.Intent
import android.util.Log
import com.andrerinas.openheadunit.App
import com.carlink.app.engine.AndroidAutoProtocol as P

/**
 * Runs in the ":aa" process (DiAuto's join-failure hook). When the phone refuses the shared Wi-Fi
 * or hotspot it was handed, DiAuto would offer the same network forever and Home would sit on
 * "Connecting". After [REFUSALS] refusals in one attempt CarLink moves the attempt to Wi-Fi
 * Direct, which needs no password, and tells the main process so the next session skips it.
 */
internal object AndroidAutoWifiFallback {
    private const val TAG = "CarLink-AA"
    /** One refusal can be a radio hiccup; a wrong or missing password repeats within seconds. */
    private const val REFUSALS = 2
    private const val WIFI_DIRECT = 0
    private const val HOTSPOT = 1

    private var refusals = 0
    private var switched = false

    @Synchronized
    fun reset() {
        refusals = 0
        switched = false
    }

    fun onJoinFailed(context: Context) {
        val settings = App.provide(context).settings
        synchronized(this) {
            if (switched || settings.nativeApTransport != HOTSPOT) return
            if (++refusals < REFUSALS) return
            switched = true
        }
        val sameWifi = settings.hotspotInterface.isNotEmpty()
        val refusedSsid = settings.hotspotSsid.takeIf { sameWifi && it.isNotEmpty() }
        Log.i(TAG, "phone refused the ${if (sameWifi) "shared Wi-Fi" else "hotspot"} $refusals times; moving to Wi-Fi Direct")
        settings.nativeApTransport = WIFI_DIRECT
        settings.hotspotInterface = ""
        settings.staticBSSID = "0"
        settings.hotspotSsid = ""
        settings.hotspotPassword = ""
        context.sendBroadcast(Intent(P.ACTION_WIFI_FALLBACK).setPackage(context.packageName)
            .putExtra(P.EXTRA_REFUSED_SSID, refusedSsid))
        // Off the engine thread: the restart stops AapService and waits for it.
        Thread({ AndroidAutoCommandReceiver.restartWireless(context) }, "carlink-aa-fallback").start()
    }
}
