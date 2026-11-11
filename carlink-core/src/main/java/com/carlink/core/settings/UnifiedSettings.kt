package com.carlink.core.settings

import android.content.Context
import com.shilapi.xcertplay.AirPlayPersistence

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
    /** How far CarPlay music and video sound is buffered behind the picture ([AudioDelay]). */
    val audioDelay: AudioDelay = AudioDelay.NORMAL,
    val sendCarLocation: Boolean = true,
    val bydDirections: Boolean = true,
    val showAdvanced: Boolean = false,
    val labUnlocked: Boolean = false,
    /** Learned steering-wheel buttons ([WheelKeys]); applies from the next connection. */
    val wheelKeys: Map<WheelAction, Int> = emptyMap(),
    /** Sound levels in dB against the engines' own ([AudioLevels.PLAYBACK_DB]); from the next connection. */
    val mediaLevelDb: Int = 0,
    val navigationLevelDb: Int = 0,
    val callLevelDb: Int = 0,
    /** CarPlay calls play as media instead of as a phone call (GitHub #32); opt-in, may bring echo. */
    val callAudioAsMedia: Boolean = false,
    /** Press play once a session is back when the phone was playing music as the last one ended. */
    val resumeMusic: Boolean = false,
    /** Software gain on the microphone sent to the phone ([AudioLevels.MIC_DB]). */
    val micGainDb: Int = 0,
)

data class WifiDetails(val ssid: String = "", val password: String = "")

enum class ConnectionMethod { AUTOMATIC, SAME_WIFI, CAR_HOTSPOT, WIFI_DIRECT }

/** Quality versus load; [CUSTOM] when Advanced picked exact per-engine values. */
enum class Picture { AUTOMATIC, BEST, BALANCED, LIGHT, CUSTOM }

enum class InterfaceSize { SMALL, MEDIUM, LARGE, CUSTOM }

enum class DayNight { AUTOMATIC, DAY, NIGHT, SCHEDULE }

/** CarPlay's music buffer: lower keeps sound in step with the picture, higher rides out Wi-Fi gaps. */
enum class AudioDelay { LOW, NORMAL, HIGH }

/** Main-process storage for [UnifiedSettings]. */
class UnifiedSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
        // Until saved once, follow the Music buffer an earlier version set in DiPlay (500 ms or 1 s was High).
        audioDelay = enumOr(prefs.getString("audio_delay", null),
            if (AirPlayPersistence.loadMediaBufferMillis(appContext, wireless = true) >= 500) AudioDelay.HIGH else AudioDelay.NORMAL),
        sendCarLocation = prefs.getBoolean("send_location", true),
        bydDirections = prefs.getBoolean("byd_directions", true),
        showAdvanced = prefs.getBoolean("show_advanced", false),
        labUnlocked = prefs.getBoolean("lab_unlocked", false),
        wheelKeys = WheelKeys.decode(prefs.getString("wheel_keys", null)),
        mediaLevelDb = prefs.getInt("media_level_db", 0),
        navigationLevelDb = prefs.getInt("navigation_level_db", 0),
        callLevelDb = prefs.getInt("call_level_db", 0),
        callAudioAsMedia = prefs.getBoolean("call_audio_as_media", false),
        resumeMusic = prefs.getBoolean("resume_music", false),
        micGainDb = prefs.getInt("mic_gain_db", 0),
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
            .putString("audio_delay", settings.audioDelay.name)
            .putBoolean("send_location", settings.sendCarLocation)
            .putBoolean("byd_directions", settings.bydDirections)
            .putBoolean("show_advanced", settings.showAdvanced)
            .putBoolean("lab_unlocked", settings.labUnlocked)
            .putString("wheel_keys", WheelKeys.encode(settings.wheelKeys))
            .putInt("media_level_db", settings.mediaLevelDb)
            .putInt("navigation_level_db", settings.navigationLevelDb)
            .putInt("call_level_db", settings.callLevelDb)
            .putBoolean("call_audio_as_media", settings.callAudioAsMedia)
            .putBoolean("resume_music", settings.resumeMusic)
            .putInt("mic_gain_db", settings.micGainDb)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        const val PREFS = "carlink_unified"
    }
}
