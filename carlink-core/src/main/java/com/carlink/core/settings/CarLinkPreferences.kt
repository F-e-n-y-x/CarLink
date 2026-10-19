package com.carlink.core.settings

import android.content.Context

/**
 * How and when phones connect. Engine settings stay in each engine; a host's own screen
 * preferences (CarLink's are `ui.UiPreferences`) live in the host.
 */
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

    /** Open the host's main screen when the car starts. */
    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, false)
        set(value) = prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()

    /**
     * "Wireless through the stereo's Bluetooth module (experimental)": where this stereo's
     * Bluetooth is an external module (detect/ExternalBluetoothProbe), CarLink stops blocking
     * wireless and wireless Android Auto runs its handshake through the module
     * (DeviceEnvironment.moduleRoute). Off by default: untested on hardware. It replaced "Try
     * wireless on this stereo anyway" and keeps its key.
     */
    /** Settings › Connection › Root mode (advanced): grants and projection tweaks through `su` (RootMode). */
    var rootMode: Boolean
        get() = prefs.getBoolean(KEY_ROOT_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_ROOT_MODE, value).apply()

    var wirelessOnExternalBluetooth: Boolean
        get() = prefs.getBoolean(KEY_WIRELESS_EXTERNAL_BT, false)
        set(value) = prefs.edit().putBoolean(KEY_WIRELESS_EXTERNAL_BT, value).apply()

    /**
     * Set when the driver cancels or disconnects; cleared when they connect again, and on its own
     * after [PAUSE_MILLIS]. Keeps automatic starts from reconnecting right after the driver
     * stopped, without leaving a "paused · Resume" state behind (user feedback, 2026-10-09).
     */
    var autoConnectPaused: Boolean
        get() {
            if (!prefs.getBoolean(KEY_PAUSED, false)) return false
            val at = prefs.getLong(KEY_PAUSED_AT, 0L)
            return System.currentTimeMillis() - at in 0 until PAUSE_MILLIS
        }
        set(value) = prefs.edit().putBoolean(KEY_PAUSED, value).putLong(KEY_PAUSED_AT, System.currentTimeMillis()).apply()

    companion object {
        private const val PREFS = "carlink"
        private const val KEY_AUTO_USB = "auto_connect_usb"
        private const val KEY_AUTO_BLUETOOTH = "auto_connect_bluetooth"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_WIRELESS_EXTERNAL_BT = "wireless_on_external_bluetooth"
        private const val KEY_ROOT_MODE = "root_mode"
        private const val KEY_PAUSED_AT = "auto_connect_paused_at"
        const val PAUSE_MILLIS = 10 * 60_000L
        private const val KEY_PAUSED = "auto_connect_paused"
    }
}
