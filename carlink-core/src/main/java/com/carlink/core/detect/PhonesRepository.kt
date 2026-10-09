package com.carlink.app.detect

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
import com.carlink.app.core.PhoneKind
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
 */
class PhonesRepository(context: Context) {
    private val app = context.applicationContext
    private val knownPhones = KnownPhones(app)
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
        proxies.forEach { (profile, proxy) -> runCatching { adapter?.closeProfileProxy(profile, proxy) } }
        proxies.clear()
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
        if (_availability.value != BluetoothAvailability.READY) {
            _phones.value = emptyList()
            return
        }
        val bonded = runCatching { adapter!!.bondedDevices }.getOrNull().orEmpty()
        _phones.value = bonded
            .filter { device -> isPhone(device) }
            .map { device ->
                val address = KnownPhones.normalize(device.address)
                val info = PhoneClassifier.BluetoothDeviceInfo(
                    address = address,
                    name = runCatching { device.name }.getOrNull(),
                    uuids = runCatching { device.uuids?.map { it.uuid }?.toSet() }.getOrNull().orEmpty(),
                    majorClass = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull(),
                )
                PairedPhone(
                    address = address,
                    name = info.name?.takeIf { it.isNotBlank() } ?: address,
                    kind = PhoneClassifier.classifyBluetooth(info, knownPhones.kindOf(address)),
                    connected = address in connected,
                )
            }
            .sortedWith(compareByDescending<PairedPhone> { it.connected }.thenBy { it.name.lowercase() })
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
