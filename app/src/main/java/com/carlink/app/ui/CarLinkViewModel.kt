package com.carlink.app.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.PhoneKind
import com.carlink.app.core.Transport
import com.carlink.app.detect.BluetoothAvailability
import com.carlink.app.detect.PairedPhone
import com.carlink.app.detect.PhonesRepository
import com.carlink.app.connection.Connection
import com.carlink.app.connection.DevicePreflight
import com.carlink.app.connection.SessionState
import com.carlink.app.connection.WirelessPolicy
import com.carlink.app.connection.toLinkState
import kotlinx.coroutines.flow.map
import com.carlink.app.settings.CarLinkPreferences
import com.carlink.app.settings.ConnectionMethod
import com.carlink.app.settings.EngineSettingsApplier
import com.carlink.app.settings.UnifiedSettings
import com.carlink.app.settings.UnifiedSettingsStore
import com.carlink.app.settings.WirelessPath
import com.carlink.app.settings.WirelessPathSelector
import com.carlink.app.settings.isValid
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfileMode
import com.shilapi.xcertplay.CarPlayHostActivity
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
    /** The floating switcher over the projection (Settings › General). */
    val floatingSwitcher: Boolean = true,
)

/** The wireless path the next session would use, for Home's Network chip. */
data class NetworkPreview(
    val path: WirelessPath,
    val ssid: String?,
    /** The car's current Wi-Fi when exactly one is joined and its name is readable. */
    val joinedSsid: String? = null,
    /** True when [joinedSsid] is not the saved "same Wi-Fi" network yet. */
    val canOfferSameWifi: Boolean = false,
    /** The driver's choice; [path] is what it resolves to right now. */
    val method: ConnectionMethod = ConnectionMethod.AUTOMATIC,
)

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
    private val session = Connection.get(app)
    private val preferences = CarLinkPreferences(app)
    private val phonesRepository = PhonesRepository(app)

    /** A second USB phone waiting for the driver's "Switch to …?" answer. */
    val pendingSwitch = session.pendingSwitch
    fun acceptSwitch() = session.acceptSwitch()
    fun declineSwitch() = session.declineSwitch()

    val link: StateFlow<LinkState> = session.state.map { it.toLinkState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.state.value.toLinkState())

    private val _connectedSince = MutableStateFlow<Long?>(null)
    /** When the current attempt started (for the Connecting timer), or null. */
    private val _attemptStartedAt = MutableStateFlow<Long?>(null)
    val attemptStartedAt: StateFlow<Long?> = _attemptStartedAt.asStateFlow()

    val home: StateFlow<HomeState> = combine(
        link, phonesRepository.phones, phonesRepository.availability, _connectedSince,
    ) { link, phones, bluetooth, since ->
        HomeState(link, phones, bluetooth, defaultPhone(phones), since)
    }.stateIn(viewModelScope, SharingStarted.Eagerly,
        HomeState(LinkState.Idle, emptyList(), BluetoothAvailability.READY, null, null))

    private val _display = MutableStateFlow(readDisplay())
    val display: StateFlow<DisplayPrefs> = _display.asStateFlow()

    private val _connection = MutableStateFlow(readConnection())
    val connection: StateFlow<ConnectionPrefs> = _connection.asStateFlow()

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
            link.collect { state ->
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
        session.connect(phone.address, phone.kind, phone.name, Transport.WIRELESS)
    }

    /**
     * The driver chose another phone while one is projecting: end that session and start this
     * one. Unlike [disconnect], this does not pause automatic connection.
     */
    fun switchTo(phone: PairedPhone) = connect(phone)

    /** Wired start for a phone that is already plugged in but was not started automatically. */
    fun connectUsb(kind: PhoneKind) {
        _attemptStartedAt.value = SystemClock.elapsedRealtime()
        session.connect(null, kind, null, Transport.USB)
    }

    /** Retry the last attempt with the same phone and transport. */
    fun retry() {
        val current = session.state.value
        if (current is SessionState.Blocked || current is SessionState.Failed) {
            _attemptStartedAt.value = SystemClock.elapsedRealtime()
            return session.retry()
        }
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
        session.disconnect()
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
        // The floating switcher may have changed the connection type or paused automatic starts
        // while Home was in the background.
        unifiedStore.load().let { if (it != _unified.value) _unified.value = it }
        _connection.value = readConnection()
        phonesRepository.refresh()
        _permissionsNeeded.value = missingPermissions().isNotEmpty()
        refreshNetwork()
    }

    private fun refreshNetwork() {
        val engine = home.value.defaultPhone?.kind?.engine ?: Engine.CARPLAY
        val settings = _unified.value
        val wifi = EngineSettingsApplier.wifiEnvironment(app, settings)
        val effective = if (!settings.carHotspot.isValid() && wifi.systemHotspot != null) {
            settings.copy(carHotspot = wifi.systemHotspot)
        } else settings
        val path = WirelessPolicy.candidates(WirelessPolicy.Inputs(engine, effective, wifi,
            headUnit = com.carlink.app.ui.theme.UiProfileDetector.looksLikeCarDisplay(app))).first()
        val saved = settings.homeWifi
        _network.value = NetworkPreview(
            path = path,
            ssid = if (path == WirelessPath.SAME_WIFI) wifi.joinedSsid else null,
            joinedSsid = wifi.joinedSsid,
            // Offered again when the saved entry cannot work: no password for a secured network,
            // or a phone already refused the one saved.
            canOfferSameWifi = wifi.joinedSsid != null && (wifi.joinedSsid != saved.ssid ||
                (saved.password.isEmpty() && wifi.joinedOpen != true) || wifi.androidAutoRefusedJoined),
            method = settings.connectionMethod,
        )
    }

    /** Remembers the car's current Wi-Fi so wireless CarPlay can use it (Same Wi-Fi). */
    fun saveSameWifi(ssid: String, password: String) =
        updateSettings { it.copy(homeWifi = com.carlink.app.settings.WifiDetails(ssid, password)) }

    /** Saves a change to the unified settings and writes it into both engines. */
    fun updateSettings(change: (UnifiedSettings) -> UnifiedSettings) {
        val next = change(_unified.value)
        if (next == _unified.value) return
        _unified.value = next
        unifiedStore.save(next)
        // Engines read their settings at the start of each session (SessionManager writes them).
        refreshNetwork()
    }

    /** Runtime permissions CarLink asks for in one go (Bluetooth, Wi-Fi, location, microphone, notifications). */
    fun missingPermissions(): List<String> = DevicePreflight.requiredPermissions()
        .filter { app.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }

    /**
     * The one-tap fix for a problem Home shows. Radios and system switches can only be offered by
     * Android's own panels; the driver then taps Try again (or the session retries on its own).
     */
    fun fix(problem: com.carlink.app.core.LinkProblem) {
        val intent = when (problem) {
            com.carlink.app.core.LinkProblem.BLUETOOTH_OFF -> Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            com.carlink.app.core.LinkProblem.WIFI_OFF -> Intent(if (android.os.Build.VERSION.SDK_INT >= 29)
                android.provider.Settings.Panel.ACTION_WIFI else android.provider.Settings.ACTION_WIFI_SETTINGS)
            com.carlink.app.core.LinkProblem.LOCATION_OFF -> Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            com.carlink.app.core.LinkProblem.OVERLAY -> Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${app.packageName}"))
            com.carlink.app.core.LinkProblem.VPN_CONSENT -> android.net.VpnService.prepare(app) ?: return retry()
            com.carlink.app.core.LinkProblem.PHONE_REFUSED_WIFI, com.carlink.app.core.LinkProblem.PHONE_NEVER_REACHED_CAR -> {
                updateSettings { it.copy(connectionMethod = com.carlink.app.settings.ConnectionMethod.WIFI_DIRECT) }
                return retry()
            }
            else -> return
        }
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun setPhoneKind(phone: PairedPhone, kind: PhoneKind) = phonesRepository.setKind(phone.address, kind)

    // ---- Preferences -------------------------------------------------------------------

    fun setThemeMode(mode: ThemeMode) { preferences.themeMode = mode; _display.value = readDisplay() }
    fun setUiProfileMode(mode: UiProfileMode) { preferences.uiProfileMode = mode; _display.value = readDisplay() }
    fun setInterfaceScale(scale: Float) { preferences.interfaceScale = scale; _display.value = readDisplay() }

    fun setAutoConnectUsb(on: Boolean) { preferences.autoConnectUsb = on; _connection.value = readConnection() }
    fun setAutoConnectBluetooth(on: Boolean) { preferences.autoConnectBluetooth = on; _connection.value = readConnection() }
    fun setStartOnBoot(on: Boolean) { preferences.startOnBoot = on; _connection.value = readConnection() }
    fun resumeAutoConnect() { preferences.autoConnectPaused = false; _connection.value = readConnection() }
    fun setFloatingSwitcher(on: Boolean) {
        preferences.floatingSwitcher = on
        _connection.value = readConnection()
        com.carlink.app.switcher.SwitcherController.onSettingChanged()
    }

    // ---- Other screens -----------------------------------------------------------------

    // Engine settings are edited natively in CarLink's Settings (ui/settings); the engines' own
    // settings screens are never opened.

    fun openBluetoothSettings() = start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    fun openAppSettings() = start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", app.packageName, null)))

    private fun start(intent: Intent) {
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * The phone Home's main button offers: the one in the current session, else the one the
     * driver used last, else one whose Bluetooth is connected, else the first known phone.
     */
    private fun defaultPhone(phones: List<PairedPhone>): PairedPhone? {
        val known = phones.filter { it.kind != PhoneKind.UNKNOWN }
        fun byAddress(address: String?) = address?.let { a -> known.firstOrNull { it.address.equals(a, ignoreCase = true) } }
        val current = (session.state.value as? SessionState.Active)?.request?.phoneAddress
        return byAddress(current)
            ?: byAddress(com.carlink.app.connection.DeviceEnvironment.lastPhoneAddress(app))
            ?: known.firstOrNull { it.connected }
            ?: known.firstOrNull()
    }

    private fun readDisplay() = DisplayPrefs(preferences.themeMode, preferences.uiProfileMode, preferences.interfaceScale)

    // The short pause after a Disconnect is silent: Home shows its normal ready state, no "Resume".
    private fun readConnection() = ConnectionPrefs(
        preferences.autoConnectUsb, preferences.autoConnectBluetooth, preferences.startOnBoot, autoConnectPaused = false,
        floatingSwitcher = preferences.floatingSwitcher)
}
