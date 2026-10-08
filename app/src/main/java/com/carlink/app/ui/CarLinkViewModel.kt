package com.carlink.app.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.main.SettingsActivity
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.PhoneKind
import com.carlink.app.core.Transport
import com.carlink.app.detect.BluetoothAvailability
import com.carlink.app.detect.PairedPhone
import com.carlink.app.detect.PhonesRepository
import com.carlink.app.engine.EngineCoordinator
import com.carlink.app.settings.CarLinkPreferences
import com.carlink.app.settings.EngineSettingsApplier
import com.carlink.app.settings.UnifiedSettings
import com.carlink.app.settings.UnifiedSettingsStore
import com.carlink.app.settings.WirelessPath
import com.carlink.app.settings.WirelessPathSelector
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfileMode
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.DiPlayActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** CarLink's own preferences as the UI renders them. */
data class DisplayPrefs(
    val themeMode: ThemeMode,
    val uiProfileMode: UiProfileMode,
    val interfaceScale: Float,
)

data class ConnectionPrefs(
    val autoConnectUsb: Boolean,
    val autoConnectBluetooth: Boolean,
    val startOnBoot: Boolean,
    val autoConnectPaused: Boolean,
)

/** The wireless path the next session would use, for Home's Network chip. */
data class NetworkPreview(val path: WirelessPath, val ssid: String?)

/** What Home's hero shows (DESIGN_SPEC §6.1.1 hero states). */
data class HomeState(
    val link: LinkState,
    val phones: List<PairedPhone>,
    val bluetooth: BluetoothAvailability,
    /** The phone the primary button connects: the connected one, else the first known one. */
    val defaultPhone: PairedPhone?,
    /** elapsedRealtime when the current session reached CONNECTED, or null. */
    val connectedSince: Long?,
)

/**
 * The single source of truth for every CarLink screen. Screens read the StateFlows and call
 * the intent methods; none of them talks to an engine directly.
 */
class CarLinkViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val coordinator = EngineCoordinator.get(app)
    private val preferences = CarLinkPreferences(app)
    private val phonesRepository = PhonesRepository(app)

    val link: StateFlow<LinkState> = coordinator.state

    private val _connectedSince = MutableStateFlow<Long?>(null)
    /** When the current attempt started (for the Connecting timer), or null. */
    private val _attemptStartedAt = MutableStateFlow<Long?>(null)
    val attemptStartedAt: StateFlow<Long?> = _attemptStartedAt.asStateFlow()

    val home: StateFlow<HomeState> = combine(
        coordinator.state, phonesRepository.phones, phonesRepository.availability, _connectedSince,
    ) { link, phones, bluetooth, since ->
        HomeState(link, phones, bluetooth, defaultPhone(phones), since)
    }.stateIn(viewModelScope, SharingStarted.Eagerly,
        HomeState(LinkState.Idle, emptyList(), BluetoothAvailability.READY, null, null))

    private val _display = MutableStateFlow(readDisplay())
    val display: StateFlow<DisplayPrefs> = _display.asStateFlow()

    private val _connection = MutableStateFlow(readConnection())
    val connection: StateFlow<ConnectionPrefs> = _connection.asStateFlow()

    val onboardingDone: Boolean get() = preferences.onboardingDone

    private val unifiedStore = UnifiedSettingsStore(app)
    private val _unified = MutableStateFlow(unifiedStore.load())
    /** The one set of settings both engines use. */
    val unified: StateFlow<UnifiedSettings> = _unified.asStateFlow()

    private val _network = MutableStateFlow<NetworkPreview?>(null)
    val network: StateFlow<NetworkPreview?> = _network.asStateFlow()

    private val _permissionsNeeded = MutableStateFlow(missingPermissions().isNotEmpty())
    /** True until the runtime permissions CarLink needs to find phones are granted. */
    val permissionsNeeded: StateFlow<Boolean> = _permissionsNeeded.asStateFlow()

    init {
        phonesRepository.start()
        viewModelScope.launch {
            coordinator.state.collect { state ->
                when (state.stage) {
                    LinkStage.CONNECTED -> if (_connectedSince.value == null) _connectedSince.value = SystemClock.elapsedRealtime()
                    LinkStage.WAITING, LinkStage.CONNECTING ->
                        if (_attemptStartedAt.value == null) _attemptStartedAt.value = SystemClock.elapsedRealtime()
                    LinkStage.IDLE, LinkStage.FAILED -> {
                        _connectedSince.value = null
                        if (state.stage == LinkStage.IDLE) _attemptStartedAt.value = null
                    }
                }
                if (state.stage == LinkStage.CONNECTED) _attemptStartedAt.value = null
            }
        }
    }

    override fun onCleared() {
        phonesRepository.stop()
    }

    // ---- Connection intents -------------------------------------------------------------

    /** Connect this phone wirelessly (Home's phone list / primary button). */
    fun connect(phone: PairedPhone) {
        _connection.value = _connection.value.copy(autoConnectPaused = false)
        _attemptStartedAt.value = SystemClock.elapsedRealtime()
        coordinator.connectPhone(phone.address, phone.kind, phone.name, Transport.WIRELESS)
    }

    /**
     * The driver chose another phone while one is projecting: end that session and start this
     * one. Unlike [disconnect], this does not pause automatic connection.
     */
    fun switchTo(phone: PairedPhone) {
        coordinator.stopAll()
        connect(phone)
    }

    /** Wired start for a phone that is already plugged in but was not started automatically. */
    fun connectUsb(kind: PhoneKind) {
        _attemptStartedAt.value = SystemClock.elapsedRealtime()
        coordinator.connectPhone(null, kind, null, Transport.USB)
    }

    /** Retry the last attempt with the same phone and transport. */
    fun retry() {
        val state = link.value
        val phone = home.value.phones.firstOrNull { it.name == state.phoneName } ?: home.value.defaultPhone
        when {
            state.transport == Transport.USB && state.engine != null ->
                connectUsb(if (state.engine == Engine.CARPLAY) PhoneKind.IPHONE else PhoneKind.ANDROID)
            phone != null -> connect(phone)
        }
    }

    /** Cancel an attempt or end a session; pauses automatic starts (DESIGN_SPEC §6.2.1). */
    fun disconnect() {
        coordinator.disconnect()
        _connection.value = readConnection()
    }

    /** Bring the running engine's projection back to the front. */
    fun returnToProjection() {
        val intent = when (link.value.engine) {
            Engine.CARPLAY -> Intent(app, CarPlayHostActivity::class.java)
            Engine.ANDROID_AUTO -> Intent(app, AapProjectionActivity::class.java)
            null -> return
        }
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /** Re-reads phones, permissions and the network path (Home calls this on resume). */
    fun refreshPhones() {
        phonesRepository.refresh()
        _permissionsNeeded.value = missingPermissions().isNotEmpty()
        refreshNetwork()
    }

    private fun refreshNetwork() {
        val engine = home.value.defaultPhone?.kind?.engine ?: Engine.CARPLAY
        val wifi = EngineSettingsApplier.wifiEnvironment(app)
        val path = WirelessPathSelector.select(engine, _unified.value, wifi)
        _network.value = NetworkPreview(path, if (path == WirelessPath.SAME_WIFI) wifi.joinedSsid else null)
    }

    /** Saves a change to the unified settings and writes it into both engines. */
    fun updateSettings(change: (UnifiedSettings) -> UnifiedSettings) {
        val next = change(_unified.value)
        if (next == _unified.value) return
        _unified.value = next
        unifiedStore.save(next)
        coordinator.applySettings()
        refreshNetwork()
    }

    /** Runtime permissions CarLink asks for in one go (Bluetooth, Wi-Fi, location, microphone, notifications). */
    fun missingPermissions(): List<String> = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            add(android.Manifest.permission.BLUETOOTH_CONNECT)
            add(android.Manifest.permission.BLUETOOTH_SCAN)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        add(android.Manifest.permission.RECORD_AUDIO)
    }.filter { app.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }

    fun setPhoneKind(phone: PairedPhone, kind: PhoneKind) = phonesRepository.setKind(phone.address, kind)

    // ---- Preferences -------------------------------------------------------------------

    fun setThemeMode(mode: ThemeMode) { preferences.themeMode = mode; _display.value = readDisplay() }
    fun setUiProfileMode(mode: UiProfileMode) { preferences.uiProfileMode = mode; _display.value = readDisplay() }
    fun setInterfaceScale(scale: Float) { preferences.interfaceScale = scale; _display.value = readDisplay() }

    fun setAutoConnectUsb(on: Boolean) { preferences.autoConnectUsb = on; _connection.value = readConnection() }
    fun setAutoConnectBluetooth(on: Boolean) { preferences.autoConnectBluetooth = on; _connection.value = readConnection() }
    fun setStartOnBoot(on: Boolean) { preferences.startOnBoot = on; _connection.value = readConnection() }
    fun resumeAutoConnect() { preferences.autoConnectPaused = false; _connection.value = readConnection() }

    fun completeOnboarding() { preferences.onboardingDone = true }

    // ---- Other screens -----------------------------------------------------------------

    /** DiPlay's settings, in its own window; the page extra keeps "connect when opened" off. */
    fun openCarPlaySettings() = start(Intent(app, DiPlayActivity::class.java).putExtra("page", "settings"))

    /** DiAuto's settings, which run in the Android Auto engine process. */
    fun openAndroidAutoSettings() = start(Intent(app, SettingsActivity::class.java))

    fun openBluetoothSettings() = start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    fun openAppSettings() = start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", app.packageName, null)))

    private fun start(intent: Intent) {
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun defaultPhone(phones: List<PairedPhone>): PairedPhone? =
        phones.firstOrNull { it.connected && it.kind != PhoneKind.UNKNOWN }
            ?: phones.firstOrNull { it.kind != PhoneKind.UNKNOWN }

    private fun readDisplay() = DisplayPrefs(preferences.themeMode, preferences.uiProfileMode, preferences.interfaceScale)

    private fun readConnection() = ConnectionPrefs(
        preferences.autoConnectUsb, preferences.autoConnectBluetooth, preferences.startOnBoot, preferences.autoConnectPaused)
}
