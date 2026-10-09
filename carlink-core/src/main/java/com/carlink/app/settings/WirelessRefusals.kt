package com.carlink.app.settings

import android.content.Context
import com.carlink.app.core.Engine
import java.security.MessageDigest

/**
 * Shared networks that did not work for an engine with the password saved for them (main process
 * only): an Android Auto phone refused to join, or an iPhone never reached the car over it. Same
 * Wi-Fi is skipped for that engine on such a network until the driver saves a different password,
 * so the next session goes straight to a path that works.
 *
 * Only read since connection v2: v1 recorded these entries; v2 fails the session on a refusal
 * instead (`connection/aa/AaSessionTracker`). Entries recorded by v1 are still honoured.
 */
class WirelessRefusals(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("carlink_wireless_refusals", Context.MODE_PRIVATE)

    fun isRefused(engine: Engine, ssid: String, password: String): Boolean =
        prefs.getString(key(engine, ssid), null) == fingerprint(password)

    // Android Auto's entries predate CarPlay's and keep their plain-SSID key.
    private fun key(engine: Engine, ssid: String) = if (engine == Engine.ANDROID_AUTO) ssid else "carplay:$ssid"

    companion object {
        // Only a digest is kept, so the password never lands in a second file.
        internal fun fingerprint(password: String): String =
            MessageDigest.getInstance("SHA-256").digest(("carlink:" + password).toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
