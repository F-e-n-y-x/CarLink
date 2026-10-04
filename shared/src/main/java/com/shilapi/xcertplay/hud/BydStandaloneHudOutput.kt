package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log

/** Ordinary-app IPC to the real stock receiver. No shell, local socket or permission grant. */
internal class BydStandaloneHudOutput private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("byd_standalone_hud", Context.MODE_PRIVATE)
    private val session = BydStandaloneSession(
        send = { packet ->
            app.sendBroadcast(Intent("byd.hud.NAVIGATION").setComponent(TARGET)
                .putExtra("normal", packet).addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
            Log.d(TAG, "dispatch uid=${Process.myUid()} bytes=${packet.split(',').size}")
        },
        rememberPendingClear = { pending ->
            check(prefs.edit().putBoolean("pending_clear", pending).commit()) { "Cannot persist HUD cleanup" }
        },
        needsRecovery = prefs.getBoolean("pending_clear", false),
    )

    init {
        Log.i(TAG, "Standalone navigation ready uid=${Process.myUid()} helper=none")
        // Retain the journal if dispatch fails; the next scheduled tick retries.
        runCatching { session.clear() }.onFailure { Log.w(TAG, "Startup clear will retry", it) }
    }

    fun update(icon: Int, exit: Int, distanceMeters: Int, road: String?) =
        session.update(icon, exit, distanceMeters, road)
    /** One plain line on the HUD, used for the CarPlay song/lyrics line. No maneuver records. */
    fun showText(text: String) = session.showText(text)
    fun clear() = session.clear()

    companion object {
        private const val TAG = "BYD-Standalone-Live"
        private val TARGET = ComponentName("com.byd.clusterdebug", "com.byd.clusterdebug.BroadcastReceiverCAN")
        private val TRUSTED_PACKAGE_PREFIXES = listOf("com.shihab.diplay", "com.andrerinas.headunitrevived")

        fun create(context: Context): BydStandaloneHudOutput? =
            if (available(context)) BydStandaloneHudOutput(context) else null

        /**
         * The stock receiver is an open backdoor, so presence of the package is the only gate.
         *
         * Self-check is a prefix match on the DiPlay / HeadUnitReloaded application id: every
         * variant (release, debug, per-car build) is armed without re-listing package constants
         * whenever the application id changes. It only keeps other apps that embed this library
         * from driving OEM hardware; the car still decides what to draw.
         */
        fun available(context: Context): Boolean {
            val own = context.packageName
            if (Build.VERSION.SDK_INT < 28 ||
                TRUSTED_PACKAGE_PREFIXES.none { own == it || own.startsWith("$it.") }) return false
            // Just try whenever the package is installed. No fingerprint, signature or
            // version checks: whether it actually renders is verified on the car, not here.
            return runCatching {
                context.packageManager.getPackageInfo(TARGET.packageName, 0)
            }.isSuccess
        }
    }
}
