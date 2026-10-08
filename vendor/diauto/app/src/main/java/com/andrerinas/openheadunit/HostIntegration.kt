package com.andrerinas.openheadunit

import android.app.Activity
import android.content.Context
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
}
