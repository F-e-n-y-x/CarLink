package com.andrerinas.openheadunit

import android.app.Activity
import android.content.Context
import android.view.View
import com.andrerinas.openheadunit.main.MainActivity

/**
 * CarLink: lets an APK that embeds this engine choose the screens the engine opens on its own
 * (notification tap, boot launch, return after a Wi-Fi Direct prompt), the name its notifications
 * and media session carry, and what happens to its toasts. Standalone DiAuto keeps its defaults.
 */
object HostIntegration {
    @Volatile
    @JvmStatic
    var homeActivity: Class<out Activity> = MainActivity::class.java

    /** Title of the persistent service notification. Must be set before [App.onCreate]. */
    @Volatile
    @JvmStatic
    var appName: String = "Open Headunit"

    /** Name of the service notification channel, shown in the system's notification settings. */
    @Volatile
    @JvmStatic
    var serviceChannelName: String = "Headunit Service"

    @Volatile
    @JvmStatic
    var mediaSessionTag: String = "HeadunitRevived"

    /**
     * Sees every engine toast first; returns true when the host handled it (showed its own status
     * or chose to drop it). [forced] marks toasts the engine shows even with toasts turned off.
     */
    @Volatile
    @JvmStatic
    var toastHandler: ((context: Context, text: CharSequence, forced: Boolean) -> Boolean)? = null

    /** The projection's own chrome, for [chromeStyler]. */
    enum class Chrome { LOADING, RENDERER_BANNER, EXIT_MENU }

    /** Restyles the projection's chrome (type, shapes, marks) after the engine shows or repaints it. */
    @Volatile
    @JvmStatic
    var chromeStyler: ((root: View, chrome: Chrome) -> Unit)? = null

    fun styleChrome(root: View?, chrome: Chrome) {
        if (root != null) chromeStyler?.invoke(root, chrome)
    }

    /**
     * Label of the exit-menu entry that opens [homeActivity] in place of the engine's Quick
     * Settings (a host with its own settings screen). Null keeps Quick Settings.
     */
    @Volatile
    @JvmStatic
    var exitMenuHomeLabel: String? = null

    /**
     * Called (on an engine thread) each time a phone reports it could not join the access point it
     * was handed (Native AA over a hotspot). Lets a host switch to another wireless path instead of
     * re-offering the same network. Null keeps the engine's own retry.
     */
    @Volatile
    @JvmStatic
    var onPhoneWifiJoinFailed: ((context: Context) -> Unit)? = null
}
