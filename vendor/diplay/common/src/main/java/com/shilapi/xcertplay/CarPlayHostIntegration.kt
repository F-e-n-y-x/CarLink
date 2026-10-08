package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent

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
