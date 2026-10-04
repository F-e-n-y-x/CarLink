package com.shilapi.xcertplay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydNavigationOutputs

/**
 * Optional: steering-wheel keys zoom CarPlay's dashboard map. BYD's window manager takes the wheel's
 * keys (volume 291/292, the custom key 305) before any app sees them and acts on them itself. An
 * accessibility service that filters key events gets them earlier, in the input filter, and may keep
 * them. With the setting on and the dashboard map streaming, the mode key (BYD's custom key by default)
 * switches the zoom keys (the volume keys by default) from volume to map zoom until it is pressed again,
 * or, in the timed behaviour, until a few seconds after the last zoom. A call always keeps the volume.
 * Every other key passes on unchanged. On the Tang the console's volume sends the same codes as the
 * wheel's, so it zooms too while zoom mode is on.
 */
class WheelKeyService : AccessibilityService() {
    private val keys = WheelZoomKeys()
    private val handler = Handler(Looper.getMainLooper())
    private val endTimedMode = Runnable { if (keys.timeOut()) announce(zoomOn = false) }

    override fun onServiceConnected() {
        running = this
        Log.i(TAG, "wheel key service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (running === this) running = null
        handler.removeCallbacks(endTimedMode)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val key = WheelKey(event.keyCode, event.scanCode, deviceName(event.deviceId))
        val down = event.action == KeyEvent.ACTION_DOWN
        if (!down && swallowUp == key) {
            swallowUp = null
            return true
        }
        learning?.let { role ->
            if (down) {
                learning = null
                swallowUp = key // its release must not reach the car on its own either
                WheelZoomSettings.assign(this, role, key)
                Log.i(TAG, "$role key is now $key")
                learnt?.invoke(role, key)
            }
            return true
        }
        if (!WheelZoomSettings.enabled(this)) return false
        val controller = CarPlayBackgroundSession.snapshot()?.controller
        val action = keys.onKey(
            role = WheelZoomSettings.roleOf(this, key),
            down = down,
            firstPress = event.repeatCount == 0,
            mapShown = controller?.dashboardMapStreaming() == true,
            inCall = inCall(),
        )
        when (action) {
            WheelZoomKeys.Action.PASS -> return false
            WheelZoomKeys.Action.CONSUME -> Unit
            WheelZoomKeys.Action.MODE_ON -> announce(zoomOn = true)
            WheelZoomKeys.Action.MODE_OFF -> announce(zoomOn = false)
            WheelZoomKeys.Action.ZOOM_IN, WheelZoomKeys.Action.ZOOM_OUT ->
                controller?.zoomDashboardMap(zoomIn = action == WheelZoomKeys.Action.ZOOM_IN)
        }
        rearmTimedMode()
        return true
    }

    // In the timed behaviour zoom mode ends a few seconds after the last press.
    private fun rearmTimedMode() {
        handler.removeCallbacks(endTimedMode)
        if (keys.zoomMode && WheelZoomSettings.behaviour(this) == WheelZoomSettings.Behaviour.TIMED) {
            handler.postDelayed(endTimedMode, WheelZoomSettings.TIMED_MODE_MILLIS)
        }
    }

    // Android is in a call or communication audio mode during CarPlay and Bluetooth calls (and ringing).
    private fun inCall(): Boolean =
        (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode.let { it != null && it != AudioManager.MODE_NORMAL }

    // On the centre screen, and where the song shows on the dashboard: zoom with BYD's Bluetooth-music
    // icon (source 6), volume with the song's usual icon.
    private fun announce(zoomOn: Boolean) {
        Log.i(TAG, "zoom mode ${if (zoomOn) "on" else "off"}")
        handler.post {
            Toast.makeText(this, if (zoomOn) R.string.wheel_zoom_mode_on else R.string.wheel_zoom_mode_off, Toast.LENGTH_SHORT).show()
        }
        if (zoomOn) {
            BydNavigationOutputs.dashboardNote("🔍 ${getString(R.string.wheel_zoom_note_zoom)}", ZOOM_NOTE_SOURCE)
        } else {
            BydNavigationOutputs.dashboardNote("🔊 ${getString(R.string.wheel_zoom_note_volume)}")
        }
    }

    private fun deviceName(id: Int): String = runCatching { InputDevice.getDevice(id)?.name }.getOrNull() ?: "?"

    companion object {
        private const val TAG = "DiPlay-WheelKeys"
        private const val ZOOM_NOTE_SOURCE = 6
        @Volatile private var running: WheelKeyService? = null
        @Volatile private var learning: WheelZoomSettings.Role? = null
        @Volatile private var learnt: ((WheelZoomSettings.Role, WheelKey) -> Unit)? = null
        @Volatile private var swallowUp: WheelKey? = null

        fun connected(): Boolean = running != null

        /** The next key pressed is assigned to [role]; [done] runs on the service's thread. */
        fun learn(role: WheelZoomSettings.Role, done: (WheelZoomSettings.Role, WheelKey) -> Unit): Boolean {
            if (running == null) return false
            learnt = done
            learning = role
            return true
        }

        fun enabledInSettings(context: Context): Boolean {
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return component(context).flattenToString() in list.orEmpty().split(':')
        }

        /**
         * BYD's settings have no accessibility page, so the user can turn the service on through the car's
         * own adb (allowed once on the car screen). Services already in the list stay there.
         */
        fun enableOverAdb(context: Context): LocalAdb.Access = LocalAdb(AdbKeys.load(context)).use { adb ->
            val access = adb.connect(mayAsk = true)
            if (access == LocalAdb.Access.READY) {
                val ours = component(context).flattenToString()
                val current = adb.shell("settings get secure enabled_accessibility_services")?.trim()
                    ?.takeUnless { it.isEmpty() || it == "null" }
                val list = (current?.split(':').orEmpty() + ours).filter { it.isNotBlank() }.distinct().joinToString(":")
                adb.shell("settings put secure enabled_accessibility_services '$list'")
                adb.shell("settings put secure accessibility_enabled 1")
                Log.i(TAG, "wheel key service allowed over adb")
            }
            access
        }

        private fun component(context: Context) = ComponentName(context, WheelKeyService::class.java)
    }
}

/** A key as the head unit reports it; code, scan code and device name tell keys apart. */
data class WheelKey(val code: Int, val scan: Int, val device: String) {
    override fun toString(): String = "$code/$scan"

    fun encode(): String = "$code|$scan|$device"

    companion object {
        fun decode(text: String?): WheelKey? = text?.split('|', limit = 3)?.takeIf { it.size == 3 }?.let {
            WheelKey(it[0].toIntOrNull() ?: return null, it[1].toIntOrNull() ?: return null, it[2])
        }
    }
}

/** What a key press does for the dashboard map zoom; no Android types, so it is unit-tested. */
class WheelZoomKeys {
    enum class Action { PASS, CONSUME, MODE_ON, MODE_OFF, ZOOM_IN, ZOOM_OUT }

    var zoomMode = false
        private set

    /** [role] is null for keys that have no role; [firstPress] is false for auto-repeats. */
    fun onKey(role: WheelZoomSettings.Role?, down: Boolean, firstPress: Boolean, mapShown: Boolean, inCall: Boolean): Action {
        role ?: return Action.PASS
        if (role == WheelZoomSettings.Role.MODE) {
            // Without the dashboard map the key keeps the car's own action, and zoom mode ends.
            if (!mapShown) {
                zoomMode = false
                return Action.PASS
            }
            if (!down || !firstPress) return Action.CONSUME
            zoomMode = !zoomMode
            return if (zoomMode) Action.MODE_ON else Action.MODE_OFF
        }
        if (!zoomMode || !mapShown || inCall) return Action.PASS
        if (!down || !firstPress) return Action.CONSUME
        return if (role == WheelZoomSettings.Role.ZOOM_IN) Action.ZOOM_IN else Action.ZOOM_OUT
    }

    /** The timed behaviour's few seconds ran out; true if that ended zoom mode. */
    fun timeOut(): Boolean {
        if (!zoomMode) return false
        zoomMode = false
        return true
    }
}

/** Settings for the wheel's dashboard map zoom. */
object WheelZoomSettings {
    private const val PREFS = "diplay_wheel_map_zoom"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BEHAVIOUR = "behaviour"
    private const val BYD_KEYS = "simulate-keys"
    const val TIMED_MODE_MILLIS = 5_000L

    /** Defaults are a BYD Tang's wheel: the custom key, then volume up and down. */
    enum class Role(val defaultKey: WheelKey) {
        MODE(WheelKey(305, 300, BYD_KEYS)),
        ZOOM_IN(WheelKey(291, 115, BYD_KEYS)),
        ZOOM_OUT(WheelKey(292, 114, BYD_KEYS)),
    }

    /** The mode key switches zoom mode until pressed again, or turns it on for a few seconds. */
    enum class Behaviour { TOGGLE, TIMED }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun behaviour(context: Context): Behaviour =
        Behaviour.entries.firstOrNull { it.name == prefs(context).getString(KEY_BEHAVIOUR, null) } ?: Behaviour.TOGGLE

    fun setBehaviour(context: Context, behaviour: Behaviour) =
        prefs(context).edit().putString(KEY_BEHAVIOUR, behaviour.name).apply()

    fun key(context: Context, role: Role): WheelKey =
        WheelKey.decode(prefs(context).getString("key_${role.name}", null)) ?: role.defaultKey

    fun assign(context: Context, role: Role, key: WheelKey) =
        prefs(context).edit().putString("key_${role.name}", key.encode()).apply()

    fun roleOf(context: Context, key: WheelKey): Role? = Role.entries.firstOrNull { key(context, it) == key }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
