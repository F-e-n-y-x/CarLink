package com.carlink.app.ui.setup

import android.Manifest
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.carlink.app.R
import com.carlink.core.connection.CarPlayPreflight
import com.carlink.core.connection.DevicePreflight
import com.carlink.core.connection.PairingSession
import com.carlink.core.core.LinkState
import com.carlink.core.core.Transport
import com.carlink.core.detect.PairedPhone
import com.carlink.core.settings.CarBrand
import com.carlink.core.settings.WirelessPath

/** The setup's steps, in order. */
enum class SetupStep { WELCOME, PERMISSIONS, CAR, PAIR, DONE }

/** How a [SetupItem] is granted: one runtime dialog for all, or its own system screen. */
enum class GrantKind { RUNTIME, OVERLAY, BATTERY, VPN }

/**
 * How much a session needs a [SetupItem]. Recommended items are not needed to connect, but
 * without them Android asks (VPN) or CarLink cannot come forward (overlay) in the middle of a
 * drive, so the setup marks them and asks for them up front.
 */
enum class Need { REQUIRED, RECOMMENDED, OPTIONAL }

/**
 * One line of the permission step: what it is for, in one line, and how much a session needs it
 * ([need]; a missing optional one leaves "Allow all" unlit). Runtime items group Android
 * permissions the driver thinks of as one thing (Bluetooth connect + scan, fine + coarse location).
 */
enum class SetupItem(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val purpose: Int,
    val grant: GrantKind,
    val need: Need = Need.REQUIRED,
) {
    BLUETOOTH(R.drawable.carlink_ic_bluetooth, R.string.carlink_setup_perm_bluetooth, R.string.carlink_setup_perm_bluetooth_detail, GrantKind.RUNTIME),
    NEARBY_WIFI(R.drawable.carlink_ic_p2p, R.string.carlink_setup_perm_wifi, R.string.carlink_setup_perm_wifi_detail, GrantKind.RUNTIME),
    LOCATION(R.drawable.carlink_ic_location_on, R.string.carlink_setup_perm_location, R.string.carlink_setup_perm_location_detail, GrantKind.RUNTIME),
    LOCAL_NETWORK(R.drawable.carlink_ic_wifi, R.string.carlink_setup_perm_local_network, R.string.carlink_setup_perm_local_network_detail,
        GrantKind.RUNTIME),
    MICROPHONE(R.drawable.carlink_ic_mic, R.string.carlink_setup_perm_microphone, R.string.carlink_setup_perm_microphone_detail, GrantKind.RUNTIME),
    NOTIFICATIONS(R.drawable.carlink_ic_notifications, R.string.carlink_setup_perm_notifications,
        R.string.carlink_setup_perm_notifications_detail, GrantKind.RUNTIME),
    OVERLAY(R.drawable.carlink_ic_layers, R.string.carlink_setup_perm_overlay, R.string.carlink_setup_perm_overlay_detail, GrantKind.OVERLAY,
        Need.RECOMMENDED),
    VPN(R.drawable.carlink_ic_cable, R.string.carlink_setup_perm_vpn, R.string.carlink_setup_perm_vpn_detail, GrantKind.VPN, Need.RECOMMENDED),
    BATTERY(R.drawable.carlink_ic_battery_charging_full, R.string.carlink_setup_perm_battery, R.string.carlink_setup_perm_battery_detail,
        GrantKind.BATTERY, Need.OPTIONAL),
}

/** A [SetupItem] with the Android permissions behind it on this device and whether it is granted. */
data class SetupPermission(val item: SetupItem, val permissions: List<String>, val granted: Boolean)

object SetupPermissions {
    /**
     * Every runtime permission a session needs ([DevicePreflight.requiredPermissions]) plus the
     * wireless CarPlay host's own ([CarPlayPreflight.requiredPermissions], e.g. local network on
     * Android 17), grouped into [SetupItem]s; then the special grants. Pure, for tests.
     */
    fun items(sdk: Int, runtime: List<String> = runtimePermissions(sdk)): List<Pair<SetupItem, List<String>>> {
        fun of(item: SetupItem, vararg names: String) = item to runtime.filter { it in names }
        val groups = listOf(
            of(SetupItem.BLUETOOTH, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
            of(SetupItem.NEARBY_WIFI, NEARBY_WIFI),
            of(SetupItem.LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            of(SetupItem.LOCAL_NETWORK, CarPlayPreflight.ACCESS_LOCAL_NETWORK),
            of(SetupItem.MICROPHONE, Manifest.permission.RECORD_AUDIO),
            of(SetupItem.NOTIFICATIONS, POST_NOTIFICATIONS),
        ).filter { it.second.isNotEmpty() }
        return groups + listOf(SetupItem.OVERLAY to emptyList(), SetupItem.VPN to emptyList(), SetupItem.BATTERY to emptyList())
    }

    /**
     * What "Allow all" asks next: every missing runtime item together (one dialog), else the next
     * special grant; never an item already asked in this run, so a refusal ends the walk.
     */
    fun nextToAllow(permissions: List<SetupPermission>, asked: Set<SetupItem>): List<SetupPermission> {
        val missing = permissions.filter { !it.granted && it.item !in asked }
        return missing.filter { it.item.grant == GrantKind.RUNTIME }.ifEmpty { missing.take(1) }
    }

    /** The runtime permissions the setup asks for in one dialog. */
    fun runtimePermissions(sdk: Int = Build.VERSION.SDK_INT): List<String> =
        (DevicePreflight.requiredPermissions() + CarPlayPreflight.requiredPermissions(Transport.WIRELESS, WirelessPath.WIFI_DIRECT, sdk))
            .distinct()

    private const val NEARBY_WIFI = "android.permission.NEARBY_WIFI_DEVICES"
    private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
}

/** Everything the setup renders, so previews and the screenshot gallery need no ViewModel. */
data class SetupUi(
    val step: SetupStep,
    val permissions: List<SetupPermission>,
    /** The driver's car brand; null until they choose. */
    val carBrand: CarBrand?,
    /** BYD on a BYD DiLink unit, else null. */
    val detectedBrand: CarBrand?,
    val pairing: PairingSession.State,
    /** This screen's Bluetooth name, as phones list it; null while Bluetooth is off or unreadable. */
    val carName: String?,
    /** This screen answers Bluetooth discovery right now. */
    val visible: Boolean,
    /** The session (first connection) as Home shows it. */
    val link: LinkState,
    /** The phone found (or chosen) in this setup, with its kind once known. */
    val phone: PairedPhone?,
    /** Phones paired before, offered on the pairing step. */
    val knownPhones: List<PairedPhone> = emptyList(),
    /** "Plug in with a cable instead" was chosen. */
    val cable: Boolean = false,
    /** This stereo's Bluetooth is an external module apps cannot use: wireless cannot start here yet. */
    val wirelessUnavailable: Boolean = false,
) {
    /** Required and recommended items not allowed yet; while any is, "Allow all" is the lit action. */
    val missingNeeded: Int get() = permissions.count { !it.granted && it.item.need != Need.OPTIONAL }
    val missing: Int get() = permissions.count { !it.granted }
}

/** What the setup can do; the screen implements it, previews fake it. */
interface SetupActions {
    fun next() {}
    fun back() {}
    /** Leaves the setup (finished or skipped); it does not run at launch again. */
    fun exit() {}
    fun allowAll() {}
    fun allow(item: SetupItem) {}
    fun setCarBrand(brand: CarBrand) {}
    fun makeVisible() {}
    fun turnOnBluetooth() {}
    fun setKind(kind: com.carlink.core.core.PhoneKind) {}
    fun connectKnown(phone: PairedPhone) {}
    fun useCable(cable: Boolean) {}
    fun retry() {}
    /** The one-tap fix for the first connection's problem, as Home offers it (home/rememberProblemFix). */
    fun fix(problem: com.carlink.core.core.LinkProblem) {}
}
