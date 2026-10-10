package com.carlink.app.ui

import android.content.Context
import com.carlink.app.ui.theme.RenderingMode
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfileMode
import com.carlink.app.update.UpdatePrefs

/**
 * CarLink's own screen preferences. The connection preferences (automatic starts, the driver's
 * pause) are the backend's `settings.CarLinkPreferences`; both live in the same file ("carlink"),
 * so values stored before the split are kept.
 */
class UiPreferences(context: Context) : UpdatePrefs {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The floating switcher over CarPlay and Android Auto (needs "Display over other apps"). */
    var floatingSwitcher: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_SWITCHER, true)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING_SWITCHER, value).apply()

    /** The first-time setup was finished or skipped (it then runs only when the driver asks). */
    var setupDone: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_DONE, value).apply()

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

    /** Full effects or lite rendering; Automatic picks lite on low-end hardware (ui/theme/RenderingMode.kt). */
    var renderingMode: RenderingMode
        get() = enumOr(prefs.getString(KEY_RENDERING, null), RenderingMode.AUTO)
        set(value) = prefs.edit().putString(KEY_RENDERING, value.name).apply()

    /** Settings › About › "Automatically check for updates" (release builds only; update/Updates.kt). */
    override var autoCheckUpdates: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATES, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATES, value).apply()

    /** The release tag the driver chose "Ignore this version" for. */
    override var ignoredUpdateTag: String?
        get() = prefs.getString(KEY_IGNORED_UPDATE, null)
        set(value) = prefs.edit().putString(KEY_IGNORED_UPDATE, value).apply()

    override var lastUpdateCheckMillis: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    companion object {
        val INTERFACE_SCALES = listOf(0.9f, 1f, 1.15f, 1.3f)
        private const val PREFS = "carlink"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_PROFILE = "ui_profile_mode"
        private const val KEY_SCALE = "interface_scale"
        private const val KEY_RENDERING = "rendering_mode"
        private const val KEY_FLOATING_SWITCHER = "floating_switcher"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_AUTO_UPDATES = "update_auto_check"
        private const val KEY_IGNORED_UPDATE = "update_ignored_tag"
        private const val KEY_LAST_UPDATE_CHECK = "update_last_check"
    }
}
