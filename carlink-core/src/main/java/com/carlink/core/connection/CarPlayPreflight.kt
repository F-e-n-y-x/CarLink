package com.carlink.core.connection

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import com.carlink.core.core.Transport
import com.carlink.core.settings.WifiEnvironmentReader
import com.carlink.core.settings.UnifiedSettings
import com.carlink.core.settings.WifiDetails
import com.carlink.core.settings.WirelessPath
import com.shilapi.xcertplay.CarPlayHostIntegration
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

/** Something that keeps a CarPlay session from starting, with what the fix needs to know. */
sealed interface CarPlayProblem {
    /** DiPlay's authentication files (MFi identity) are missing or unreadable (§1.3 item 4). */
    data object AuthenticationMissing : CarPlayProblem

    /** Wired CarPlay needs the VPN consent (`VpnService.prepare`), F3. */
    data object VpnConsentNeeded : CarPlayProblem

    /** Runtime permissions the CarPlay host would otherwise ask for over the projection (F3, F4). */
    data class PermissionsMissing(val permissions: List<String>) : CarPlayProblem

    /** Wireless CarPlay opens RFCOMM to the iPhone; with Bluetooth off it fails at once (F16). */
    data object BluetoothOff : CarPlayProblem

    /** Wireless CarPlay needs a chosen iPhone (`choosePhone`, §2.5 step 6). */
    data object NoIphoneSelected : CarPlayProblem

    /** The chosen iPhone is no longer bonded with this device (F16; CarPlayController ~2174). */
    data class IphoneNotBonded(val address: String) : CarPlayProblem

    /** Wi-Fi Direct and Same Wi-Fi need the Wi-Fi radio on. */
    data object WifiOff : CarPlayProblem

    /** Same Wi-Fi: no saved network name, or a name/password DiPlay would reject (F17). */
    data object SameWifiDetailsMissing : CarPlayProblem

    /** Same Wi-Fi: this device is not on any Wi-Fi network. */
    data object SameWifiNotConnected : CarPlayProblem

    /** Same Wi-Fi: two Wi-Fi networks are up (dual Wi-Fi); DiPlay refuses that (F7, #290). */
    data object SameWifiMultipleNetworks : CarPlayProblem

    /**
     * Same Wi-Fi: Android hides the joined network's name, so neither CarLink nor DiPlay can
     * confirm it is the saved one and a typo would only show as "no AirPlay TCP" (F7, §3.4 #7).
     */
    data class SameWifiNameUnreadable(val needsLocationPermission: Boolean, val needsLocationOn: Boolean) : CarPlayProblem

    /** Same Wi-Fi: this device is on [joined], not on the saved [saved] network. */
    data class SameWifiDifferentNetwork(val joined: String, val saved: String) : CarPlayProblem

    /** Car hotspot: name or password missing or invalid (§2.5 step 4). */
    data object CarHotspotDetailsMissing : CarPlayProblem

    /** Car hotspot: reported off and CarLink cannot switch it on (§2.5 step 5). */
    data object CarHotspotOff : CarPlayProblem
}

/** Everything [CarPlayPreflight.check] looks at, gathered without opening anything. */
data class CarPlayPreflightInput(
    val transport: Transport,
    /** Wireless only; null is treated as Wi-Fi Direct. */
    val path: WirelessPath?,
    val sdkInt: Int,
    val authenticationReady: Boolean,
    val grantedPermissions: Set<String>,
    val vpnConsentGranted: Boolean,
    /** Null when the device has no Bluetooth adapter. */
    val bluetoothOn: Boolean?,
    /** Bonded device addresses (upper case); null when they cannot be read (no BLUETOOTH_CONNECT). */
    val bondedAddresses: Set<String>?,
    val iphoneAddress: String?,
    val wifiOn: Boolean,
    val locationOn: Boolean,
    val homeWifi: WifiDetails,
    val carHotspot: WifiDetails,
    /** Client Wi-Fi networks (not VPN) and the joined name when exactly one and readable. */
    val joinedNetworks: Int,
    val joinedSsid: String?,
    /** Null when the firmware does not say (§12 row 7: treat as "unknown", not off). */
    val carHotspotOn: Boolean?,
    /** CarLink can switch the hotspot on itself (DiPlay's adb/WRITE_SETTINGS automation). */
    val carHotspotCanStart: Boolean,
)

/**
 * CarPlay pre-flight v2 (design/REBUILD_V2.md §4 "Preflight", §6): the checks DiPlay's own
 * `connect()` ran (§2.5) plus what the projection host would otherwise ask or fail on in front of
 * the driver (F3, F4, F7, F16, F17). Returns every problem as data, most fundamental first, and
 * opens nothing: no dialog, no activity, no engine.
 */
object CarPlayPreflight {
    /** Android 17 Local Network Protection (#425; F4). */
    const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val SDK_LOCAL_NETWORK = 37

    /**
     * The runtime permissions the host requests for this session (CarPlayHostActivity
     * `requiredWirelessPermissions`, §1.3 item 2), plus ACCESS_LOCAL_NETWORK from SDK 37, without
     * which local IPv4 is silently dropped (#425, F4). Wired sessions need only the VPN consent.
     */
    fun requiredPermissions(transport: Transport, path: WirelessPath?, sdkInt: Int): List<String> {
        if (transport == Transport.USB) return emptyList()
        val host = when {
            path == WirelessPath.SAME_WIFI ->
                if (sdkInt >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
            sdkInt >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES)
            sdkInt >= Build.VERSION_CODES.S -> listOf(
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
            else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return if (sdkInt >= SDK_LOCAL_NETWORK) host + ACCESS_LOCAL_NETWORK else host
    }

    fun check(input: CarPlayPreflightInput): List<CarPlayProblem> = buildList {
        if (!input.authenticationReady) add(CarPlayProblem.AuthenticationMissing)
        if (input.transport == Transport.USB) {
            if (!input.vpnConsentGranted) add(CarPlayProblem.VpnConsentNeeded)
            return@buildList
        }
        val path = input.path ?: WirelessPath.WIFI_DIRECT
        requiredPermissions(input.transport, path, input.sdkInt).filterNot { it in input.grantedPermissions }
            .takeIf { it.isNotEmpty() }?.let { add(CarPlayProblem.PermissionsMissing(it)) }
        if (input.bluetoothOn != true) add(CarPlayProblem.BluetoothOff)
        val address = input.iphoneAddress
        if (address == null) {
            add(CarPlayProblem.NoIphoneSelected)
        } else if (input.bondedAddresses != null && address.uppercase() !in input.bondedAddresses) {
            add(CarPlayProblem.IphoneNotBonded(address))
        }
        if (path != WirelessPath.CAR_HOTSPOT && !input.wifiOn) add(CarPlayProblem.WifiOff)
        when (path) {
            WirelessPath.SAME_WIFI -> sameWifi(input)?.let(::add)
            WirelessPath.CAR_HOTSPOT -> when {
                ManualHotspotValidation.error(input.carHotspot.ssid, input.carHotspot.password) != null ->
                    add(CarPlayProblem.CarHotspotDetailsMissing)
                input.carHotspotOn == false && !input.carHotspotCanStart -> add(CarPlayProblem.CarHotspotOff)
            }
            WirelessPath.WIFI_DIRECT -> Unit
        }
    }

    private fun sameWifi(input: CarPlayPreflightInput): CarPlayProblem? {
        val saved = input.homeWifi
        // CarLink's own values, not DiPlay's copy (F17). Same rules DiPlay applies to its fields.
        if (ManualHotspotValidation.error(saved.ssid, saved.password) != null) return CarPlayProblem.SameWifiDetailsMissing
        if (!input.wifiOn) return null // reported as WifiOff
        return when {
            input.joinedNetworks == 0 -> CarPlayProblem.SameWifiNotConnected
            input.joinedNetworks > 1 -> CarPlayProblem.SameWifiMultipleNetworks
            input.joinedSsid == null -> CarPlayProblem.SameWifiNameUnreadable(
                needsLocationPermission = Manifest.permission.ACCESS_FINE_LOCATION !in input.grantedPermissions,
                needsLocationOn = !input.locationOn,
            )
            // Exact match: DiPlay sends the saved name untrimmed in 0x5703 (§3.4 #7).
            input.joinedSsid != saved.ssid -> CarPlayProblem.SameWifiDifferentNetwork(input.joinedSsid, saved.ssid)
            else -> null
        }
    }

    /** Reads [CarPlayPreflightInput] for [plan]; no dialog, no activity, no engine start. */
    fun inputs(context: Context, plan: SessionPlan, settings: UnifiedSettings): CarPlayPreflightInput {
        val app = context.applicationContext
        val sdk = Build.VERSION.SDK_INT
        val permissions = buildSet {
            (requiredPermissions(Transport.WIRELESS, plan.path, sdk) + Manifest.permission.ACCESS_FINE_LOCATION +
                Manifest.permission.BLUETOOTH_CONNECT + ACCESS_LOCAL_NETWORK).forEach {
                if (app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) add(it)
            }
        }
        val adapter = runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        val canReadBonds = sdk < Build.VERSION_CODES.S || Manifest.permission.BLUETOOTH_CONNECT in permissions
        val bonded = if (adapter != null && canReadBonds) {
            runCatching { adapter.bondedDevices.orEmpty().mapNotNull { it.address?.uppercase() }.toSet() }.getOrNull()
        } else null
        val wifi = runCatching { WifiEnvironmentReader.read(app) }.getOrNull()
        val location = app.getSystemService(LocationManager::class.java)
        val locationOn = runCatching {
            if (sdk >= Build.VERSION_CODES.P) location?.isLocationEnabled == true
            else location?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true ||
                location?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        }.getOrDefault(false)
        return CarPlayPreflightInput(
            transport = plan.transport,
            path = plan.path,
            sdkInt = sdk,
            authenticationReady = CarPlayHostIntegration.authenticationError(app) == null,
            grantedPermissions = permissions,
            // Allowed silently over the stereo's own adb where it can be, so the dialog never shows there.
            vpnConsentGranted = plan.transport != Transport.USB || AdbVpnConsent.ensure(app),
            bluetoothOn = adapter?.let { runCatching { it.isEnabled }.getOrDefault(false) },
            bondedAddresses = bonded,
            iphoneAddress = plan.phoneAddress ?: CarPlayHostIntegration.selectedPhoneAddress(app),
            wifiOn = runCatching { app.getSystemService(WifiManager::class.java)?.isWifiEnabled == true }.getOrDefault(false),
            locationOn = locationOn,
            homeWifi = settings.homeWifi,
            carHotspot = settings.carHotspot,
            joinedNetworks = wifi?.joinedNetworks ?: 0,
            joinedSsid = wifi?.joinedSsid,
            carHotspotOn = wifi?.carHotspotOn ?: runCatching { CarHotspotStatus.isEnabled(app) }.getOrNull(),
            carHotspotCanStart = runCatching { CarHotspotSettings.enabled(app) && CarHotspotTethering.permitted(app) }
                .getOrDefault(false),
        )
    }

    fun check(context: Context, plan: SessionPlan, settings: UnifiedSettings): List<CarPlayProblem> =
        check(inputs(context, plan, settings))
}
