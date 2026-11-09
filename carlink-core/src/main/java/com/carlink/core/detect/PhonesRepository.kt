package com.carlink.core.detect

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.carlink.core.core.PhoneKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A phone paired with the car, as Home lists it (DESIGN_SPEC §4.3). */
data class PairedPhone(
    val address: String,
    val name: String,
    val kind: PhoneKind,
    /** Its Bluetooth is connected to the car right now (hands-free or media). */
    val connected: Boolean,
)

/** Whether CarLink can list phones at all. */
enum class BluetoothAvailability { READY, OFF, NO_PERMISSION, UNSUPPORTED }

/**
 * Bonded phones with their kind and presence, refreshed on Bluetooth broadcasts while
 * [start]ed. Main process only.
 *
 * On a stereo whose Bluetooth is an external module ([viaModule]), phones pair with the module,
 * not with Android, so the list also holds every phone CarLink knows ([KnownPhones]) and is
 * available with Android's Bluetooth off.
 */
class PhonesRepository(
    context: Context,
    private val viaModule: () -> Boolean = { com.carlink.core.connection.DeviceEnvironment.moduleCarriesBluetooth(context) },
) {
    private val app = context.applicationContext
    private val knownPhones = KnownPhones(app)
    private val paths = com.carlink.core.connection.PathMemory(app)
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter

    private val _phones = MutableStateFlow<List<PairedPhone>>(emptyList())
    val phones: StateFlow<List<PairedPhone>> = _phones.asStateFlow()

    private val _availability = MutableStateFlow(BluetoothAvailability.READY)
    val availability: StateFlow<BluetoothAvailability> = _availability.asStateFlow()

    private val connected = mutableSetOf<String>()
    private val proxies = mutableMapOf<Int, BluetoothProfile>()
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> address(intent)?.let { connected += KnownPhones.normalize(it) }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> address(intent)?.let { connected -= KnownPhones.normalize(it) }
                BluetoothAdapter.ACTION_STATE_CHANGED -> if (adapter?.isEnabled == true) queryConnected() else connected.clear()
            }
            refresh()
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            proxies[profile] = proxy
            queryConnected()
            refresh()
        }

        override fun onServiceDisconnected(profile: Int) {
            proxies.remove(profile)
        }
    }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        if (hasPermission()) {
            runCatching {
                adapter?.getProfileProxy(app, profileListener, BluetoothProfile.HEADSET)
                adapter?.getProfileProxy(app, profileListener, BluetoothProfile.A2DP)
            }
        }
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { app.unregisterReceiver(receiver) }
        // closeProfileProxy can call onServiceDisconnected straight away, which removes from
        // proxies; closing a copy keeps that from throwing ConcurrentModificationException.
        val open = proxies.toMap()
        proxies.clear()
        open.forEach { (profile, proxy) -> runCatching { adapter?.closeProfileProxy(profile, proxy) } }
    }

    /**
     * The driver forgot this phone: CarLink drops its type, name and connection history and asks
     * Android to unpair it (hidden `removeBond`, allowed on most head units). Returns whether
     * Android's pairing is gone (true when there was none).
     */
    @SuppressLint("MissingPermission")
    fun forget(address: String): Boolean {
        knownPhones.forget(address)
        paths.forget(address)
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        val bonded = hasPermission() && device != null &&
            runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        val unpaired = !bonded || runCatching {
            BluetoothDevice::class.java.getMethod("removeBond").invoke(device) as? Boolean == true
        }.getOrDefault(false)
        refresh()
        return unpaired
    }

    /** The driver corrected the detected type (Phone detail). */
    fun setKind(address: String, kind: PhoneKind) {
        knownPhones.remember(address, kind)
        refresh()
    }

    @SuppressLint("MissingPermission") // hasPermission() checked first.
    fun refresh() {
        _availability.value = when {
            adapter == null -> BluetoothAvailability.UNSUPPORTED
            !hasPermission() -> BluetoothAvailability.NO_PERMISSION
            !adapter.isEnabled -> BluetoothAvailability.OFF
            else -> BluetoothAvailability.READY
        }
        val module = runCatching(viaModule).getOrDefault(false)
        val androidReady = _availability.value == BluetoothAvailability.READY
        if (module) _availability.value = BluetoothAvailability.READY
        if (!androidReady && !module) {
            _phones.value = emptyList()
            return
        }
        val bonded = if (androidReady) runCatching { adapter!!.bondedDevices }.getOrNull().orEmpty() else emptySet()
        val listed = bonded
            .filter { device -> isPhone(device) }
            .map { device ->
                val address = KnownPhones.normalize(device.address)
                val info = PhoneClassifier.BluetoothDeviceInfo(
                    address = address,
                    name = runCatching { device.name }.getOrNull(),
                    uuids = runCatching { device.uuids?.map { it.uuid }?.toSet() }.getOrNull().orEmpty(),
                    majorClass = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull(),
                )
                knownPhones.rememberName(address, info.name)
                PairedPhone(
                    address = address,
                    name = info.name?.takeIf { it.isNotBlank() } ?: knownPhones.nameOf(address) ?: address,
                    kind = PhoneClassifier.classifyBluetooth(info, knownPhones.kindOf(address)),
                    connected = address in connected,
                )
            }
        _phones.value = distinguishSameNames(if (module) listed + modulePhones(listed.map { it.address }.toSet()) else listed)
            .sortedWith(compareByDescending<PairedPhone> { it.connected }.thenBy { it.name.lowercase() })
    }

    /**
     * Phones CarLink knows that Android's paired list lacks (they paired with the stereo's module):
     * only those that connected wirelessly here before, or the last phone. Every phone ever seen
     * listed stale entries with the same name ("iPhone" twice, one that could not connect).
     */
    private fun modulePhones(listed: Set<String>): List<PairedPhone> = knownPhones.all()
        .filterKeys { it !in listed }
        .filterValues { it != PhoneKind.UNKNOWN }
        .filter { (address, kind) ->
            address.equals(com.carlink.core.connection.DeviceEnvironment.lastPhoneAddress(app), ignoreCase = true) ||
                kind.engine?.let { paths.lastWorking(it, address) } != null
        }
        .map { (address, kind) ->
            PairedPhone(address, knownPhones.nameOf(address) ?: if (kind == PhoneKind.IPHONE) "iPhone" else "Android phone",
                kind, connected = false)
        }

    companion object {
        /** Phones sharing a name ("iPhone") get the end of their address, so the driver can tell them apart. */
        fun distinguishSameNames(phones: List<PairedPhone>): List<PairedPhone> {
            val counts = phones.groupingBy { it.name.trim().lowercase() }.eachCount()
            return phones.map { phone ->
                if ((counts[phone.name.trim().lowercase()] ?: 0) < 2) phone
                else phone.copy(name = "${phone.name} · …${phone.address.takeLast(5)}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun queryConnected() {
        if (!hasPermission()) return
        proxies.values.forEach { proxy ->
            runCatching { proxy.connectedDevices }.getOrNull()?.forEach { connected += KnownPhones.normalize(it.address) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun isPhone(device: BluetoothDevice): Boolean {
        // A Bluetooth Low Energy-only device (a car's "CAR-BT BLE" key, a tag) has no hands-free or
        // RFCOMM: it was listed and woken as the Android phone (issue #18).
        if (runCatching { device.type }.getOrNull() == BluetoothDevice.DEVICE_TYPE_LE) return false
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
        return major == null || major == BluetoothClass.Device.Major.PHONE || major == BluetoothClass.Device.Major.UNCATEGORIZED
    }

    private fun hasPermission() = Build.VERSION.SDK_INT < 31 ||
        app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun address(intent: Intent): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)?.address
        else @Suppress("DEPRECATION") intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.address
    }.getOrNull()
}
