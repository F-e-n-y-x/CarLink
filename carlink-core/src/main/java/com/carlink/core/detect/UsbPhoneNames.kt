package com.carlink.core.detect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.usb.UsbDevice
import com.carlink.core.connection.DeviceEnvironment
import com.carlink.core.core.PhoneKind
import java.util.Locale

/**
 * The name to show for a phone plugged in over USB. USB only reports generic names
 * ("iPhone", "SAMSUNG_Android"), so the driver's own paired phone of that kind is used when
 * there is one: the one used last, else the only one ("Switch to Ayush's S25 Ultra?").
 */
object UsbPhoneNames {
    @SuppressLint("MissingPermission") // Bonded devices are read only when the permission allows; else the fallback.
    fun resolve(context: Context, kind: PhoneKind, device: UsbDevice): String {
        val app = context.applicationContext
        val known = KnownPhones(app)
        val paired = runCatching {
            app.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .filter { known.kindOf(it.address) == kind }
                .mapNotNull { d -> d.name?.let { d.address to it } }
        }.getOrDefault(emptyList())
        val last = DeviceEnvironment.lastPhoneAddress(app)
        paired.firstOrNull { it.first.equals(last, ignoreCase = true) }?.let { return it.second }
        if (paired.size == 1) return paired.single().second
        return fallback(kind, runCatching { device.manufacturerName }.getOrNull())
    }

    internal fun fallback(kind: PhoneKind, manufacturer: String?): String = when (kind) {
        PhoneKind.IPHONE -> "iPhone"
        else -> manufacturer?.takeIf { it.isNotBlank() }
            ?.lowercase(Locale.ROOT)?.replaceFirstChar { it.titlecase(Locale.ROOT) }?.let { "$it phone" }
            ?: "Android phone"
    }
}
