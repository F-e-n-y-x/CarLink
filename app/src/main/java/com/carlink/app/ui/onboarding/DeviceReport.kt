package com.carlink.app.ui.onboarding

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

internal enum class BluetoothCheck { ON, OFF, UNSUPPORTED }

/** What the "Your device" step reports and checks (DESIGN_SPEC §6.4 step 2). */
@Immutable
internal data class DeviceReport(
    val androidRelease: String,
    val apiLevel: Int,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val bluetooth: BluetoothCheck,
    val usbHost: Boolean,
    val wifiDirect: Boolean,
) {
    val allGood: Boolean get() = bluetooth == BluetoothCheck.ON && usbHost && wifiDirect

    companion object {
        /** Sample for previews. */
        val Sample = DeviceReport("14", 34, 1920, 1080, BluetoothCheck.ON, usbHost = true, wifiDirect = false)

        @Suppress("DEPRECATION") // getRealMetrics is the API-25-compatible way to read the panel size.
        fun read(context: Context): DeviceReport {
            val pm = context.packageManager
            val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
            val bluetooth = when {
                adapter == null || !pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) -> BluetoothCheck.UNSUPPORTED
                // isEnabled needs no runtime permission.
                runCatching { adapter.isEnabled }.getOrDefault(false) -> BluetoothCheck.ON
                else -> BluetoothCheck.OFF
            }
            val metrics = DisplayMetrics()
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) display.getRealMetrics(metrics) else metrics.setTo(context.resources.displayMetrics)
            return DeviceReport(
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                apiLevel = Build.VERSION.SDK_INT,
                screenWidthPx = maxOf(metrics.widthPixels, metrics.heightPixels),
                screenHeightPx = minOf(metrics.widthPixels, metrics.heightPixels),
                bluetooth = bluetooth,
                usbHost = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
                wifiDirect = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
            )
        }
    }
}

/** The report, re-run on resume and whenever Bluetooth is switched on or off. */
@Composable
internal fun rememberDeviceReport(): State<DeviceReport> {
    val context = LocalContext.current
    val report = remember(context) { mutableStateOf(DeviceReport.read(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { report.value = DeviceReport.read(context) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                report.value = DeviceReport.read(context)
            }
        }
        // A protected system broadcast. Before API 33 ContextCompat's NOT_EXPORTED flag would require a
        // sender permission the Bluetooth process does not hold, so register as PhonesRepository does.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return report
}
