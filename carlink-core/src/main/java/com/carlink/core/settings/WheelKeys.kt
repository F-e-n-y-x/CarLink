package com.carlink.core.settings

import android.view.KeyEvent

/**
 * What a learned steering-wheel button does (Settings › Car › Steering-wheel buttons). Each action
 * is the standard Android key both engines already handle: DiPlay's CarPlay screen opens Siri on
 * VOICE_ASSIST, answers or ends a CarPlay call on CALL/ENDCALL and sends the media keys to CarPlay;
 * DiAuto sends all six to the phone (VOICE_ASSIST as Android Auto's SEARCH).
 */
enum class WheelAction(val standardKeyCode: Int) {
    VOICE(KeyEvent.KEYCODE_VOICE_ASSIST),
    ANSWER(KeyEvent.KEYCODE_CALL),
    HANG_UP(KeyEvent.KEYCODE_ENDCALL),
    PLAY_PAUSE(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
    NEXT(KeyEvent.KEYCODE_MEDIA_NEXT),
    PREVIOUS(KeyEvent.KEYCODE_MEDIA_PREVIOUS),
}

/**
 * The learned buttons, one key code per action, and their translation for each engine. Pure, so it
 * is unit tested; stored in [UnifiedSettings.wheelKeys] and written at each session start.
 */
object WheelKeys {
    /**
     * Keys a button cannot be learned as: Back cancels learning, volume must keep working (Android
     * Auto never sees it anyway), and Home and Power belong to the system.
     */
    fun canLearn(keyCode: Int): Boolean = keyCode > KeyEvent.KEYCODE_UNKNOWN && keyCode !in reserved

    private val reserved = setOf(
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
    )

    /** [keys] with [keyCode] for [action]; a key belongs to one action, so it leaves any other. */
    fun assign(keys: Map<WheelAction, Int>, action: WheelAction, keyCode: Int): Map<WheelAction, Int> =
        keys.filterValues { it != keyCode } + (action to keyCode)

    /** Physical key code → the standard key code, for the CarPlay screen. A key that already is its action's key is left out. */
    fun remap(keys: Map<WheelAction, Int>): Map<Int, Int> =
        keys.entries.filter { it.value != it.key.standardKeyCode }.associate { it.value to it.key.standardKeyCode }

    /** DiAuto's `key-codes` set (Settings.keyCodes): "logical-physical", applied by CommManager.sendKey to every route. */
    fun diAutoKeyCodes(keys: Map<WheelAction, Int>): Set<String> =
        remap(keys).entries.map { (physical, standard) -> "$standard-$physical" }.toSet()

    /** A learned answer or hang-up key needs DiPlay's call-key handling on (`BydOutputSettings.carPlayCallControls`). */
    fun needsCarPlayCallKeys(keys: Map<WheelAction, Int>): Boolean =
        WheelAction.ANSWER in keys || WheelAction.HANG_UP in keys

    fun encode(keys: Map<WheelAction, Int>): String = keys.entries.joinToString(",") { "${it.key.name}=${it.value}" }

    /** Unknown actions and broken entries (another version, a hand edit) are dropped. */
    fun decode(text: String?): Map<WheelAction, Int> = text.orEmpty().split(',').mapNotNull { entry ->
        val (name, code) = entry.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        val action = WheelAction.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
        code.toIntOrNull()?.takeIf(::canLearn)?.let { action to it }
    }.toMap()
}
