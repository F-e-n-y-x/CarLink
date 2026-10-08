package com.carlink.app.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.PhoneKind
import com.carlink.app.core.Transport
import com.carlink.app.detect.KnownPhones
import com.carlink.app.detect.PhoneClassifier
import com.carlink.app.entry.UsbRouterActivity
import com.carlink.app.settings.CarLinkPreferences
import com.carlink.app.settings.EngineSettingsApplier
import com.carlink.app.settings.UnifiedSettings
import com.carlink.app.settings.UnifiedSettingsStore
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one place that decides which engine runs. Main process only.
 *
 * Invariants:
 * - At most one engine is engaged. Starting one stops the other first, so they never share
 *   the Wi-Fi P2P group, hotspot, VPN slot, audio focus or MediaSession.
 * - DiAuto's static key/media receivers are enabled only while Android Auto is engaged; they
 *   abort ordered key broadcasts and would otherwise take the steering-wheel keys from CarPlay.
 * - Automatic starts never interrupt a running session and respect the driver's pause.
 * - A wired session ends when its phone is unplugged.
 */
class EngineCoordinator internal constructor(
    context: Context,
    // Production defaults; unit tests inject fakes. Everything else goes through [get].
    engineFactory: EngineFactory = defaultEngineFactory,
    private val applySessionSettings: (Context, Engine, UnifiedSettings) -> Unit =
        { c, e, s -> EngineSettingsApplier.applyForSession(c, e, s) },
    /** Settings are written into the engines (IPC to ":aa") off the main thread, in order. */
    private val worker: Executor = Executors.newSingleThreadExecutor { Thread(it, "carlink-engine") },
    usbPhonePresent: ((Engine) -> Boolean)? = null,
) {
    private val app = context.applicationContext
    private val knownPhones = KnownPhones(app)
    private val preferences = CarLinkPreferences(app)
    private val unifiedSettings = UnifiedSettingsStore(app)
    private val main = Handler(Looper.getMainLooper())
    private val isUsbPhonePresent: (Engine) -> Boolean = usbPhonePresent ?: ::usbPhonePresent
    private var attempt = 0

    private val _state = MutableStateFlow(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    /** Bluetooth address of the phone the current session was started for, when known. */
    private var sessionAddress: String? = null
    private var engaged: Engine? = null

    /** When the driver last stopped a session (elapsed realtime); see [onUsbPhone]. */
    private var driverStoppedAt = -REENUMERATION_WINDOW_MILLIS

    private val carPlay = engineFactory(app, Engine.CARPLAY) { onEngineState(Engine.CARPLAY, it) }
    private val androidAuto = engineFactory(app, Engine.ANDROID_AUTO) { onEngineState(Engine.ANDROID_AUTO, it) }

    /** Ends a wired session whose phone stayed unplugged past the engines' own re-enumeration. */
    private val usbGoneCheck = Runnable {
        val engine = engaged ?: return@Runnable
        if (_state.value.transport != Transport.USB) return@Runnable
        if (isUsbPhonePresent(engine)) return@Runnable
        Log.i(TAG, "USB phone for $engine unplugged; ending the session")
        stopAll()
    }

    init {
        setAndroidAutoReceivers(false)
        val carPlayState = carPlay.current()
        if (carPlayState.isActive) {
            engaged = Engine.CARPLAY
            _state.value = carPlayState
        } else {
            // An Android Auto session that outlived this process is adopted when it reports.
            androidAuto.refresh()
        }
    }

    /** A phone was plugged in. USB is a deliberate act, so it may replace a wireless session. */
    fun onUsbPhone(kind: PhoneKind, phoneName: String?, attachIntent: Intent?) {
        val engine = kind.engine ?: return
        if (!preferences.autoConnectUsb) {
            Log.i(TAG, "USB $engine phone: automatic USB start is off")
            return
        }
        val current = _state.value
        // Both engines re-enumerate the phone on purpose while they connect: CarPlay switches the
        // iPhone into its CarPlay configuration and Android Auto into accessory mode, and each
        // switch is a new attach. The engine already running over USB handles those itself;
        // restarting it here would abort every attempt and never connect.
        if (current.isActive && current.engine == engine && current.transport == Transport.USB) {
            Log.i(TAG, "USB re-attach during $engine session; left to the engine")
            main.removeCallbacks(usbGoneCheck)
            return
        }
        // Stopping a wired session re-enumerates the phone too (an Android phone leaves accessory
        // mode, an iPhone its CarPlay configuration). That attach is not the driver plugging in.
        if (SystemClock.elapsedRealtime() - driverStoppedAt < REENUMERATION_WINDOW_MILLIS) {
            Log.i(TAG, "USB $engine attach right after the driver stopped; not restarting")
            return
        }
        connect(engine, Transport.USB, address = null, phoneName = phoneName, usbAttach = attachIntent)
    }

    /** A USB device left. A wired session whose phone does not come back within the grace ends. */
    fun onUsbDetached() {
        val current = _state.value
        if (engaged == null || current.transport != Transport.USB) return
        main.removeCallbacks(usbGoneCheck)
        main.postDelayed(usbGoneCheck, USB_GONE_GRACE_MILLIS)
    }

    /** A paired phone's Bluetooth link came up. Never interrupts a running session. */
    fun onBluetoothPhone(address: String, kind: PhoneKind, phoneName: String?) {
        val engine = kind.engine ?: return
        when {
            !preferences.autoConnectBluetooth -> Log.i(TAG, "Bluetooth $engine phone: automatic start is off")
            preferences.autoConnectPaused -> Log.i(TAG, "Bluetooth $engine phone: paused by the driver")
            _state.value.isActive -> Log.i(TAG, "Bluetooth $engine phone: a ${_state.value.engine} session is running")
            else -> connect(engine, Transport.WIRELESS, address, phoneName)
        }
    }

    /** The driver asked for this phone (Home's phone list or primary button). */
    fun connectPhone(address: String?, kind: PhoneKind, phoneName: String?, transport: Transport = Transport.WIRELESS) {
        val engine = kind.engine ?: return
        preferences.autoConnectPaused = false
        address?.let { knownPhones.remember(it, kind) }
        connect(engine, transport, address, phoneName)
    }

    /** The driver cancelled or disconnected: stop, and pause automatic starts until they connect again. */
    fun disconnect() {
        Log.i(TAG, "driver disconnect")
        preferences.autoConnectPaused = true
        driverStoppedAt = SystemClock.elapsedRealtime()
        stopAll()
    }

    fun stopAll() {
        Log.i(TAG, "stop engaged=$engaged")
        when (engaged) {
            Engine.CARPLAY -> carPlay.stop()
            Engine.ANDROID_AUTO -> androidAuto.stop()
            null -> {
                carPlay.stop()
                androidAuto.stop()
            }
        }
        disengage()
    }

    private fun connect(engine: Engine, transport: Transport, address: String?, phoneName: String?, usbAttach: Intent? = null) {
        Log.i(TAG, "connect engine=$engine transport=$transport known=${address != null}")
        if (engaged != null && engaged != engine) stopAll()
        main.removeCallbacks(usbGoneCheck)
        engaged = engine
        sessionAddress = address
        setAndroidAutoReceivers(engine == Engine.ANDROID_AUTO)
        _state.value = LinkState(LinkStage.WAITING, engine, transport, phoneName, 1, 6)
        val thisAttempt = ++attempt
        worker.execute {
            // The engine starts from the driver's unified settings and this session's wireless path.
            runCatching { applySessionSettings(app, engine, unifiedSettings.load()) }
                .onFailure { Log.w(TAG, "could not apply settings", it) }
            main.post {
                if (thisAttempt != attempt || engaged != engine) {
                    Log.i(TAG, "attempt $thisAttempt superseded before $engine started")
                    return@post
                }
                when (engine) {
                    Engine.CARPLAY -> carPlay.start(transport, address, phoneName, usbAttach)
                    Engine.ANDROID_AUTO -> androidAuto.start(transport, address, phoneName, usbAttach)
                }
            }
        }
    }

    /** Writes changed unified settings into both engines (live ones take effect at once). */
    fun applySettings() {
        worker.execute {
            runCatching { EngineSettingsApplier.applyAll(app, unifiedSettings.load()) }
                .onFailure { Log.w(TAG, "could not apply settings", it) }
        }
    }

    private fun disengage() {
        attempt++
        engaged = null
        sessionAddress = null
        main.removeCallbacks(usbGoneCheck)
        setAndroidAutoReceivers(false)
        _state.value = LinkState.Idle
    }

    private fun onEngineState(engine: Engine, state: LinkState) {
        // A session CarLink did not start adopts its engine: CarPlay started from DiPlay's own
        // notification, or Android Auto still running in ":aa" after this process restarted.
        if (engaged == null && state.isActive) {
            Log.i(TAG, "adopting a running $engine session (${state.stage})")
            if (engine == Engine.CARPLAY) androidAuto.stop() else carPlay.stop()
            engaged = engine
            setAndroidAutoReceivers(engine == Engine.ANDROID_AUTO)
        }
        if (engaged != engine) return
        if (state.stage != _state.value.stage) {
            Log.i(TAG, "$engine ${_state.value.stage} -> ${state.stage}${state.problem?.let { " problem=$it" } ?: ""}" +
                (state.detail?.let { " ($it)" } ?: ""))
        }
        if (state.stage == LinkStage.CONNECTED) {
            sessionAddress?.let { knownPhones.remember(it, if (engine == Engine.CARPLAY) PhoneKind.IPHONE else PhoneKind.ANDROID) }
        }
        _state.value = state
        if (state.stage == LinkStage.IDLE) disengage()
    }

    private fun usbPhonePresent(engine: Engine): Boolean =
        app.getSystemService(UsbManager::class.java)?.deviceList?.values.orEmpty().any { device ->
            runCatching { PhoneClassifier.classifyUsb(UsbRouterActivity.describe(device)).engine == engine }.getOrDefault(false)
        }

    private fun setAndroidAutoReceivers(enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        ANDROID_AUTO_KEY_RECEIVERS.forEach { name ->
            val component = ComponentName(app, name)
            runCatching {
                if (app.packageManager.getComponentEnabledSetting(component) != state) {
                    app.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                }
            }.onFailure { Log.w(TAG, "Could not set $name enabled=$enabled", it) }
        }
    }

    companion object {
        private const val TAG = "CarLink-Engine"

        /** Longer than CarPlay's configuration switch and Android Auto's accessory switch. */
        private const val USB_GONE_GRACE_MILLIS = 6_000L

        /** How long USB attaches after the driver's Disconnect count as re-enumeration. */
        private const val REENUMERATION_WINDOW_MILLIS = 10_000L

        /** Declared disabled in the manifest; see the class invariants. */
        internal val ANDROID_AUTO_KEY_RECEIVERS = listOf(
            "com.andrerinas.openheadunit.connection.CarKeyReceiver",
            "com.andrerinas.openheadunit.app.RemoteControlReceiver",
            "androidx.media.session.MediaButtonReceiver",
        )

        @Volatile private var instance: EngineCoordinator? = null

        fun get(context: Context): EngineCoordinator =
            instance ?: synchronized(this) { instance ?: EngineCoordinator(context).also { instance = it } }
    }
}
