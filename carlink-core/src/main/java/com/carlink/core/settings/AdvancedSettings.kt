package com.carlink.core.settings

import android.content.Context
import android.os.Bundle
import com.carlink.core.core.Engine
import com.carlink.core.engine.AndroidAutoSettings
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayDock

/** Where an Advanced or Lab setting appears (the same categories as the Essentials). */
enum class SettingsCategory { CONNECTION, DISPLAY, SOUND, NAVIGATION, CAR, GENERAL }

/** Advanced: shown with "Show advanced". Lab: experimental or debug, unlocked from About. */
enum class Tier { ADVANCED, LAB }

/** When a change takes effect, shown on the row so nothing surprises the driver. */
enum class Applies { NOW, NEXT_CONNECTION }

/** Which unified Essential an Advanced value overrides; editing it marks that Essential CUSTOM. */
enum class Overrides { PICTURE, INTERFACE_SIZE }

data class SettingOption(val value: String, val label: String)

sealed class Control {
    class Toggle(val read: (EngineAccess) -> Boolean, val write: (EngineAccess, Boolean) -> Unit) : Control()
    class Choice(
        val options: List<SettingOption>,
        val read: (EngineAccess) -> String,
        val write: (EngineAccess, String) -> Unit,
    ) : Control()
}

/** One engine-specific setting, edited natively in CarLink (design/REBUILD_PLAN.md "Advanced"). */
data class AdvancedSetting(
    val id: String,
    val engine: Engine,
    val category: SettingsCategory,
    val tier: Tier,
    val title: String,
    val description: String,
    val applies: Applies,
    val control: Control,
    val overrides: Overrides? = null,
)

/**
 * Reads and writes engine values. CarPlay settings belong to the main process, so they go
 * through DiPlay's own typed persistence. Android Auto settings belong to ":aa": they are read
 * once into [androidAuto] (call [loadAndroidAuto] off the main thread) and written through the
 * settings bridge.
 */
class EngineAccess(val context: Context) {
    @Volatile var androidAuto: Bundle = Bundle()
        private set

    fun loadAndroidAuto() {
        androidAuto = AndroidAutoSettings.read(context)
    }

    fun aaInt(key: String, default: Int): Int = if (androidAuto.containsKey(key)) androidAuto.getInt(key, default) else default
    fun aaBool(key: String, default: Boolean): Boolean =
        if (androidAuto.containsKey(key)) androidAuto.getBoolean(key, default) else default
    fun aaString(key: String, default: String): String = androidAuto.getString(key) ?: default

    fun putAa(key: String, value: Any) {
        val bundle = Bundle()
        when (value) {
            is Int -> bundle.putInt(key, value)
            is Boolean -> bundle.putBoolean(key, value)
            is String -> bundle.putString(key, value)
        }
        androidAuto = Bundle(androidAuto).apply { putAll(bundle) }
        AndroidAutoSettings.write(context, bundle)
    }
}

private fun options(vararg pairs: Pair<Any, String>) = pairs.map { SettingOption(it.first.toString(), it.second) }

private fun carPlayToggle(read: (Context) -> Boolean, write: (Context, Boolean) -> Unit) =
    Control.Toggle({ read(it.context) }, { access, value -> write(access.context, value) })

private fun carPlayIntChoice(options: List<SettingOption>, read: (Context) -> Int, write: (Context, Int) -> Unit) =
    Control.Choice(options, { read(it.context).toString() }, { access, value -> write(access.context, value.toInt()) })

private fun aaToggle(key: String, default: Boolean) =
    Control.Toggle({ it.aaBool(key, default) }, { access, value -> access.putAa(key, value) })

private fun aaIntChoice(key: String, default: Int, options: List<SettingOption>) =
    Control.Choice(options, { it.aaInt(key, default).toString() }, { access, value -> access.putAa(key, value.toInt()) })

/** Android audio stream choices DiPlay accepts (0 = by usage, otherwise a legacy stream type). */
private val audioStreams = options(0 to "Automatic", 3 to "Media", 4 to "Alarm", 5 to "Notification", 1 to "System", 10 to "Accessibility")

/**
 * The curated engine settings (from the full inventories of both engines). Unsafe or
 * firmware-specific options (cluster geometry, ADB-only vehicle data, identity) stay out.
 */
object AdvancedSettings {
    val all: List<AdvancedSetting> = listOf(
        // ---------- CarPlay: display ----------
        AdvancedSetting("cp_scale", Engine.CARPLAY, SettingsCategory.DISPLAY, Tier.ADVANCED,
            "Resolution", "Picture size relative to the screen. Higher is sharper and works the head unit harder.",
            Applies.NEXT_CONNECTION, carPlayIntChoice(options(50 to "50 %", 75 to "75 %", 100 to "100 %", 125 to "125 %", 150 to "150 %"),
                AirPlayPersistence::loadDisplayScalePercent, AirPlayPersistence::saveDisplayScalePercent), Overrides.PICTURE),
        AdvancedSetting("cp_fps", Engine.CARPLAY, SettingsCategory.DISPLAY, Tier.ADVANCED,
            "Frame rate", "60 fps is smoother; 30 fps is lighter on older head units.",
            Applies.NEXT_CONNECTION, carPlayIntChoice(options(30 to "30 fps", 60 to "60 fps"),
                AirPlayPersistence::loadFps, AirPlayPersistence::saveFps), Overrides.PICTURE),
        AdvancedSetting("cp_width", Engine.CARPLAY, SettingsCategory.DISPLAY, Tier.ADVANCED,
            "Screen width reported to iPhone", "A wider screen makes CarPlay's icons and text smaller.",
            Applies.NEXT_CONNECTION, carPlayIntChoice(options(250 to "250 mm (largest)", 300 to "300 mm", 350 to "350 mm", 400 to "400 mm (smallest)"),
                AirPlayPersistence::loadWidthPhysicalMm, AirPlayPersistence::saveWidthPhysicalMm), Overrides.INTERFACE_SIZE),
        AdvancedSetting("cp_dock", Engine.CARPLAY, SettingsCategory.DISPLAY, Tier.ADVANCED,
            "Dock position", "Where CarPlay shows its row of apps.",
            Applies.NEXT_CONNECTION, Control.Choice(
                options(CarPlayDock.AUTOMATIC.name to "Automatic", CarPlayDock.DRIVER_SIDE.name to "Driver side", CarPlayDock.BOTTOM.name to "Bottom"),
                { CarPlayDock.load(it.context).name }, { access, value -> CarPlayDock.save(access.context, CarPlayDock.valueOf(value)) })),
        AdvancedSetting("cp_hide_top", Engine.CARPLAY, SettingsCategory.DISPLAY, Tier.ADVANCED,
            "Hide the status bar", "Use the whole screen for CarPlay.", Applies.NOW,
            carPlayToggle(AirPlayPersistence::loadHideTopBar, AirPlayPersistence::saveHideTopBar)),
        AdvancedSetting("cp_hide_bottom", Engine.CARPLAY, SettingsCategory.DISPLAY, Tier.ADVANCED,
            "Hide the navigation bar", "Use the whole screen for CarPlay.", Applies.NOW,
            carPlayToggle(AirPlayPersistence::loadHideBottomBar, AirPlayPersistence::saveHideBottomBar)),
        AdvancedSetting("cp_hevc", Engine.CARPLAY, SettingsCategory.DISPLAY, Tier.ADVANCED,
            "Efficient video (HEVC)", "Sharper picture for the same Wi-Fi bandwidth. Needs a hardware HEVC decoder.",
            Applies.NEXT_CONNECTION, carPlayToggle(AirPlayPersistence::loadHevcEnabled, AirPlayPersistence::saveHevcEnabled)),
        AdvancedSetting("cp_adapt_pip", Engine.CARPLAY, SettingsCategory.DISPLAY, Tier.ADVANCED,
            "Re-negotiate size in split screen", "Off scales the picture; on asks the iPhone for the new size.",
            Applies.NOW, carPlayToggle(AirPlayPersistence::loadAdaptPipResolution, AirPlayPersistence::saveAdaptPipResolution)),
        AdvancedSetting("cp_hevc_sw", Engine.CARPLAY, SettingsCategory.DISPLAY, Tier.LAB,
            "Software HEVC decoder", "Decode HEVC on the processor instead of the video chip.",
            Applies.NEXT_CONNECTION, carPlayToggle(AirPlayPersistence::loadHevcSoftwareDecoderEnabled,
                AirPlayPersistence::saveHevcSoftwareDecoderEnabled)),
        AdvancedSetting("cp_smooth", Engine.CARPLAY, SettingsCategory.DISPLAY, Tier.LAB,
            "Smooth video (experimental)", "Paces frames by their timestamps.",
            Applies.NEXT_CONNECTION, carPlayToggle(AirPlayPersistence::loadSmoothVideo) { c, v ->
                c.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().putBoolean("smooth_video", v).apply()
            }),

        // ---------- CarPlay: sound ----------
        AdvancedSetting("cp_media_stream", Engine.CARPLAY, SettingsCategory.SOUND, Tier.ADVANCED,
            "Media audio stream", "Change this if CarPlay music plays too quietly or on the wrong channel.",
            Applies.NEXT_CONNECTION, carPlayIntChoice(audioStreams, AirPlayPersistence::loadMediaAudioChannel, AirPlayPersistence::saveMediaAudioChannel)),
        AdvancedSetting("cp_nav_stream", Engine.CARPLAY, SettingsCategory.SOUND, Tier.ADVANCED,
            "Navigation audio stream", "Channel used for CarPlay directions.",
            Applies.NEXT_CONNECTION, carPlayIntChoice(audioStreams, AirPlayPersistence::loadNavigationAudioChannel,
                AirPlayPersistence::saveNavigationAudioChannel)),
        AdvancedSetting("cp_buffer", Engine.CARPLAY, SettingsCategory.SOUND, Tier.ADVANCED,
            "Music buffer", "A longer buffer rides out Wi-Fi hiccups but adds delay.",
            Applies.NEXT_CONNECTION, carPlayIntChoice(options(300 to "Short (300 ms)", 500 to "Medium (500 ms)", 1000 to "Long (1 s)"),
                // DiPlay 0.2.19 keeps separate wired and wireless buffers; this one setting sets both.
                { c -> AirPlayPersistence.loadMediaBufferMillis(c, wireless = true) },
                { c, v ->
                    AirPlayPersistence.saveMediaBufferMillis(c, wireless = true, millis = v)
                    AirPlayPersistence.saveMediaBufferMillis(c, wireless = false, millis = v)
                })),
        AdvancedSetting("cp_focus", Engine.CARPLAY, SettingsCategory.SOUND, Tier.ADVANCED,
            "Take audio focus", "Pause the car's own audio while CarPlay plays.",
            Applies.NEXT_CONNECTION, carPlayToggle(AirPlayPersistence::loadAudioFocusEnabled, AirPlayPersistence::saveAudioFocusEnabled)),
        AdvancedSetting("cp_yield", Engine.CARPLAY, SettingsCategory.SOUND, Tier.ADVANCED,
            "Mute music for car announcements", "Briefly silence CarPlay when the car plays a chime or message.",
            Applies.NEXT_CONNECTION, carPlayToggle(AirPlayPersistence::loadAudioFocusAutoYield, AirPlayPersistence::saveAudioFocusAutoYield)),
        AdvancedSetting("cp_buffered_music", Engine.CARPLAY, SettingsCategory.SOUND, Tier.ADVANCED,
            "Buffered music", "Lets the iPhone send music ahead for steadier playback.",
            Applies.NEXT_CONNECTION, carPlayToggle(AirPlayPersistence::loadMainBufferedAudio) { c, v ->
                c.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().putBoolean("main_buffered_audio", v).apply()
            }),
        AdvancedSetting("cp_echo", Engine.CARPLAY, SettingsCategory.SOUND, Tier.ADVANCED,
            "Call echo cancellation", "Reduce the echo the other person hears on calls.",
            Applies.NEXT_CONNECTION, carPlayToggle(AirPlayPersistence::loadCallEchoCancellation, AirPlayPersistence::saveCallEchoCancellation)),
        AdvancedSetting("cp_voice", Engine.CARPLAY, SettingsCategory.SOUND, Tier.ADVANCED,
            "Clearer call voices", "Filter road noise from your voice on calls.",
            Applies.NEXT_CONNECTION, carPlayToggle(AirPlayPersistence::loadCallVoiceFilter, AirPlayPersistence::saveCallVoiceFilter)),

        // ---------- CarPlay: connection, car, lab ----------
        AdvancedSetting("cp_p2p_channel", Engine.CARPLAY, SettingsCategory.CONNECTION, Tier.ADVANCED,
            "Wi-Fi Direct channel", "Pin CarPlay's Wi-Fi Direct link to a band or channel if it drops.",
            Applies.NEXT_CONNECTION, carPlayIntChoice(options(0 to "Automatic", -5 to "Automatic, 5 GHz", -2 to "Automatic, 2.4 GHz",
                36 to "Channel 36", 149 to "Channel 149", 1 to "Channel 1", 6 to "Channel 6", 11 to "Channel 11"),
                AirPlayPersistence::loadWifiP2pPreferredChannel, AirPlayPersistence::saveWifiP2pPreferredChannel)),
        AdvancedSetting("cp_fingers", Engine.CARPLAY, SettingsCategory.CAR, Tier.ADVANCED,
            "Gesture for the session menu", "Swipe down with this many fingers on CarPlay to open its menu.",
            Applies.NOW, carPlayIntChoice(options(2 to "Two fingers", 3 to "Three fingers", 4 to "Four fingers"),
                AirPlayPersistence::loadSettingsGestureFingers) { c, v ->
                c.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().putInt("settings_gesture_fingers", v).apply()
            }),
        AdvancedSetting("cp_debug", Engine.CARPLAY, SettingsCategory.GENERAL, Tier.LAB,
            "CarPlay debug logs", "Record detailed CarPlay logs for troubleshooting.",
            Applies.NEXT_CONNECTION, carPlayToggle(AirPlayPersistence::loadDebugLogsEnabled, AirPlayPersistence::saveDebugLogsEnabled)),

        // ---------- Android Auto: display ----------
        AdvancedSetting("aa_resolution", Engine.ANDROID_AUTO, SettingsCategory.DISPLAY, Tier.ADVANCED,
            "Resolution", "Higher is sharper and needs more Wi-Fi and decoding power.",
            Applies.NEXT_CONNECTION, aaIntChoice("resolutionId", 0, options(0 to "Automatic", 1 to "800 × 480", 2 to "1280 × 720",
                3 to "1920 × 1080", 4 to "2560 × 1440")), Overrides.PICTURE),
        AdvancedSetting("aa_fps", Engine.ANDROID_AUTO, SettingsCategory.DISPLAY, Tier.ADVANCED,
            "Frame rate", "60 fps is smoother; 30 fps is lighter on older head units.",
            Applies.NEXT_CONNECTION, aaIntChoice("fps-limit", 60, options(30 to "30 fps", 60 to "60 fps")), Overrides.PICTURE),
        AdvancedSetting("aa_dpi", Engine.ANDROID_AUTO, SettingsCategory.DISPLAY, Tier.ADVANCED,
            "Pixel density (DPI)", "Higher DPI makes Android Auto's controls bigger.",
            Applies.NEXT_CONNECTION, aaIntChoice("dpi-pixel-density", 0, options(0 to "Automatic", 120 to "120", 160 to "160",
                200 to "200", 240 to "240", 280 to "280", 320 to "320")), Overrides.INTERFACE_SIZE),
        AdvancedSetting("aa_codec", Engine.ANDROID_AUTO, SettingsCategory.DISPLAY, Tier.ADVANCED,
            "Video codec", "H.265 is sharper at the same bandwidth if the head unit decodes it well.",
            Applies.NEXT_CONNECTION, Control.Choice(options("Auto" to "Automatic", "H.264" to "H.264", "H.265" to "H.265"),
                { it.aaString("video-codec", "Auto").let { v -> if (v == "H.264" || v == "H.265") v else "Auto" } },
                // Always the canonical value: DiAuto's own picker stored a translated "Auto".
                { access, value -> access.putAa("video-codec", value) })),
        AdvancedSetting("aa_screen_mode", Engine.ANDROID_AUTO, SettingsCategory.DISPLAY, Tier.ADVANCED,
            "Full screen", "How Android Auto uses the screen around the car's bars.",
            Applies.NOW, aaIntChoice("fullscreen-mode", 1, options(1 to "Full screen", 3 to "Full screen, avoid the notch",
                2 to "Keep the status bar", 0 to "Keep both bars"))),
        AdvancedSetting("aa_stretch", Engine.ANDROID_AUTO, SettingsCategory.DISPLAY, Tier.ADVANCED,
            "Stretch to fill", "Fill the screen even if the picture's shape differs.",
            Applies.NEXT_CONNECTION, aaToggle("stretch_to_fill", true)),
        AdvancedSetting("aa_sw_decode", Engine.ANDROID_AUTO, SettingsCategory.DISPLAY, Tier.ADVANCED,
            "Software video decoding", "Try this if Android Auto shows a black or broken picture.",
            Applies.NEXT_CONNECTION, aaToggle("force-software-decoding", false)),
        AdvancedSetting("aa_view_mode", Engine.ANDROID_AUTO, SettingsCategory.DISPLAY, Tier.LAB,
            "Video surface", "How the picture is drawn on screen.",
            Applies.NOW, aaIntChoice("view-mode", 1, options(0 to "SurfaceView", 1 to "TextureView", 2 to "OpenGL"))),
        AdvancedSetting("aa_sw_decoder", Engine.ANDROID_AUTO, SettingsCategory.DISPLAY, Tier.LAB,
            "Software decoder", "Which decoder software decoding uses.",
            Applies.NEXT_CONNECTION, aaIntChoice("software-video-decoder", 1, options(0 to "Head unit's own", 1 to "Built-in FFmpeg"))),

        // ---------- Android Auto: sound and voice ----------
        AdvancedSetting("aa_sink", Engine.ANDROID_AUTO, SettingsCategory.SOUND, Tier.ADVANCED,
            "Play Android Auto audio here", "Off keeps all Android Auto sound on the phone.",
            Applies.NEXT_CONNECTION, aaToggle("enable-audio-sink", true)),
        AdvancedSetting("aa_separate", Engine.ANDROID_AUTO, SettingsCategory.SOUND, Tier.ADVANCED,
            "Separate audio streams", "Use different car channels for music, directions and the assistant.",
            Applies.NEXT_CONNECTION, aaToggle("separate-audio-streams", false)),
        AdvancedSetting("aa_static_focus", Engine.ANDROID_AUTO, SettingsCategory.SOUND, Tier.ADVANCED,
            "Always give audio to the phone", "Fixes music that keeps pausing on some head units.",
            Applies.NEXT_CONNECTION, aaToggle("static-audio-focus", false)),
        AdvancedSetting("aa_playback_focus", Engine.ANDROID_AUTO, SettingsCategory.SOUND, Tier.ADVANCED,
            "Pause other audio", "Whether the car's own audio pauses while Android Auto plays.",
            Applies.NEXT_CONNECTION, aaIntChoice("playback-focus-mode", 0, options(0 to "Automatic", 1 to "Always", 2 to "Never"))),
        AdvancedSetting("aa_media_gain", Engine.ANDROID_AUTO, SettingsCategory.SOUND, Tier.ADVANCED,
            "Music volume", "Adjust Android Auto music against other sources.",
            Applies.NOW, aaIntChoice("media-volume-offset", 0, options(-50 to "−50 %", -25 to "−25 %", 0 to "Normal", 25 to "+25 %", 50 to "+50 %"))),
        AdvancedSetting("aa_nav_gain", Engine.ANDROID_AUTO, SettingsCategory.SOUND, Tier.ADVANCED,
            "Directions volume", "Adjust spoken directions against music.",
            Applies.NOW, aaIntChoice("navigation-volume-offset", 0, options(-50 to "−50 %", -25 to "−25 %", 0 to "Normal", 25 to "+25 %", 50 to "+50 %"))),
        AdvancedSetting("aa_assistant_gain", Engine.ANDROID_AUTO, SettingsCategory.SOUND, Tier.ADVANCED,
            "Assistant volume", "Adjust Google Assistant against music.",
            Applies.NOW, aaIntChoice("assistant-volume-offset", 0, options(-50 to "−50 %", -25 to "−25 %", 0 to "Normal", 25 to "+25 %", 50 to "+50 %"))),
        AdvancedSetting("aa_mic_source", Engine.ANDROID_AUTO, SettingsCategory.SOUND, Tier.ADVANCED,
            "Microphone", "Which microphone Android Auto listens with.",
            Applies.NOW, aaIntChoice("mic-input-source", 0, options(0 to "Automatic", 1 to "Raw microphone", 2 to "Voice recognition",
                3 to "Voice communication", 4 to "Bluetooth headset"))),
        AdvancedSetting("aa_mic_aec", Engine.ANDROID_AUTO, SettingsCategory.SOUND, Tier.ADVANCED,
            "Microphone echo cancellation", "Use the head unit's echo cancellation on your voice.",
            Applies.NOW, aaToggle("mic-echo-canceler", false)),
        AdvancedSetting("aa_mic_ns", Engine.ANDROID_AUTO, SettingsCategory.SOUND, Tier.ADVANCED,
            "Microphone noise suppression", "Reduce road noise in your voice.",
            Applies.NOW, aaToggle("mic-noise-suppressor", false)),
        AdvancedSetting("aa_aac", Engine.ANDROID_AUTO, SettingsCategory.SOUND, Tier.LAB,
            "AAC audio", "Compressed audio instead of raw PCM.",
            Applies.NEXT_CONNECTION, aaToggle("use-aac-audio", false)),

        // ---------- Android Auto: car, connection, lab ----------
        AdvancedSetting("aa_rotary", Engine.ANDROID_AUTO, SettingsCategory.CAR, Tier.ADVANCED,
            "Rotary controller", "Tell Android Auto the car has a knob or touchpad.",
            Applies.NEXT_CONNECTION, aaToggle("enable-rotary", false)),
        AdvancedSetting("aa_media_keys", Engine.ANDROID_AUTO, SettingsCategory.CAR, Tier.ADVANCED,
            "Send media buttons to Android Auto", "Whether steering-wheel media keys control Android Auto.",
            Applies.NOW, aaIntChoice("media-key-routing", 0, options(0 to "Always", 1 to "Unless Bluetooth audio is on", 2 to "Never"))),
        AdvancedSetting("aa_version_exchange", Engine.ANDROID_AUTO, SettingsCategory.CONNECTION, Tier.LAB,
            "Modern wireless handshake", "Newer handshake some phones expect.",
            Applies.NEXT_CONNECTION, aaToggle("native-wifi-version-exchange", false)),
        AdvancedSetting("aa_p2p_24", Engine.ANDROID_AUTO, SettingsCategory.CONNECTION, Tier.LAB,
            "Force Wi-Fi Direct onto 2.4 GHz", "For head units whose 5 GHz Wi-Fi Direct is unreliable.",
            Applies.NEXT_CONNECTION, aaToggle("debug-force-p2p-band-24", false)),
        AdvancedSetting("aa_fps_counter", Engine.ANDROID_AUTO, SettingsCategory.GENERAL, Tier.LAB,
            "Show frame counter", "Overlay the Android Auto frame rate.", Applies.NOW, aaToggle("show-fps-counter", false)),
        AdvancedSetting("aa_low_latency", Engine.ANDROID_AUTO, SettingsCategory.GENERAL, Tier.LAB,
            "Low-latency decoder", "Ask the decoder for its low-latency mode.", Applies.NEXT_CONNECTION, aaToggle("debug-video-low-latency", false)),
    )

    fun forCategory(category: SettingsCategory, showAdvanced: Boolean, lab: Boolean): List<AdvancedSetting> =
        all.filter { it.category == category && ((it.tier == Tier.ADVANCED && showAdvanced) || (it.tier == Tier.LAB && lab)) }
}
