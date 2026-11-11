package com.carlink.core.detect

/**
 * The driver's "Screen type" (Settings > Display), when not Automatic: detection guesses wrong on
 * some units (a head unit with a 4G modem or a battery reading taken for a tablet, #33 #65; a tablet
 * fixed in a dash). Null = detect. Set in the main process by the app; the ':aa' process gets it
 * through Android Auto's session settings ([KEY]).
 */
object ScreenType {
    /** true = phone or tablet (turns, handheld sizes), false = car stereo, null = Automatic. */
    @Volatile var forcedHandheld: Boolean? = null

    /** Android Auto session-settings key: "handheld", "car" or "" (Automatic). */
    const val KEY = "carlink-screen-type"

    fun encode(): String = when (forcedHandheld) { true -> "handheld"; false -> "car"; null -> "" }
    fun decode(value: String?): Boolean? = when (value) { "handheld" -> true; "car" -> false; else -> null }
}
