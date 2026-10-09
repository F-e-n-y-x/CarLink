package com.carlink.core.settings

import android.content.Context

/**
 * The settings a driver sets once for both CarPlay and Android Auto (design/REBUILD_PLAN.md,
 * "Essentials"). Each value says what the driver wants; `connection/CarPlaySettings` and
 * `connection/aa/AaSessionSettings` translate it into each
 * engine's own storage. [Picture.CUSTOM] and [InterfaceSize.CUSTOM] mean an Advanced, per-engine
 * value was set and the unified choice must not overwrite it.
 */
data class UnifiedSettings(
    val connectionMethod: ConnectionMethod = ConnectionMethod.AUTOMATIC,
    val carHotspot: WifiDetails = WifiDetails(),
    val homeWifi: WifiDetails = WifiDetails(),
    val picture: Picture = Picture.AUTOMATIC,
    val interfaceSize: InterfaceSize = InterfaceSize.MEDIUM,
    val dayNight: DayNight = DayNight.AUTOMATIC,
    /** Minutes after midnight, for [DayNight.SCHEDULE]. */
    val nightStartMinute: Int = 19 * 60,
    val nightEndMinute: Int = 7 * 60,
    val rightHandDrive: Boolean = false,
    val soundThroughCarBluetooth: Boolean = false,
    val sendCarLocation: Boolean = true,
    val bydDirections: Boolean = true,
    val showAdvanced: Boolean = false,
    val labUnlocked: Boolean = false,
)

data class WifiDetails(val ssid: String = "", val password: String = "")

enum class ConnectionMethod { AUTOMATIC, SAME_WIFI, CAR_HOTSPOT, WIFI_DIRECT }

/** Quality versus load; [CUSTOM] when Advanced picked exact per-engine values. */
enum class Picture { AUTOMATIC, BEST, BALANCED, LIGHT, CUSTOM }

enum class InterfaceSize { SMALL, MEDIUM, LARGE, CUSTOM }

enum class DayNight { AUTOMATIC, DAY, NIGHT, SCHEDULE }

/** Main-process storage for [UnifiedSettings]. */
class UnifiedSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load() = UnifiedSettings(
        connectionMethod = enumOr(prefs.getString("connection_method", null), ConnectionMethod.AUTOMATIC),
        carHotspot = WifiDetails(prefs.getString("car_hotspot_ssid", "")!!, prefs.getString("car_hotspot_password", "")!!),
        homeWifi = WifiDetails(prefs.getString("home_wifi_ssid", "")!!, prefs.getString("home_wifi_password", "")!!),
        picture = enumOr(prefs.getString("picture", null), Picture.AUTOMATIC),
        interfaceSize = enumOr(prefs.getString("interface_size", null), InterfaceSize.MEDIUM),
        dayNight = enumOr(prefs.getString("day_night", null), DayNight.AUTOMATIC),
        nightStartMinute = prefs.getInt("night_start", 19 * 60),
        nightEndMinute = prefs.getInt("night_end", 7 * 60),
        rightHandDrive = prefs.getBoolean("right_hand_drive", false),
        soundThroughCarBluetooth = prefs.getBoolean("sound_car_bluetooth", false),
        sendCarLocation = prefs.getBoolean("send_location", true),
        bydDirections = prefs.getBoolean("byd_directions", true),
        showAdvanced = prefs.getBoolean("show_advanced", false),
        labUnlocked = prefs.getBoolean("lab_unlocked", false),
    )

    fun save(settings: UnifiedSettings) {
        prefs.edit()
            .putString("connection_method", settings.connectionMethod.name)
            .putString("car_hotspot_ssid", settings.carHotspot.ssid)
            .putString("car_hotspot_password", settings.carHotspot.password)
            .putString("home_wifi_ssid", settings.homeWifi.ssid)
            .putString("home_wifi_password", settings.homeWifi.password)
            .putString("picture", settings.picture.name)
            .putString("interface_size", settings.interfaceSize.name)
            .putString("day_night", settings.dayNight.name)
            .putInt("night_start", settings.nightStartMinute)
            .putInt("night_end", settings.nightEndMinute)
            .putBoolean("right_hand_drive", settings.rightHandDrive)
            .putBoolean("sound_car_bluetooth", settings.soundThroughCarBluetooth)
            .putBoolean("send_location", settings.sendCarLocation)
            .putBoolean("byd_directions", settings.bydDirections)
            .putBoolean("show_advanced", settings.showAdvanced)
            .putBoolean("lab_unlocked", settings.labUnlocked)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        const val PREFS = "carlink_unified"
    }
}
