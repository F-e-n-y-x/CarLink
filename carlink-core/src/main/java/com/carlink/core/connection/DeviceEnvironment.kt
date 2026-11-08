package com.carlink.core.connection

import com.shilapi.xcertplay.transport.ModuleIap2Route
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.util.Log
import com.andrerinas.openheadunit.connection.ExternalBtTransportPolicy
import com.carlink.core.core.Engine
import com.carlink.core.core.PhoneKind
import com.carlink.core.detect.ExternalBluetoothProbe
import com.carlink.core.detect.HeadUnitDetector
import com.carlink.core.detect.StereoBluetooth
import com.carlink.core.detect.KnownPhones
import com.carlink.core.detect.PhoneClassifier
import com.carlink.core.diagnostics.EventLog
import com.carlink.core.entry.UsbRouterActivity
import com.carlink.core.settings.CarLinkPreferences
import com.carlink.core.settings.WifiEnvironmentReader
import com.carlink.core.settings.UnifiedSettingsStore
import com.carlink.core.settings.WirelessPath

/**
 * [SessionManager.Environment] on a real device. [enginePreflight] adds each engine's own checks
 * (CarPlay authentication, VPN consent for USB CarPlay) to [DevicePreflight].
 */
class DeviceEnvironment(
    context: Context,
    private val enginePreflight: (SessionRequest, WirelessPath?) -> List<Problem> = { _, _ -> emptyList() },
) : SessionManager.Environment {
    private val app = context.applicationContext
    private val preferences = CarLinkPreferences(app)
    private val unified = UnifiedSettingsStore(app)
    private val knownPhones = KnownPhones(app)
    private val paths = PathMemory(app)
    private val callMicrophone = CallMicrophoneWatch(app)

    override val autoConnectUsb get() = preferences.autoConnectUsb
    override val autoConnectBluetooth get() = preferences.autoConnectBluetooth
    override var autoConnectPaused: Boolean
        get() = preferences.autoConnectPaused
        set(value) { preferences.autoConnectPaused = value }

    override fun wirelessUnavailable(engine: Engine): Boolean =
        wirelessBlocked(effectiveStereo(app), preferences.wirelessOnExternalBluetooth, engine)

    override fun preflight(request: SessionRequest, path: WirelessPath?): List<Problem> {
        val settings = unified.load()
        val wifi = WifiEnvironmentReader.read(app, settings)
        val facts = DevicePreflight.facts(app, request.phoneAddress)
        val stereo = effectiveStereo(app)
        val moduleOn = preferences.wirelessOnExternalBluetooth
        val viaModule = when (request.engine) {
            Engine.ANDROID_AUTO -> moduleRoute(stereo, moduleOn) != null
            Engine.CARPLAY -> moduleCarPlay(stereo, moduleOn)
        }
        // The module carries Bluetooth and the phone pairs with it, so no check asks for Android's
        // radio or an Android pairing (CarPlay was blocked "not paired" on a module stereo, 2026-11-04).
        val engine = enginePreflight(request, path).filterNot { viaModule && it in MODULE_IRRELEVANT }
        return (DevicePreflight.check(request, path, facts, settings, wifi, viaModule) + engine).distinct()
    }

    override fun wirelessPaths(request: SessionRequest): List<WirelessPath> {
        val settings = unified.load()
        return WirelessPolicy.candidates(WirelessPolicy.Inputs(
            engine = request.engine,
            settings = settings,
            wifi = WifiEnvironmentReader.read(app, settings),
            headUnit = HeadUnitDetector.looksLikeCarDisplay(app),
            lastWorking = request.phoneAddress?.let { paths.lastWorking(request.engine, it) },
            wifiDirect = WifiDirectSupport.supported(app),
        ))
    }

    override fun rememberWorkingPath(engine: Engine, phoneAddress: String, path: WirelessPath) {
        paths.remember(engine, phoneAddress, path)
        if (path == WirelessPath.WIFI_DIRECT) WifiDirectSupport.connected(app)
        // It connected wirelessly, so its pairing works again.
        com.carlink.core.detect.PairingLoss.clear(app, phoneAddress)
    }

    override fun rememberPhone(phoneAddress: String, kind: PhoneKind?, name: String?) {
        kind?.let { knownPhones.remember(phoneAddress, it) }
        knownPhones.rememberName(phoneAddress, name)
        lastPhone(app).edit().putString(KEY_LAST_PHONE, phoneAddress).apply()
    }

    override fun usbSerial(attach: android.content.Intent?): String? = runCatching {
        val device = if (android.os.Build.VERSION.SDK_INT >= 33)
            attach?.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
        else @Suppress("DEPRECATION") attach?.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
        device?.serialNumber?.takeIf { it.isNotBlank() }
    }.getOrNull() // SecurityException without permission: unknown

    override fun modulePhoneWaitMillis(request: SessionRequest): Long? {
        if (request.transport != com.carlink.core.core.Transport.WIRELESS) return null
        val stereo = effectiveStereo(app)
        val moduleOn = preferences.wirelessOnExternalBluetooth
        return when (request.engine) {
            Engine.CARPLAY -> com.shilapi.xcertplay.transport.ModuleIap2Route.CONNECT_TIMEOUT_MILLIS.takeIf { moduleCarPlay(stereo, moduleOn) }
            Engine.ANDROID_AUTO -> MODULE_AA_WAIT_MILLIS.takeIf { moduleRoute(stereo, moduleOn) != null }
        }
    }

    override fun connectedPhones(): List<PhoneOption> = knownPhones.all()
        .filterKeys { BluetoothWarmup.linked(app, it) }
        .map { (address, kind) -> PhoneOption(address, knownPhones.nameOf(address), kind, present = true) }

    /** The iPhone the stereo's module reported for the last CarPlay link (address, name). */
    override fun modulePhone(engine: Engine): Pair<String, String?>? =
        if (engine == Engine.CARPLAY) com.shilapi.xcertplay.transport.ModuleIap2Route.lastPhone else null

    override fun usbPhonePresent(engine: Engine): Boolean =
        app.getSystemService(UsbManager::class.java)?.deviceList?.values.orEmpty().any { device ->
            runCatching { PhoneClassifier.classifyUsb(UsbRouterActivity.describe(device)).engine == engine }.getOrDefault(false)
        }

    /**
     * DiAuto's key and media receivers abort ordered key broadcasts; they are declared disabled
     * and enabled only while Android Auto is engaged, so CarPlay keeps the steering-wheel keys.
     */
    override fun onEngaged(engine: Engine?) {
        callMicrophone.setWatching(engine != null)
        if (preferences.rootMode) kotlin.concurrent.thread(name = "carlink-root") { com.carlink.core.settings.RootMode.projecting(engine != null) }
        val state = if (engine == Engine.ANDROID_AUTO) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        ANDROID_AUTO_KEY_RECEIVERS.forEach { name ->
            val component = ComponentName(app, name)
            runCatching {
                if (app.packageManager.getComponentEnabledSetting(component) != state) {
                    app.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                }
            }.onFailure { Log.w(TAG, "could not set $name", it) }
        }
    }

    override fun warmUp(request: SessionRequest, done: () -> Unit): Boolean {
        // The stereo's module reaches the phone itself; paging it on Android's radio only cost 6 s.
        val stereo = effectiveStereo(app)
        val moduleOn = preferences.wirelessOnExternalBluetooth
        val viaModule = when (request.engine) {
            Engine.ANDROID_AUTO -> moduleRoute(stereo, moduleOn) != null
            Engine.CARPLAY -> moduleCarPlay(stereo, moduleOn)
        }
        if (viaModule && request.transport == com.carlink.core.core.Transport.WIRELESS) return false
        return request.phoneAddress?.let { BluetoothWarmup.start(app, it, done) } ?: false
    }

    override fun returnHome() = CarLinkCore.config.host.openHome(app, CarLinkHost.OpenReason.UNPLUGGED)

    override fun log(message: String) = EventLog.add("CarLink", message)

    companion object {
        private const val TAG = "CarLink-Session"
        private const val KEY_LAST_PHONE = "address"
        /**
         * Android-Bluetooth problems that do not apply when the stereo's module carries Bluetooth.
         * No iPhone chosen too: the iPhone pairs with the module, which names it to the engine (#32).
         */
        private val MODULE_IRRELEVANT = setOf(Problem.BLUETOOTH_OFF, Problem.PHONE_NOT_PAIRED, Problem.PAIRING_LOST,
            Problem.NO_IPHONE_SELECTED)
        /** DiAuto's ZBT carrier repeats the wake while the module reports no phone and speaks first by 20 s. */
        private const val MODULE_AA_WAIT_MILLIS = 20_000L

        /**
         * Whether the module route is on for [stereo]: by itself where the ZLink daemon answers
         * ([StereoBluetooth.ZLINK_DAEMON], verified end to end 2026-11-04), otherwise only with the
         * driver's "Wireless through the stereo's Bluetooth module (experimental)" ([moduleOn]).
         */
        /** The stereo as CarLink routes it: no module when the driver chose Android's own Bluetooth (Home's chip). */
        fun effective(context: Context, stereo: StereoBluetooth): StereoBluetooth =
            effective(CarLinkPreferences(context).useAndroidBluetooth, stereo)

        /** The driver picked this screen's own Bluetooth on Home: the module is ignored, as if absent. */
        fun effective(useAndroidBluetooth: Boolean, stereo: StereoBluetooth): StereoBluetooth =
            if (useAndroidBluetooth) StereoBluetooth.NONE else stereo

        fun effectiveStereo(context: Context): StereoBluetooth = effective(context, ExternalBluetoothProbe.current(context).classification)

        fun moduleEnabled(stereo: StereoBluetooth, moduleOn: Boolean): Boolean =
            moduleOn || stereo == StereoBluetooth.ZLINK_DAEMON

        /** This stereo's module carries Bluetooth for at least one engine (probe finished; [moduleEnabled]). */
        fun moduleCarriesBluetooth(context: Context): Boolean =
            ExternalBluetoothProbe.cached()?.classification?.let { effective(context, it) }?.let { stereo ->
                stereo.blocksWireless && moduleEnabled(stereo, CarLinkPreferences(context).wirelessOnExternalBluetooth)
            } == true

        /**
         * Whether CarLink blocks wireless [engine] on a stereo classified [stereo]; [moduleOn] is the
         * driver's opt-in (see [moduleEnabled]). The module unblocks Android Auto only: neither module
         * route carries CarPlay's iAP2.
         */
        fun wirelessBlocked(stereo: StereoBluetooth, moduleOn: Boolean, engine: Engine): Boolean =
            stereo.blocksWireless && !(moduleEnabled(stereo, moduleOn) && engine == Engine.ANDROID_AUTO) &&
                !(engine == Engine.CARPLAY && moduleCarPlay(stereo, moduleOn))

        /**
         * Wireless CarPlay through the stereo's module (DiPlay's ModuleIap2Route), only with the
         * driver's opt-in: a ZLink-type daemon that answered the probe (verified only against an
         * emulated daemon, 2026-11-04; on EXTERNAL_UNKNOWN nothing listens on 3152, #24), or an FYT
         * module's /dev/rf_serial (protocol read from FYT's own CarPlay, #11; not yet tried on a unit).
         */
        fun moduleCarPlay(stereo: StereoBluetooth, moduleOn: Boolean): Boolean = moduleCarPlayKind(stereo, moduleOn) != null

        fun moduleCarPlayKind(stereo: StereoBluetooth, moduleOn: Boolean): ModuleIap2Route.Kind? = when {
            !moduleOn -> null
            stereo == StereoBluetooth.ZLINK_DAEMON -> ModuleIap2Route.Kind.ZBT_DAEMON
            stereo == StereoBluetooth.FYT_MODULE -> ModuleIap2Route.Kind.FYT_RF_SERIAL
            else -> null
        }

        /**
         * The route wireless Android Auto takes through the stereo's Bluetooth module when it is on
         * ([moduleEnabled]), or null for Android's own Bluetooth (design/EXTERNAL_BLUETOOTH.md):
         * a ZLink-type daemon, or ZLink markers with no daemon seen yet, take ZBT; ZLink markers whose
         * `/dev/socket/zj_bt_socket` accepts a connect take ZJ_SOCKET; FYT's module takes BLINK.
         */
        fun moduleRoute(stereo: StereoBluetooth, moduleOn: Boolean): ExternalBtTransportPolicy.Route? = when {
            !moduleEnabled(stereo, moduleOn) -> null
            stereo == StereoBluetooth.ZLINK_DAEMON || stereo == StereoBluetooth.EXTERNAL_UNKNOWN -> ExternalBtTransportPolicy.Route.ZBT
            stereo == StereoBluetooth.ZJ_SOCKET -> ExternalBtTransportPolicy.Route.ZJ_SOCKET
            stereo == StereoBluetooth.FYT_MODULE -> ExternalBtTransportPolicy.Route.BLINK
            else -> null
        }

        private fun lastPhone(context: Context) = context.getSharedPreferences("carlink_last_phone", Context.MODE_PRIVATE)

        /** The phone the driver last connected or asked for; Home's main button offers it first. */
        fun lastPhoneAddress(context: Context): String? = lastPhone(context).getString(KEY_LAST_PHONE, null)

        val ANDROID_AUTO_KEY_RECEIVERS = listOf(
            "com.andrerinas.openheadunit.connection.CarKeyReceiver",
            "com.andrerinas.openheadunit.app.RemoteControlReceiver",
            "androidx.media.session.MediaButtonReceiver",
        )
    }
}
