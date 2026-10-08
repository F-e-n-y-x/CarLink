package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View

/**
 * CarLink: lets an APK that embeds this engine replace DiPlay's own screens. The projection
 * (CarPlayHostActivity) opens [homeActivity] for its home button and when authentication cannot
 * load; standalone DiPlay keeps DiPlayActivity.
 */
object CarPlayHostIntegration {
    @Volatile
    @JvmStatic
    var homeActivity: Class<out Activity> = DiPlayActivity::class.java

    /** Title of the connection notification. */
    @Volatile
    @JvmStatic
    var appName: String = "DiPlay"

    @Volatile
    @JvmStatic
    var mediaSessionTag: String = "DiPlay CarPlay"

    /** Sees the projection's informational toasts first; returns true when the host handled them. */
    @Volatile
    @JvmStatic
    var toastHandler: ((context: Context, text: CharSequence) -> Boolean)? = null

    fun showToast(context: Context, text: CharSequence, duration: Int) {
        if (toastHandler?.invoke(context, text) == true) return
        android.widget.Toast.makeText(context, text, duration).show()
    }

    /** The projection's own chrome, for [chromeStyler]. */
    enum class Chrome { WAITING, MENU, SIDE_PANEL, PICTURE_PANEL, SAFE_AREA_EDITOR }

    /**
     * Sections of the in-session menu (the multi-finger swipe). A host whose own settings screen
     * owns a section, or that has no use for a standalone-DiPlay control, leaves it out; hidden
     * sections are still built and keep their saved values.
     */
    enum class MenuItem {
        SIDE_PANEL, MFI_TARGET, WIRELESS, WIFI_METHOD, HOTSPOT_STATUS, AUTO_START, LOCATION, AUDIO_MAPPING,
        VEHICLE_IDENTITY, CAR_BUTTON_ICON, DRIVING_SIDE, DOCK, RESOLUTION, FRAME_RATE, PHYSICAL_SIZE_BASIS,
        PHYSICAL_LENGTH, HEVC, SAFE_AREA, SYSTEM_BARS, DEBUG_LOGS, EXIT_APPLICATION, LANGUAGE, GESTURE_FINGERS,
        HOME,
    }

    @Volatile
    @JvmStatic
    var menuItems: Set<MenuItem> = MenuItem.entries.toSet()

    /** Replaces DiPlay's colors for its chrome. Return the same instance for the same [night]. */
    @Volatile
    @JvmStatic
    var palette: ((night: Boolean) -> DiPlayPalette)? = null

    /** Restyles the projection's chrome (type, shapes, marks) after DiPlay builds or repaints it. */
    @Volatile
    @JvmStatic
    var chromeStyler: ((root: View, chrome: Chrome, night: Boolean) -> Unit)? = null

    internal fun styleChrome(root: View?, chrome: Chrome, night: Boolean) {
        if (root != null) chromeStyler?.invoke(root, chrome, night)
    }

    /** [page] is DiPlay's page name ("home", "settings", …); other hosts may ignore it. */
    fun homeIntent(context: Context, page: String? = null): Intent =
        Intent(context, homeActivity).apply { page?.let { putExtra("page", it) } }

    /** The iPhone wireless CarPlay connects to (DiPlay's "Choose iPhone"). */
    fun selectPhone(context: Context, address: String, name: String) = DiPlayPreferences.savePhone(context, address, name)

    fun selectedPhoneAddress(context: Context): String? = DiPlayPreferences.phoneAddress(context)

    /** Null when CarPlay authentication is ready; otherwise why it is not (DiPlay's setup check). */
    fun authenticationError(context: Context): Throwable? =
        runCatching { DiPlayBootstrap.ensure(context, AirPlayPersistence.loadMfiTarget(context)) }.exceptionOrNull()
}
