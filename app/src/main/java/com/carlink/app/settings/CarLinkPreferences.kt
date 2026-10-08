package com.carlink.app.settings

import android.content.Context

/** CarLink's own settings: how and when phones connect. Engine settings stay in each engine. */
class CarLinkPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Start the right engine when a phone is plugged in. */
    var autoConnectUsb: Boolean
        get() = prefs.getBoolean(KEY_AUTO_USB, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_USB, value).apply()

    /** Start wireless projection when a paired phone's Bluetooth connects to the car. */
    var autoConnectBluetooth: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BLUETOOTH, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_BLUETOOTH, value).apply()

    /** Open CarLink when the car starts. */
    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, false)
        set(value) = prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()

    /**
     * Set when the driver cancels or disconnects; cleared when they connect again. Keeps
     * automatic starts from reconnecting against the driver's wish.
     */
    var autoConnectPaused: Boolean
        get() = prefs.getBoolean(KEY_PAUSED, false)
        set(value) = prefs.edit().putBoolean(KEY_PAUSED, value).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    companion object {
        private const val PREFS = "carlink"
        private const val KEY_AUTO_USB = "auto_connect_usb"
        private const val KEY_AUTO_BLUETOOTH = "auto_connect_bluetooth"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_PAUSED = "auto_connect_paused"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
    }
}
