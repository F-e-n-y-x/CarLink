package com.carlink.core.detect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.carlink.core.entry.UsbRouterActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * When each USB device (by its node, `/dev/bus/usb/002/004`) attached, as this process heard it.
 *
 * Android grants the default app's access while it dispatches the attach: about 0.1 s after it on
 * the Galaxy tablet (2026-11-03). The engines wait up to [DEFAULT_GRANT_WAIT_MS] for that grant
 * before raising the permission dialog; counted from the attach instead of from when an engine
 * first looks, a phone that has been on the bus for a while is asked at once (cold CarPlay start:
 * found 1.3 s after its attach, then waited 2 s more; Android Auto: waited 2 s for a phone that had
 * attached 3 s earlier). A device whose attach was not heard (plugged in before the process ran)
 * gets the full wait, as before.
 */
object UsbAttachTimes {
    /** How long a just-attached device may take to get Android's own grant (CarLink measured ~0.5 s, 2026-10-09). */
    const val DEFAULT_GRANT_WAIT_MS = 2_000L
    private const val POLL_MS = 100L

    private val attachedAt = ConcurrentHashMap<String, Long>()
    private val watching = AtomicBoolean(false)

    fun attached(deviceName: String, at: Long = SystemClock.elapsedRealtime()) {
        attachedAt[deviceName] = at
    }

    fun detached(deviceName: String) {
        attachedAt.remove(deviceName)
    }

    fun attachedAt(deviceName: String): Long? = attachedAt[deviceName]

    /** Milliseconds since [deviceName] attached; null when this process did not hear it. */
    fun ageMillis(deviceName: String, now: Long = SystemClock.elapsedRealtime()): Long? =
        attachedAt[deviceName]?.let { (now - it).coerceAtLeast(0) }

    /** Listens for attaches and detaches in this process (once). Main process and ":aa" each call it. */
    fun watch(context: Context) {
        if (!watching.compareAndSet(false, true)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = UsbRouterActivity.usbDevice(intent) ?: return
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> attached(device.deviceName)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> detached(device.deviceName)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // Exported like UsbWatcher's: these are system broadcasts, which nothing else may send.
        ContextCompat.registerReceiver(context.applicationContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    /**
     * Waits for Android's own grant on [device] and returns the device to use: granted, or the one
     * to ask for. Follows the phone when it re-enumerates meanwhile (a Samsung phone changes node
     * three times in its first 2 s; the dialog went to a node removed 2 s earlier and its "Allow"
     * opened nothing, 2026-11-03), and waits for each node only until it is [max] old. Never longer
     * than 2 × [max] in all, for a phone that keeps re-enumerating.
     */
    fun <D> awaitDefaultGrant(
        device: D,
        current: (D) -> D?,
        nameOf: (D) -> String,
        granted: (D) -> Boolean,
        attachedAt: (String) -> Long?,
        now: () -> Long,
        sleep: (Long) -> Unit,
        max: Long = DEFAULT_GRANT_WAIT_MS,
    ): D {
        val started = now()
        var target = current(device) ?: device
        while (!granted(target)) {
            val t = now()
            val deadline = minOf((attachedAt(nameOf(target)) ?: started) + max, started + 2 * max)
            if (t >= deadline) break
            sleep(minOf(POLL_MS, deadline - t))
            target = current(target) ?: target
        }
        return target
    }
}
