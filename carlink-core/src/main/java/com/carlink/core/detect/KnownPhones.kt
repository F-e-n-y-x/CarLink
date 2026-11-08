package com.carlink.core.detect

import android.content.Context
import com.carlink.core.core.PhoneKind
import java.util.Locale

/**
 * Phones CarLink has identified, keyed by Bluetooth address. Filled when the driver picks a
 * phone type or a session succeeds, and seeded from DiPlay's chosen iPhone. DiAuto's Bluetooth
 * auto-start list is not a source: CarLink writes every Android Auto attempt's phone into it
 * (AaEngineAccess.setPokeTargets), so an iPhone wrongly started as Android Auto stayed "Android"
 * through it for good (module stereo, 2026-11-08).
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
        return out
    }

    fun forget(address: String) {
        prefs.edit().remove(normalize(address)).apply()
        names.edit().remove(normalize(address)).apply()
    }

    /**
     * Once per install: drops every stored ANDROID type that [connectedAsAndroid] cannot back up.
     * Up to 1.0.14 a Bluetooth guess (SDP records without iAP2) and every tap on Home saved the
     * type as fact, so an iPhone could stay "Android" for good; on a stereo whose Bluetooth is an
     * external module no later evidence ever corrects it (2026-11-08). Returns the addresses dropped.
     */
    fun dropUnprovenAndroid(connectedAsAndroid: (String) -> Boolean): List<String> {
        val done = app.getSharedPreferences(MIGRATIONS, Context.MODE_PRIVATE)
        if (done.getBoolean(KEY_UNPROVEN_ANDROID, false)) return emptyList()
        val dropped = prefs.all.filter { (address, kind) -> kind == PhoneKind.ANDROID.name && !connectedAsAndroid(address) }.keys.toList()
        prefs.edit().apply { dropped.forEach(::remove) }.apply()
        done.edit().putBoolean(KEY_UNPROVEN_ANDROID, true).apply()
        return dropped
    }

    /**
     * The phone's name as last seen (Android's paired list, a session, or the stereo's Bluetooth
     * module), so Home can list it where Android's own Bluetooth cannot (an external module).
     */
    fun nameOf(address: String): String? = names.getString(normalize(address), null)

    fun rememberName(address: String, name: String?) {
        val clean = name?.trim()?.takeIf { it.isNotEmpty() && !it.equals(address, ignoreCase = true) } ?: return
        if (nameOf(address) != clean) names.edit().putString(normalize(address), clean).apply()
    }

    private val names get() = app.getSharedPreferences(NAMES, Context.MODE_PRIVATE)

    /** DiPlay stores the iPhone chosen in its connection setup in its "diplay" preferences. */
    private fun carPlayPhone(): String? =
        app.getSharedPreferences("diplay", Context.MODE_PRIVATE).getString("phone_address", null)?.let(::normalize)

    companion object {
        private const val PREFS = "carlink_known_phones"
        private const val NAMES = "carlink_known_phone_names"
        private const val MIGRATIONS = "carlink_known_phones_migrations"
        private const val KEY_UNPROVEN_ANDROID = "unproven_android_dropped"
        fun normalize(address: String): String = address.trim().uppercase(Locale.ROOT)
    }
}
