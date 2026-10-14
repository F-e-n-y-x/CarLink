package com.carlink.core.detect

import android.content.Context
import com.carlink.core.core.PhoneKind
import java.util.Locale

/**
 * Phones CarLink has identified, keyed by Bluetooth address. Filled when the driver picks a
 * phone type or a session succeeds, and seeded from the phones each engine was set up with:
 * DiPlay's chosen iPhone and DiAuto's Bluetooth auto-start list.
 *
 * Written only by the main process; the ":aa" process never reads it.
 */
class KnownPhones(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun kindOf(address: String): PhoneKind? {
        val key = normalize(address)
        prefs.getString(key, null)?.let { stored -> return runCatching { PhoneKind.valueOf(stored) }.getOrNull() }
        if (key == carPlayPhone()) return PhoneKind.IPHONE
        if (key in androidAutoPhones()) return PhoneKind.ANDROID
        return null
    }

    fun remember(address: String, kind: PhoneKind) {
        if (kind == PhoneKind.UNKNOWN) return
        prefs.edit().putString(normalize(address), kind.name).apply()
    }

    /** Every phone CarLink knows, by normalized address (stored kinds, then the engines' own choices). */
    fun all(): Map<String, PhoneKind> {
        val out = linkedMapOf<String, PhoneKind>()
        prefs.all.forEach { (key, value) ->
            runCatching { PhoneKind.valueOf(value as String) }.getOrNull()?.let { out[normalize(key)] = it }
        }
        carPlayPhone()?.let { out.putIfAbsent(it, PhoneKind.IPHONE) }
        androidAutoPhones().forEach { out.putIfAbsent(it, PhoneKind.ANDROID) }
        return out
    }

    fun forget(address: String) {
        prefs.edit().remove(normalize(address)).apply()
    }

    /** DiPlay stores the iPhone chosen in its connection setup in its "diplay" preferences. */
    private fun carPlayPhone(): String? =
        app.getSharedPreferences("diplay", Context.MODE_PRIVATE).getString("phone_address", null)?.let(::normalize)

    /**
     * DiAuto's Bluetooth auto-start allowlist. It lives in the device-protected copy that DiAuto
     * syncs for direct boot, which this process can read without loading the engine.
     */
    private fun androidAutoPhones(): Set<String> = runCatching {
        com.andrerinas.openheadunit.utils.Settings.getAutoStartBtMacs(app)
    }.getOrDefault(emptySet()).map(::normalize).toSet()

    companion object {
        private const val PREFS = "carlink_known_phones"
        fun normalize(address: String): String = address.trim().uppercase(Locale.ROOT)
    }
}
