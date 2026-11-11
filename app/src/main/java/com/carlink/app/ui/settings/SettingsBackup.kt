package com.carlink.app.ui.settings

import android.content.Context
import com.carlink.app.ui.UiPreferences
import com.carlink.app.ui.theme.RenderingMode
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfileMode
import com.carlink.core.settings.AdvancedSettings
import com.carlink.core.settings.CarBrand
import com.carlink.core.settings.ConnectionMethod
import com.carlink.core.settings.Control
import com.carlink.core.settings.DayNight
import com.carlink.core.settings.InterfaceSize
import com.carlink.core.settings.Picture
import org.json.JSONObject

/**
 * Settings › General › Back up / Restore: CarLink's own settings as one JSON file (the unified
 * settings with the wheel keys, CarLink's screen and start preferences, the car brand and the
 * engine values CarLink edits under Advanced). Nothing else leaves the device: no phones, no
 * history, no device grants (root mode, the car hotspot switch); Wi-Fi passwords only when the
 * driver says so. A restore checks every value before anything is written.
 */
object SettingsBackup {
    const val FORMAT = "carlink-settings"
    const val VERSION = 1

    private enum class Type { BOOL, INT, FLOAT, STRING }

    /** One stored value: its preferences file, key and type, and what it may hold. */
    private class Field(val file: String, val key: String, val type: Type, val allowed: Set<String>? = null,
                        val range: ClosedFloatingPointRange<Double>? = null, val secret: Boolean = false)

    private inline fun <reified T : Enum<T>> names() = enumValues<T>().map { it.name }.toSet()

    private const val UNIFIED = "carlink_unified"
    private const val CARLINK = "carlink"
    private val minutes = 0.0..(24 * 60 - 1).toDouble()

    // The keys of UnifiedSettingsStore, UiPreferences and CarLinkPreferences (SettingsBackupTest round-trips them).
    private val fields = listOf(
        Field(UNIFIED, "connection_method", Type.STRING, names<ConnectionMethod>()),
        Field(UNIFIED, "car_hotspot_ssid", Type.STRING),
        Field(UNIFIED, "car_hotspot_password", Type.STRING, secret = true),
        Field(UNIFIED, "home_wifi_ssid", Type.STRING),
        Field(UNIFIED, "home_wifi_password", Type.STRING, secret = true),
        Field(UNIFIED, "picture", Type.STRING, names<Picture>()),
        Field(UNIFIED, "interface_size", Type.STRING, names<InterfaceSize>()),
        Field(UNIFIED, "day_night", Type.STRING, names<DayNight>()),
        Field(UNIFIED, "night_start", Type.INT, range = minutes),
        Field(UNIFIED, "night_end", Type.INT, range = minutes),
        Field(UNIFIED, "right_hand_drive", Type.BOOL),
        Field(UNIFIED, "sound_car_bluetooth", Type.BOOL),
        Field(UNIFIED, "send_location", Type.BOOL),
        Field(UNIFIED, "byd_directions", Type.BOOL),
        Field(UNIFIED, "show_advanced", Type.BOOL),
        Field(UNIFIED, "lab_unlocked", Type.BOOL),
        Field(UNIFIED, "wheel_keys", Type.STRING),
        Field(CARLINK, "auto_connect_usb", Type.BOOL),
        Field(CARLINK, "auto_connect_bluetooth", Type.BOOL),
        Field(CARLINK, "start_on_boot", Type.BOOL),
        Field(CARLINK, "wireless_on_external_bluetooth", Type.BOOL),
        Field(CARLINK, "use_android_bluetooth", Type.BOOL),
        Field(CARLINK, "android_auto_on_screen", Type.BOOL),
        Field(CARLINK, "floating_switcher", Type.BOOL),
        Field(CARLINK, "update_auto_check", Type.BOOL),
        Field(CARLINK, "theme_mode", Type.STRING, names<ThemeMode>()),
        Field(CARLINK, "ui_profile_mode", Type.STRING, names<UiProfileMode>()),
        Field(CARLINK, "rendering_mode", Type.STRING, names<RenderingMode>()),
        Field(CARLINK, "interface_scale", Type.FLOAT,
            range = UiPreferences.INTERFACE_SCALES.min().toDouble()..UiPreferences.INTERFACE_SCALES.max().toDouble()),
    )

    /** A checked backup, ready to apply. [values] is keyed by preferences file, then key. */
    class Backup internal constructor(
        internal val values: Map<String, Map<String, Any>>,
        val carBrand: CarBrand?,
        val advanced: Map<String, String>,
        val withPasswords: Boolean,
        val createdMillis: Long?,
    ) {
        val count: Int get() = values.values.sumOf { it.size } + advanced.size + (if (carBrand != null) 1 else 0)
    }

    /** The current settings as JSON. [advanced] is the engines' Advanced/Lab values by id, when read. */
    fun export(context: Context, carBrand: CarBrand?, advanced: Map<String, String>?, withPasswords: Boolean,
               nowMillis: Long = System.currentTimeMillis()): String {
        val settings = JSONObject()
        fields.groupBy { it.file }.forEach { (file, list) ->
            val stored = context.getSharedPreferences(file, Context.MODE_PRIVATE).all
            val out = JSONObject()
            list.filter { withPasswords || !it.secret }.forEach { f -> stored[f.key]?.let { out.put(f.key, it) } }
            settings.put(file, out)
        }
        return JSONObject()
            .put("format", FORMAT).put("version", VERSION).put("created", nowMillis).put("passwords", withPasswords)
            .put("settings", settings)
            .apply { carBrand?.let { put("carBrand", it.name) } }
            .put("advanced", JSONObject(advanced.orEmpty().filterKeys { id -> AdvancedSettings.all.any { it.id == id } }))
            .toString(2)
    }

    /** Parses and checks [text]; throws [IllegalArgumentException] naming the first problem. Writes nothing. */
    fun parse(text: String): Backup {
        val root = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("not JSON") }
        require(root.optString("format") == FORMAT) { "not a CarLink settings backup" }
        val version = root.optInt("version", -1)
        require(version in 1..VERSION) { "backup version $version is newer than this CarLink" }
        val settings = root.optJSONObject("settings") ?: JSONObject()
        // Only the files the backup carries; an empty one still means "all defaults".
        val values = fields.groupBy { it.file }.filterKeys { settings.has(it) }.mapValues { (file, list) ->
            val obj = settings.optJSONObject(file) ?: throw IllegalArgumentException("bad section $file")
            list.filter { obj.has(it.key) }.associate { f -> f.key to check(f, obj.get(f.key)) }
        }
        val brand = root.optString("carBrand", "").ifEmpty { null }?.let { name ->
            requireNotNull(CarBrand.entries.firstOrNull { it.name == name }) { "unknown car brand $name" }
        }
        val advancedObj = root.optJSONObject("advanced") ?: JSONObject()
        val advanced = advancedObj.keys().asSequence().associateWith { id ->
            val setting = requireNotNull(AdvancedSettings.all.firstOrNull { it.id == id }) { "unknown Advanced setting $id" }
            val value = advancedObj.get(id).toString()
            val ok = when (val c = setting.control) {
                is Control.Toggle -> value == "true" || value == "false"
                is Control.Choice -> c.options.any { it.value == value }
            }
            require(ok) { "bad value for $id" }
            value
        }
        return Backup(values, brand, advanced, root.optBoolean("passwords", false), root.optLong("created").takeIf { it > 0 })
    }

    private fun check(f: Field, raw: Any): Any {
        val value: Any = when (f.type) {
            Type.BOOL -> raw as? Boolean
            Type.INT -> (raw as? Number)?.takeIf { it.toDouble() == it.toInt().toDouble() }?.toInt()
            Type.FLOAT -> (raw as? Number)?.toFloat()?.takeIf { it.isFinite() }
            Type.STRING -> raw as? String
        } ?: throw IllegalArgumentException("bad value for ${f.key}")
        require(f.allowed == null || value in f.allowed) { "bad value for ${f.key}" }
        require(f.range == null || (value as Number).toDouble() in f.range) { "${f.key} out of range" }
        return value
    }

    /**
     * Writes [backup]'s preferences: each file it carries is replaced field by field (a field it
     * lacks goes back to its default), except Wi-Fi passwords when it was made without them. The
     * car brand and the engine values are the caller's (they go through their own setters).
     */
    fun applyPreferences(context: Context, backup: Backup) {
        fields.groupBy { it.file }.forEach { (file, list) ->
            val incoming = backup.values[file] ?: return@forEach
            val edit = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
            list.forEach { f ->
                when (val v = incoming[f.key]) {
                    null -> if (!f.secret || backup.withPasswords) edit.remove(f.key)
                    is Boolean -> edit.putBoolean(f.key, v)
                    is Int -> edit.putInt(f.key, v)
                    is Float -> edit.putFloat(f.key, v)
                    is String -> edit.putString(f.key, v)
                }
            }
            edit.commit()
        }
    }
}
