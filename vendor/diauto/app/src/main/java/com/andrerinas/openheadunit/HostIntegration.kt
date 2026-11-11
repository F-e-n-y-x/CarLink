package com.andrerinas.openheadunit

import android.app.Activity
import android.content.Context
import android.view.View
import com.andrerinas.openheadunit.main.MainActivity

/**
 * CarLink: lets an APK that embeds this engine choose the screens the engine opens on its own
 * (notification tap, boot launch, return after a Wi-Fi Direct prompt), the name its notifications
 * and media session carry, and what happens to its toasts. Standalone DiAuto keeps its defaults.
 */
object HostIntegration {
    @Volatile
    @JvmStatic
    var homeActivity: Class<out Activity> = MainActivity::class.java

    /** Title of the persistent service notification. Must be set before [App.onCreate]. */
    @Volatile
    @JvmStatic
    var appName: String = "Open Headunit"

    /** Name of the service notification channel, shown in the system's notification settings. */
    @Volatile
    @JvmStatic
    var serviceChannelName: String = "Headunit Service"

    @Volatile
    @JvmStatic
    var mediaSessionTag: String = "HeadunitRevived"

    /**
     * Sees every engine toast first; returns true when the host handled it (showed its own status
     * or chose to drop it). [forced] marks toasts the engine shows even with toasts turned off.
     */
    @Volatile
    @JvmStatic
    var toastHandler: ((context: Context, text: CharSequence, forced: Boolean) -> Boolean)? = null

    /** The projection's own chrome, for [chromeStyler]. */
    enum class Chrome { LOADING, RENDERER_BANNER, EXIT_MENU }

    /** Restyles the projection's chrome (type, shapes, marks) after the engine shows or repaints it. */
    @Volatile
    @JvmStatic
    var chromeStyler: ((root: View, chrome: Chrome) -> Unit)? = null

    fun styleChrome(root: View?, chrome: Chrome) {
        if (root != null) chromeStyler?.invoke(root, chrome)
    }

    /**
     * Label of the exit-menu entry that opens [homeActivity] in place of the engine's Quick
     * Settings (a host with its own settings screen). Null keeps Quick Settings.
     */
    @Volatile
    @JvmStatic
    var exitMenuHomeLabel: String? = null

    /**
     * CarLink: the phone asked for the car's own screen (the Exit / car tile). Non-null: the engine
     * answers "native", keeps the session, and calls this (any thread) while the projection is in
     * front, so the host shows its screen; showing the projection again regains focus. Null
     * (standalone) ends the session as before.
     */
    @Volatile
    @JvmStatic
    var openCarScreen: (() -> Unit)? = null

    // CarLink: process lifecycle. Standalone DiAuto keeps both true: it owns the device and wants
    // its engine back after a low-memory kill or a task swipe. A host that drives the engine itself
    // sets both false, so no engine runs that the host did not start and cannot see.

    /** True: AapService returns START_STICKY (Android restarts it with a null intent after a kill). */
    @Volatile
    @JvmStatic
    var stickyService: Boolean = true

    /** True: AapService restarts itself from onTaskRemoved when the app's task is swiped away. */
    @Volatile
    @JvmStatic
    var restartOnTaskRemoved: Boolean = true

    /**
     * CarLink: true lets the projection claim the window area a BYD DiLink caption hides
     * ([com.andrerinas.openheadunit.utils.BydFullscreen]). On other devices that area is a real
     * system bar or cutout, so a host that knows it is not on a DiLink sets this false.
     */
    @Volatile
    @JvmStatic
    var claimHiddenBottomStrip: Boolean = true

    /**
     * CarLink: the screen turns (a phone or tablet). With Automatic resolution the engine then asks
     * for one landscape stream holding the screen's shape in either orientation, and a turn moves
     * Android Auto by margins (UpdateUiConfigRequest) instead of reconnecting
     * ([com.andrerinas.openheadunit.utils.HeadUnitScreenConfig.turning]).
     */
    @Volatile
    @JvmStatic
    var turningScreen: Boolean = false

    /**
     * CarLink: the turning-screen region is this many times smaller than the session's (>= 1), so
     * Android Auto's layout grows live with a margin change instead of a reconnect (interface size).
     */
    @Volatile
    @JvmStatic
    var turnSizeFactor: Float = 1f

    /** CarLink: set by the projection screen; re-sends the turning-screen margins after [turnSizeFactor] changes. */
    @Volatile
    @JvmStatic
    var relayoutTurningScreen: (() -> Unit)? = null

    /** CarLink: what [onEngineEvent] reports. Detail strings are `key=value` pairs joined by `;`. */
    enum class EngineEvent {
        /** The Wi-Fi radio is off, so the Wi-Fi Direct route cannot start (API 29+ cannot turn it on). */
        WIFI_OFF,
        /** Another app owns the Wi-Fi Direct group; the engine stood down. */
        P2P_OWNED_BY_OTHER,
        /** The Wi-Fi Direct group is on a channel most phones cannot join (DFS). Detail: `frequency=`. */
        DFS_CHANNEL,
        /** No usable BSSID when the credentials were due; the handshake aborted. Detail: `location=on|off`. */
        BSSID_UNRESOLVED,
        /** Bluetooth cannot serve the native route. Detail: `cause=permission|off`. */
        BLUETOOTH_UNAVAILABLE,
        /** The phone opened the Android Auto RFCOMM channel. Detail: `address=`. */
        BT_CHANNEL_ACCEPTED,
        /** The credentials (WifiInfoResponse, type 3) went out. Detail: `transport=`. */
        CREDENTIALS_SENT,
        /** The phone answers wake pokes but never opens the Android Auto channel. Detail: `pokes=`. */
        POKE_ANSWERED_NO_ACCEPT,
        /** Handshakes are no longer served after repeated silent ones. Detail: `failures=`. */
        HANDSHAKE_BACKOFF,
        /** A WifiStartResponse (7) or WifiConnectStatus (6) status. Detail: `type=;status=;stage=`. */
        PHONE_WIFI_STATUS,
        /** No TCP session within the settle window after the credentials. Detail: `seconds=`. */
        SETTLE_TIMEOUT,
        /** Several sessions in a row ended without one video frame. Detail: `sessions=`. */
        STARVATION_ADVICE,
        /** A Wi-Fi AAP session received nothing for the read timeout. Detail: `millis=`. */
        LINK_READ_TIMEOUT,
        /** Wireless bring-up refused by the boot-loop guard. */
        BOOT_LOOP_PAUSE,
        /** An external Bluetooth module makes the native route impossible. Detail: `evidence=`. */
        EXTERNAL_BLUETOOTH,
        /** The host's module route (ZBT/BLINK) cannot reach the module. Detail: `route=;reason=`. */
        EXTERNAL_MODULE_UNAVAILABLE,
        /** USB permission was denied, or its request failed. Detail: `device=`. */
        USB_PERMISSION_DENIED,
        /** The first video frame of a session was rendered. */
        FIRST_VIDEO_FRAME,
        /** A session that had connected ended. Detail: `userExit=;clean=`. */
        SESSION_ENDED,
        /** CarLink: Self Mode found no head unit server on 127.0.0.1:5277 (Android Auto's developer setting is off). */
        HEAD_UNIT_SERVER_CLOSED,
        /**
         * CarLink: the projection screen settled in the other orientation from the picture the phone
         * agreed to send (agreed once per session). Detail: `picture=WxH;screen=landscape|portrait`.
         */
        ORIENTATION_CHANGED,
        /** CarLink: this unit could not create its Wi-Fi Direct group at all (#40). Detail: `reason=`. */
        WIFI_DIRECT_GROUP_FAILED,
        /**
         * CarLink: a dropped wireless session is offered the network again on the held Bluetooth
         * channel (`QuickReconnectPolicy`). Detail: `result=started|fallback` (fallback: the full re-arm runs).
         */
        QUICK_RECONNECT,
    }

    /**
     * CarLink: called (on any engine thread) when something a host should show or act on happens.
     * The engine's own toasts and logs are unchanged. Null (standalone) does nothing.
     */
    @Volatile
    @JvmStatic
    var onEngineEvent: ((context: Context?, kind: EngineEvent, detail: String?) -> Unit)? = null

    /** CarLink: fires [onEngineEvent]; a failing host hook never reaches the engine. */
    @JvmStatic
    fun engineEvent(context: Context?, kind: EngineEvent, detail: String? = null) {
        when (kind) { // CarLink: wireless timing (aap/WirelessTiming), whether or not a host listens
            EngineEvent.BT_CHANNEL_ACCEPTED -> com.andrerinas.openheadunit.aap.WirelessTiming.mark(
                com.andrerinas.openheadunit.aap.WirelessTimeline.Phase.PHONE_RFCOMM)
            EngineEvent.CREDENTIALS_SENT -> com.andrerinas.openheadunit.aap.WirelessTiming.mark(
                com.andrerinas.openheadunit.aap.WirelessTimeline.Phase.WIFI_INFO_RESPONSE)
            EngineEvent.FIRST_VIDEO_FRAME -> com.andrerinas.openheadunit.aap.WirelessTiming.mark(
                com.andrerinas.openheadunit.aap.WirelessTimeline.Phase.FIRST_FRAME)
            else -> Unit
        }
        val hook = onEngineEvent ?: return
        try {
            hook(context, kind, detail)
        } catch (e: Exception) {
            com.andrerinas.openheadunit.utils.AppLog.w("HostIntegration: onEngineEvent($kind) failed: ${e.message}")
        }
    }

    /**
     * CarLink: the host performs the USB accessory switch itself. When true, a phone attached in
     * normal mode is left to the host instead of the engine's delayed fallback switch, which
     * otherwise races the host's own switch on the same device. Default false (standalone).
     */
    @Volatile
    @JvmStatic
    var hostOwnsUsbSwitch: Boolean = false

    /**
     * CarLink: the host runs a USB-only session. While true, the service arms no wireless route
     * (`initWifiMode` returns at once: no Wi-Fi Direct group, no Bluetooth wake pokes, no wireless
     * server), so a plugged-in phone is never pulled into the wireless flow. Default false.
     */
    @Volatile
    @JvmStatic
    var usbOnlySession: Boolean = false

    /**
     * CarLink: the next stop leaves this engine's own Wi-Fi Direct group up, for a restart of the
     * same wireless session to read instead of recreating it (after open-headunit's
     * NativeGroupKeepPolicy). The host sets it per stop and clears it at the next start. Default false.
     */
    @Volatile
    @JvmStatic
    var keepWifiDirectGroupOnStop: Boolean = false

    /**
     * CarLink: on a turning screen, ask for the 1440p (H.265) stream below a 2560-pixel panel too.
     * The host sets it when the hardware HEVC decoder carries 2560x1440 at 60 fps: the zoomed sizes
     * show a region of the stream scaled up, so a larger stream keeps them sharp. Default false.
     */
    @Volatile
    @JvmStatic
    var turningHighResStream: Boolean = false

    /**
     * CarLink: the density announced when the DPI setting is Automatic (0), from the device's own
     * density and the visible codec area (codec size less margins, in pixels). Null = the device's
     * density (standalone).
     */
    @Volatile
    @JvmStatic
    var automaticDensityDpi: ((deviceDpi: Int, visibleWidthPx: Int, visibleHeightPx: Int) -> Int)? = null

    /** CarLink: scales each microphone read (16-bit PCM, in place) before it is sent to the phone. */
    @Volatile
    @JvmStatic
    var microphonePcm: ((data: ByteArray, length: Int) -> Unit)? = null

    /** CarLink: the phone's play state from each media playback status it sends. */
    @Volatile
    @JvmStatic
    var onPhonePlaying: ((Boolean) -> Unit)? = null
}
