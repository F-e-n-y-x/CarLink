package com.carlink.app.connection

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.util.Log
import com.carlink.app.core.Engine
import com.carlink.app.core.PhoneKind
import com.carlink.app.detect.KnownPhones
import com.carlink.app.detect.PhoneClassifier
import com.carlink.app.diagnostics.EventLog
import com.carlink.app.entry.UsbRouterActivity
import com.carlink.app.settings.CarLinkPreferences
import com.carlink.app.settings.EngineSettingsApplier
import com.carlink.app.settings.UnifiedSettingsStore
import com.carlink.app.settings.WirelessPath
import com.carlink.app.ui.theme.UiProfileDetector

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

    override val autoConnectUsb get() = preferences.autoConnectUsb
    override val autoConnectBluetooth get() = preferences.autoConnectBluetooth
    override var autoConnectPaused: Boolean
        get() = preferences.autoConnectPaused
        set(value) { preferences.autoConnectPaused = value }

    override fun preflight(request: SessionRequest, path: WirelessPath?): List<Problem> {
        val settings = unified.load()
        val wifi = EngineSettingsApplier.wifiEnvironment(app, settings)
        val facts = DevicePreflight.facts(app, request.phoneAddress)
        return (DevicePreflight.check(request, path, facts, settings, wifi) + enginePreflight(request, path)).distinct()
    }

    override fun wirelessPaths(request: SessionRequest): List<WirelessPath> {
        val settings = unified.load()
        return WirelessPolicy.candidates(WirelessPolicy.Inputs(
            engine = request.engine,
            settings = settings,
            wifi = EngineSettingsApplier.wifiEnvironment(app, settings),
            headUnit = UiProfileDetector.looksLikeCarDisplay(app),
            lastWorking = request.phoneAddress?.let { paths.lastWorking(request.engine, it) },
        ))
    }

    override fun rememberWorkingPath(engine: Engine, phoneAddress: String, path: WirelessPath) =
        paths.remember(engine, phoneAddress, path)

    override fun rememberPhone(phoneAddress: String, kind: PhoneKind) {
        knownPhones.remember(phoneAddress, kind)
        lastPhone(app).edit().putString(KEY_LAST_PHONE, phoneAddress).apply()
    }

    override fun usbPhonePresent(engine: Engine): Boolean =
        app.getSystemService(UsbManager::class.java)?.deviceList?.values.orEmpty().any { device ->
            runCatching { PhoneClassifier.classifyUsb(UsbRouterActivity.describe(device)).engine == engine }.getOrDefault(false)
        }

    /**
     * DiAuto's key and media receivers abort ordered key broadcasts; they are declared disabled
     * and enabled only while Android Auto is engaged, so CarPlay keeps the steering-wheel keys.
     */
    override fun onEngaged(engine: Engine?) {
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

    override fun returnHome() = com.carlink.app.entry.HomeLauncher.open(app, com.carlink.app.entry.HomeLauncher.Reason.UNPLUGGED)

    override fun log(message: String) = EventLog.add("CarLink", message)

    companion object {
        private const val TAG = "CarLink-Session"
        private const val KEY_LAST_PHONE = "address"

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
