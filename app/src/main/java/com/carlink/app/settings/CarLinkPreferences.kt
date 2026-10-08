package com.carlink.app.settings

import android.content.Context
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfileMode

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

    /** Follow the car/system day-night mode by default (DESIGN_SPEC §5.1). */
    var themeMode: ThemeMode
        get() = enumOr(prefs.getString(KEY_THEME, null), ThemeMode.FOLLOW_SYSTEM)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    /** Car-grade or phone/tablet sizing; Auto detects the device. */
    var uiProfileMode: UiProfileMode
        get() = enumOr(prefs.getString(KEY_PROFILE, null), UiProfileMode.AUTO)
        set(value) = prefs.edit().putString(KEY_PROFILE, value.name).apply()

    /** "Interface size" of CarLink's own screens: 0.9, 1.0, 1.15 or 1.3. */
    var interfaceScale: Float
        get() = prefs.getFloat(KEY_SCALE, 1f)
        set(value) = prefs.edit().putFloat(KEY_SCALE, value).apply()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    companion object {
        val INTERFACE_SCALES = listOf(0.9f, 1f, 1.15f, 1.3f)
        private const val PREFS = "carlink"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_PROFILE = "ui_profile_mode"
        private const val KEY_SCALE = "interface_scale"
        private const val KEY_AUTO_USB = "auto_connect_usb"
        private const val KEY_AUTO_BLUETOOTH = "auto_connect_bluetooth"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_PAUSED = "auto_connect_paused"
    }
}
