package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection

// CarLink: new file. Bounded in-session recovery from transient USB request failures.

/**
 * Counts consecutive transient USB failures (a reap that returned no request, a refused queue).
 * A retry is allowed while fewer than [maxConsecutive] failures happened in a row and the first
 * of them is less than [windowMillis] old; any success resets it. A detached device therefore
 * still fails within [windowMillis] plus one liveness probe.
 */
internal class UsbTransientFailureBudget(
    private val maxConsecutive: Int = MAX_CONSECUTIVE,
    private val windowMillis: Long = WINDOW_MILLIS,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var count = 0
    private var firstNanos = 0L

    val consecutive: Int get() = count

    /** Records one failure; returns true when another attempt is still within the budget. */
    fun recordFailure(): Boolean {
        val now = nanoTime()
        if (count == 0) firstNanos = now
        count += 1
        return count <= maxConsecutive && now - firstNanos < windowMillis * 1_000_000L
    }

    fun reset() {
        count = 0
    }

    companion object {
        const val MAX_CONSECUTIVE = 4
        const val WINDOW_MILLIS = 2_000L
    }
}

/**
 * True when the device answers a standard GET_STATUS within [timeoutMillis]. On a detached or
 * reset device usbfs fails at once (ENODEV), so this separates a transient libusbhost hiccup
 * (EINTR/EAGAIN from poll or REAPURBNDELAY, both reported as a null request) from a dead link.
 */
internal fun usbDeviceResponds(connection: UsbDeviceConnection, timeoutMillis: Int = 250): Boolean = try {
    val status = ByteArray(2)
    connection.controlTransfer(
        UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
        USB_REQUEST_GET_STATUS,
        0,
        0,
        status,
        status.size,
        timeoutMillis,
    ) == status.size
} catch (_: RuntimeException) {
    false
}

private const val USB_REQUEST_GET_STATUS = 0x00
