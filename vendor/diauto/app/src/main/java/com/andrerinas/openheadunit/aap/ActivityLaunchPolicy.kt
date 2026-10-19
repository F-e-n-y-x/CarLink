package com.andrerinas.openheadunit.aap

import android.os.Build

object ActivityLaunchPolicy {

    enum class LaunchStrategy { DIRECT, OVERLAY, NOTIFICATION }

    /**
     * Chooses the safest activity-start strategy for the given API level.
     *
     * On Android 10+ (API 29+), direct startActivity() from a background Service is
     * silently blocked by the OS. The overlay trampoline bypasses this if the
     * SYSTEM_ALERT_WINDOW permission is granted; otherwise a full-screen notification
     * is used as fallback.
     */
    fun chooseLaunchStrategy(apiLevel: Int, canDrawOverlays: Boolean, appVisible: Boolean = false): LaunchStrategy = when {
        apiLevel < Build.VERSION_CODES.Q -> LaunchStrategy.DIRECT
        // CarLink: an app with a visible activity may start activities from any of its processes
        // (Android's background-launch rules), so a session the driver started on CarLink's
        // screen opens the projection without "display over other apps".
        appVisible -> LaunchStrategy.DIRECT
        canDrawOverlays -> LaunchStrategy.OVERLAY
        else -> LaunchStrategy.NOTIFICATION
    }
}
