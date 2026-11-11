package com.shilapi.xcertplay.orchestration

import android.content.Context

/**
 * CarLink (new file, docs/VENDOR_CHANGES.md): the iPhones, by USB serial number, that ignored the CarPlay mode
 * request on the enumeration of a session that had just ended and then connected on the mode they kept.
 *
 * On the tablet (iPhone, 2026-11-12) every restart on such an enumeration asked again (#335, [WiredSessionMemory]),
 * the iPhone ignored it, and the reuse after [CarPlayController.SAME_ENUMERATION_WAIT_MILLIS] reached the picture
 * in about 1.5 s. A remembered iPhone skips the request; it is forgotten when a skip is taken, and remembered
 * again only when that reuse reaches an AirPlay session, so a failed reuse falls back to the request next time.
 */
internal object WiredReuseMemory {
    private const val PREFS = "carlink_wired_reuse"

    fun knows(context: Context, serial: String): Boolean = prefs(context).getBoolean(serial, false)

    fun remember(context: Context, serial: String) = prefs(context).edit().putBoolean(serial, true).apply()

    fun forget(context: Context, serial: String) = prefs(context).edit().remove(serial).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
