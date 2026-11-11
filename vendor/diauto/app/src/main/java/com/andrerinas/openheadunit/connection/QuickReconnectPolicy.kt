package com.andrerinas.openheadunit.connection

import android.content.Context
import com.andrerinas.openheadunit.utils.Settings

// CarLink: new file (see docs/VENDOR_CHANGES.md). The idea is aa-proxy-rs's `quick_reconnect`,
// reimplemented here, not copied.

/**
 * Whether a wireless session that dropped is offered the network again on the Bluetooth channel
 * still held for it (WifiStartRequest on the same RFCOMM socket), instead of the full re-arm
 * (manager stop, 1.5 s, listeners and wake poke again). Off unless the host turns it on.
 */
object QuickReconnectPolicy {
    /** The host's switch in DiAuto's preferences; absent means off. */
    const val HOST_KEY = "carlink-quick-reconnect"

    /**
     * How long a held channel whose session ended waits for AapService to ask for the quick path
     * before it is released as before. AapService asks within milliseconds of the drop.
     */
    const val DECISION_GRACE_MS = 3_000L

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(HOST_KEY, false)

    /**
     * @param channelHeld the phone's channel is still open in [com.andrerinas.openheadunit.aap.WppStage.HOLDING]
     * @param usbOnlySession the host runs a USB session: no wireless route, nothing held for it
     * @param credentialsKnown this unit's network is still up and described
     */
    fun shouldTry(
        enabled: Boolean,
        userExit: Boolean,
        usbOnlySession: Boolean,
        channelHeld: Boolean,
        credentialsKnown: Boolean,
    ): Boolean = enabled && !userExit && !usbOnlySession && channelHeld && credentialsKnown

    /** Whether a held channel keeps waiting [endedForMs] after its session ended (off: never waits). */
    fun waitsForDecision(enabled: Boolean, endedForMs: Long): Boolean = enabled && endedForMs in 0 until DECISION_GRACE_MS
}
