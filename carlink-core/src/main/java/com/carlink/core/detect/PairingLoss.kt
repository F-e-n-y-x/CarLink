package com.carlink.core.detect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.carlink.core.connection.Connection
import com.carlink.core.core.PhoneKind
import com.carlink.core.diagnostics.EventLog

/**
 * Known phones whose Bluetooth pairing this screen lost. Android 16 (API 36) broadcasts
 * `BluetoothDevice.ACTION_KEY_MISSING` when a bonded device can no longer be authenticated: it keeps
 * the bond, drops the link and asks the driver to pair again. Android 17 first re-pairs on its own
 * and broadcasts it only when that fails. Until the phone pairs again, wireless sessions for it are
 * blocked with [com.carlink.core.connection.Problem.PAIRING_LOST] and Home says what to do.
 * Main process only.
 */
object PairingLoss {
    private const val PREFS = "carlink_pairing_lost"

    /** Whether a key-missing broadcast for a phone of [kind] (null: unknown to CarLink) is acted on. */
    fun applies(sdk: Int, kind: PhoneKind?): Boolean =
        sdk >= Build.VERSION_CODES.BAKLAVA && kind != null && kind != PhoneKind.UNKNOWN

    fun has(context: Context, address: String): Boolean = prefs(context).getBoolean(KnownPhones.normalize(address), false)

    /** The phone paired again, was forgotten, or connected: the record goes. */
    fun clear(context: Context, address: String) {
        val key = KnownPhones.normalize(address)
        if (prefs(context).contains(key)) prefs(context).edit().remove(key).apply()
    }

    /** Listens for the broadcasts for as long as the process lives (call once, from [Connection]). */
    fun watch(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        val app = context.applicationContext
        val filter = IntentFilter(BluetoothDevice.ACTION_KEY_MISSING).apply { addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED) }
        ContextCompat.registerReceiver(app, Receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private object Receiver : BroadcastReceiver() {
        @SuppressLint("MissingPermission") // Both broadcasts are sent only to holders of BLUETOOTH_CONNECT.
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val address = device.address
            when (intent.action) {
                BluetoothDevice.ACTION_KEY_MISSING -> {
                    val kind = KnownPhones(context).kindOf(address)
                    if (!applies(Build.VERSION.SDK_INT, kind)) return
                    EventLog.add("CarLink", "Bluetooth pairing lost for a known $kind phone (key missing)")
                    prefs(context).edit().putBoolean(KnownPhones.normalize(address), true).apply()
                    Connection.get(context).onPairingLost(address, kind!!, runCatching { device.name }.getOrNull())
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_NONE) clear(context, address)
                }
            }
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
