// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import com.shilapi.xcertplay.host.R
import java.util.Locale

/** Persisted app language (system / en / zh / ar / ru / es) applied through configuration contexts. */
object AppLocale {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val SIMPLIFIED_CHINESE = "zh"
    const val ARABIC = "ar"
    const val RUSSIAN = "ru"
    const val SPANISH = "es"

    val ALL = listOf(SYSTEM, ENGLISH, SIMPLIFIED_CHINESE, ARABIC, RUSSIAN, SPANISH)

    private const val PREFS = "diplay"
    private const val KEY_LANGUAGE = "app_language"

    fun preference(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, SYSTEM) ?: SYSTEM

    fun save(context: Context, language: String) {
        require(language in ALL)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, language).apply()
    }

    /** Wraps [context] so resource lookups resolve against the chosen language; system stays as-is. */
    fun wrap(context: Context): Context {
        val locale = locale(preference(context)) ?: return context
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    /** Names stay in their native form for every language; only "system default" is localized. */
    fun displayName(context: Context, language: String): String = when (language) {
        SYSTEM -> context.getString(R.string.language_system_default)
        ENGLISH -> "English"
        SIMPLIFIED_CHINESE -> "简体中文"
        ARABIC -> "العربية"
        RUSSIAN -> "Русский"
        SPANISH -> "Español"
        else -> language
    }

    private fun locale(language: String): Locale? = when (language) {
        ENGLISH -> Locale.ENGLISH
        SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
        ARABIC -> Locale("ar")
        RUSSIAN -> Locale("ru")
        SPANISH -> Locale("es")
        else -> null
    }
}
