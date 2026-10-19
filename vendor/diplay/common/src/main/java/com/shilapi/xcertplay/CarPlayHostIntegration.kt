package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus

/**
 * CarLink: lets an APK that embeds this engine replace DiPlay's own screens. The projection
 * (CarPlayHostActivity) opens [homeActivity] for its home button and when authentication cannot
 * load; standalone DiPlay keeps DiPlayActivity.
 */
object CarPlayHostIntegration {
    @Volatile
    @JvmStatic
    var homeActivity: Class<out Activity> = DiPlayActivity::class.java

    /** Title of the connection notification. */
    @Volatile
    @JvmStatic
    var appName: String = "DiPlay"

    @Volatile
    @JvmStatic
    var mediaSessionTag: String = "DiPlay CarPlay"

    /**
     * CarLink: the driver's learned steering-wheel keys, physical key code → the standard key code
     * the CarPlay screen already handles (VOICE_ASSIST, CALL, ENDCALL, MEDIA_*). Empty for standalone DiPlay.
     */
    @Volatile
    @JvmStatic
    var keyRemap: Map<Int, Int> = emptyMap()

    /** [event] with its key code replaced per [keyRemap], or [event] itself. */
    internal fun remapKey(event: android.view.KeyEvent): android.view.KeyEvent {
        val code = keyRemap[event.keyCode] ?: return event
        return android.view.KeyEvent(event.downTime, event.eventTime, event.action, code, event.repeatCount,
            event.metaState, event.deviceId, event.scanCode, event.flags, event.source)
    }

    /** Sees the projection's informational toasts first; returns true when the host handled them. */
    @Volatile
    @JvmStatic
    var toastHandler: ((context: Context, text: CharSequence) -> Boolean)? = null

    fun showToast(context: Context, text: CharSequence, duration: Int) {
        if (toastHandler?.invoke(context, text) == true) return
        android.widget.Toast.makeText(context, text, duration).show()
    }

    /** The projection's own chrome, for [chromeStyler]. */
    enum class Chrome { WAITING, MENU, SIDE_PANEL, PICTURE_PANEL, SAFE_AREA_EDITOR }

    /**
     * Sections of the in-session menu (the multi-finger swipe). A host whose own settings screen
     * owns a section, or that has no use for a standalone-DiPlay control, leaves it out; hidden
     * sections are still built and keep their saved values.
     */
    enum class MenuItem {
        SIDE_PANEL, MFI_TARGET, WIRELESS, WIFI_METHOD, HOTSPOT_STATUS, AUTO_START, LOCATION, AUDIO_MAPPING,
        VEHICLE_IDENTITY, CAR_BUTTON_ICON, DRIVING_SIDE, DOCK, RESOLUTION, FRAME_RATE, PHYSICAL_SIZE_BASIS,
        PHYSICAL_LENGTH, HEVC, SAFE_AREA, SYSTEM_BARS, DEBUG_LOGS, EXIT_APPLICATION, LANGUAGE, GESTURE_FINGERS,
        HOME,
        SETTINGS_SUMMARY, // CarLink: the Display page's read-only summary of the session settings
    }

    @Volatile
    @JvmStatic
    var menuItems: Set<MenuItem> = MenuItem.entries.toSet()

    /** Replaces DiPlay's colors for its chrome. Return the same instance for the same [night]. */
    @Volatile
    @JvmStatic
    var palette: ((night: Boolean) -> DiPlayPalette)? = null

    /** Restyles the projection's chrome (type, shapes, marks) after DiPlay builds or repaints it. */
    @Volatile
    @JvmStatic
    var chromeStyler: ((root: View, chrome: Chrome, night: Boolean) -> Unit)? = null

    internal fun styleChrome(root: View?, chrome: Chrome, night: Boolean) {
        if (root != null) chromeStyler?.invoke(root, chrome, night)
    }

    /** [page] is DiPlay's page name ("home", "settings", …); other hosts may ignore it. */
    fun homeIntent(context: Context, page: String? = null): Intent =
        Intent(context, homeActivity).apply { page?.let { putExtra("page", it) } }

    /** The iPhone wireless CarPlay connects to (DiPlay's "Choose iPhone"). */
    fun selectPhone(context: Context, address: String, name: String) = DiPlayPreferences.savePhone(context, address, name)

    fun selectedPhoneAddress(context: Context): String? = DiPlayPreferences.phoneAddress(context)

    /** Null when CarPlay authentication is ready; otherwise why it is not (DiPlay's setup check). */
    fun authenticationError(context: Context): Throwable? =
        runCatching { DiPlayBootstrap.ensure(context, AirPlayPersistence.loadMfiTarget(context)) }.exceptionOrNull()

    // ---------------------------------------------------------------- CarLink: session lifecycle

    /**
     * What the projection host does that [com.shilapi.xcertplay.orchestration.CarPlayStatus]
     * cannot say (docs/dissection/DIPLAY.md F1, F13, F15). Delivered on the main thread.
     */
    sealed interface HostState {
        /**
         * DiPlay scheduled a reconnect. [failure] is the status that caused it, or null when an
         * established AirPlay session ended or its transport failed ([reason] says which).
         * [maxAttempts] is DiPlay's startup budget, or null for losses it retries without limit.
         */
        data class Retrying(
            val attempt: Int,
            val maxAttempts: Int?,
            val delayMillis: Long,
            val failure: CarPlayStatus.Failed?,
            val reason: String,
        ) : HostState

        /**
         * DiPlay stopped retrying a wireless startup failure: its budget is spent ([exhausted]),
         * or the failure is one DiPlay never retries (HOTSPOT_CONFIGURATION, BLUETOOTH_UNUSABLE). The host
         * stays open and shows its Retry button until the host stops the session.
         */
        data class RetriesStopped(
            val failure: CarPlayStatus.Failed?,
            val startupFailure: WirelessStartupFailure,
            val retries: Int,
            val exhausted: Boolean,
        ) : HostState

        /** The driver declined the VPN consent the wired session needs; nothing starts. */
        data object VpnConsentDenied : HostState

        /** The driver declined runtime permissions the wireless session needs; nothing starts. */
        data class PermissionsDenied(val permissions: List<String>) : HostState

        /** DiPlaySessionService could not start, so the attempt was shut down. */
        data class ForegroundServiceFailed(val message: String) : HostState

        /** The host could not load CarPlay authentication and closed itself at once. */
        data object AuthenticationUnavailable : HostState

        /** The first video frame of an AirPlay session was rendered: projection is up. */
        data object FirstFrame : HostState

        /**
         * No host, controller or teardown is left: [hasSession] is false. Sent once per session,
         * whoever ended it (the host, the notification's Disconnect, a removed task).
         */
        data object Closed : HostState
    }

    /** Sees every [HostState]. Main thread. */
    @Volatile
    @JvmStatic
    var onHostState: ((HostState) -> Unit)? = null

    /**
     * Replaces the projection's waiting, preparation and failure panel with the host's own view,
     * built once per host window. It is shown until the first frame, hidden while CarPlay is on
     * screen and shown again when the session drops back to waiting; DiPlay's permission and VPN
     * dialogs and the in-session menu work as before. Null keeps DiPlay's panel.
     */
    @Volatile
    @JvmStatic
    var waitingView: ((Context) -> View)? = null

    /**
     * BYD vehicle integrations that DiPlay runs on every head unit (DIPLAY.md §12): navigation
     * output bridges, BYD wheel voice key codes 304/312 (and 327/328), the local-adb Wi-Fi scan
     * pause and the adb cluster activity. True keeps upstream behaviour.
     */
    @JvmStatic
    var vehicleIntegrations: Boolean
        get() = com.shilapi.xcertplay.hud.VehicleIntegrations.enabled
        set(value) { com.shilapi.xcertplay.hud.VehicleIntegrations.enabled = value }

    /** The car's name in iAP2 identification (name and label) and in the AirPlay/Bonjour service. */
    @Volatile
    @JvmStatic
    var accessoryName: String = "DiPlay"

    /**
     * Wireless CarPlay over Wi-Fi Direct asks for one name and passphrase per install, as a
     * persistent group, so the iPhone rejoins a network it saved (network/P2pStableIdentity.kt).
     * Null uses DiPlay's setting (`AirPlayPersistence.loadWifiP2pStableIdentity`, on by default).
     */
    @Volatile
    @JvmStatic
    var wifiP2pStableIdentity: Boolean? = null

    /** False keeps the CarPlay debug-log setting across bootstraps (DiPlay turns it off; F6). */
    @Volatile
    @JvmStatic
    var resetDebugLogsOnBootstrap: Boolean = true

    /**
     * True from the moment a projection host opens until its teardown has completed, including
     * DiPlay's internal restarts and the permission/VPN stage before a controller exists (F13).
     */
    fun hasSession(): Boolean = HostSessions.hasSession()

    /**
     * Ends the session at any stage (also a host still asking for permissions, F9) and calls
     * [completion] on the main thread once the controller is closed and DiPlaySessionService has
     * been stopped, so a new session cannot lose its foreground service to the old teardown (F14).
     * Calls [completion] at once when nothing runs. Call on the main thread.
     */
    fun stopSession(completion: () -> Unit = {}) = HostSessions.stop(completion)

    /** The waiting view's Retry: restarts a session whose startup retries stopped. */
    fun retryNow(): Boolean = HostSessions.retryNow()

    internal fun publishHostState(state: HostState) {
        runCatching { onHostState?.invoke(state) }
            .onFailure { android.util.Log.w("CarLink-CarPlayHost", "host state listener failed", it) }
    }
}

/**
 * CarLink: which projection hosts are open and which teardowns are still running, so
 * [CarPlayHostIntegration.hasSession] and [CarPlayHostIntegration.stopSession] cover the whole
 * session, not only the time a controller exists. Main thread only.
 */
internal object HostSessions {
    /** What a host exposes to [HostSessions]. */
    interface Host {
        val ended: Boolean
        /** Shuts the session down (idempotent) and finishes the window; [done] after teardown. */
        fun end(done: () -> Unit)
        fun retryNow(): Boolean
    }

    private const val MAX_ROUNDS = 8
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val hosts = mutableListOf<java.lang.ref.WeakReference<Host>>()
    private var teardowns = 0
    private var open = false
    private var stopping = false
    private val stopWaiters = mutableListOf<() -> Unit>()
    private val teardownWaiters = mutableListOf<() -> Unit>()

    private fun liveHosts(): List<Host> {
        hosts.removeAll { it.get()?.ended != false }
        return hosts.mapNotNull { it.get() }
    }

    fun hasSession(): Boolean = liveHosts().isNotEmpty() || CarPlayBackgroundSession.hasSession() || teardowns > 0

    fun opened(host: Host) {
        liveHosts()
        if (hosts.none { it.get() === host }) hosts += java.lang.ref.WeakReference(host)
        open = true
    }

    /** The host's window is gone, or it finished without a teardown. */
    fun closed(host: Host) {
        hosts.removeAll { it.get() == null || it.get() === host }
        publishIfClosed()
    }

    fun teardownStarted() { teardowns += 1 }

    fun teardownFinished() {
        teardowns = (teardowns - 1).coerceAtLeast(0)
        if (teardowns == 0) {
            val waiters = teardownWaiters.toList()
            teardownWaiters.clear()
            waiters.forEach { it() }
        }
        publishIfClosed()
    }

    fun stop(completion: () -> Unit) {
        stopWaiters += completion
        if (stopping) return
        stopping = true
        rounds = 0
        step()
    }

    fun retryNow(): Boolean = liveHosts().lastOrNull()?.retryNow() == true

    /** Guards [step] against a stop action that completes without clearing its session. */
    private var rounds = 0

    /** One teardown at a time: the controller's owner, then any host without one, then waits. */
    private fun step() {
        if (++rounds > MAX_ROUNDS) {
            android.util.Log.w("CarLink-CarPlayHost", "stopSession gave up after $MAX_ROUNDS rounds; " +
                "background=${CarPlayBackgroundSession.hasSession()} hosts=${liveHosts().size} teardowns=$teardowns")
        } else if (CarPlayBackgroundSession.hasSession()) {
            CarPlayBackgroundSession.stop { main.post(::step) }
            return
        } else if (liveHosts().isNotEmpty()) {
            liveHosts().first().end { main.post(::step) }
            return
        } else if (teardowns > 0) {
            teardownWaiters += { main.post(::step) }
            return
        }
        stopping = false
        val waiters = stopWaiters.toList()
        stopWaiters.clear()
        publishIfClosed()
        waiters.forEach { it() }
    }

    private fun publishIfClosed() {
        if (!open || hasSession()) return
        open = false
        CarPlayHostIntegration.publishHostState(CarPlayHostIntegration.HostState.Closed)
    }
}
