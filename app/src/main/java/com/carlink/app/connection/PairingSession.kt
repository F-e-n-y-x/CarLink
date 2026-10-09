package com.carlink.app.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.carlink.app.core.PhoneKind
import com.carlink.app.detect.KnownPhones
import com.carlink.app.detect.PhoneClassifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * First-time pairing, the way every shipping receiver does it (design/RESEARCH_PLUG_AND_PLAY.md):
 * this screen becomes discoverable under its Bluetooth name, the driver pairs *from the phone*
 * (tablets cannot start the car-kit role themselves, DA§11.3), and CarLink then tells an iPhone
 * from an Android phone by its service records.
 *
 * Main process; [start] while the pairing screen is shown, [close] when it leaves.
 * Discoverability itself is requested by the screen with [discoverableIntent] (a system dialog).
 */
class PairingSession(context: Context) {
    sealed interface State {
        /** Waiting for a phone to pair; [carName] is what the driver looks for on the phone. */
        data class Waiting(val carName: String?) : State
        /** A phone is pairing (the phone or this screen shows a code to confirm). */
        data class Pairing(val phoneName: String?) : State
        /** Paired; [kind] may be UNKNOWN until the phone's services are read. */
        data class Paired(val address: String, val phoneName: String?, val kind: PhoneKind) : State
        data object BluetoothOff : State
    }

    private val app = context.applicationContext
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val knownPhones = KnownPhones(app)
    private val _state = MutableStateFlow<State>(State.Waiting(null))
    val state: StateFlow<State> = _state.asStateFlow()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> refresh()
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = device(intent) ?: return
                    val name = runCatching { device.name }.getOrNull()
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                        BluetoothDevice.BOND_BONDING -> _state.value = State.Pairing(name)
                        BluetoothDevice.BOND_BONDED -> {
                            classify(device, device.uuids?.map { it.uuid }?.toSet().orEmpty())
                            // The phone's service records decide iPhone vs Android; ask for fresh ones.
                            runCatching { device.fetchUuidsWithSdp() }
                        }
                        BluetoothDevice.BOND_NONE -> if (_state.value is State.Pairing) refresh()
                    }
                }
                BluetoothDevice.ACTION_UUID -> {
                    val device = device(intent) ?: return
                    val current = _state.value as? State.Paired ?: return
                    if (!current.address.equals(device.address, ignoreCase = true)) return
                    classify(device, uuids(intent))
                }
            }
        }
    }

    fun start() {
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_UUID)
            }
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
        refresh()
    }

    fun close() {
        if (registered) runCatching { app.unregisterReceiver(receiver) }
        registered = false
    }

    @SuppressLint("MissingPermission") // The pairing screen runs after the permission step.
    private fun refresh() {
        _state.value = if (adapter?.isEnabled != true) State.BluetoothOff
        else State.Waiting(runCatching { adapter.name }.getOrNull())
    }

    @SuppressLint("MissingPermission")
    private fun classify(device: BluetoothDevice, uuids: Set<java.util.UUID>) {
        val name = runCatching { device.name }.getOrNull()
        val kind = PhoneClassifier.classifyBluetooth(
            PhoneClassifier.BluetoothDeviceInfo(device.address, name, uuids,
                runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()),
            remembered = null,
        )
        if (kind != PhoneKind.UNKNOWN) knownPhones.remember(device.address, kind)
        _state.value = State.Paired(device.address, name, kind)
    }

    companion object {
        /** Makes this screen visible to phones for five minutes (system dialog). */
        fun discoverableIntent(): Intent =
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)

        private fun device(intent: Intent): BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        private fun uuids(intent: Intent): Set<java.util.UUID> {
            val values = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)?.toList()
            } else {
                @Suppress("DEPRECATION") intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)?.filterIsInstance<ParcelUuid>()
            }
            return values.orEmpty().map { it.uuid }.toSet()
        }
    }
}
