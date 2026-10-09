package com.carlink.app.settings

import android.content.Context
import java.security.MessageDigest

/**
 * Networks an Android Auto phone refused to join with the password CarLink handed it (main
 * process only). Same Wi-Fi is skipped for Android Auto on such a network until the driver saves
 * a different password, so the next session goes straight to a path that works.
 */
class WirelessRefusals(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("carlink_wireless_refusals", Context.MODE_PRIVATE)

    fun isRefused(ssid: String, password: String): Boolean = prefs.getString(ssid, null) == fingerprint(password)

    fun record(ssid: String, password: String) {
        prefs.edit().putString(ssid, fingerprint(password)).apply()
    }

    companion object {
        // Only a digest is kept, so the password never lands in a second file.
        internal fun fingerprint(password: String): String =
            MessageDigest.getInstance("SHA-256").digest(("carlink:" + password).toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
