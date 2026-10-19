package com.carlink.core.connection.aa

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.HostIntegration
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.UsbAccessoryMode
import com.andrerinas.openheadunit.connection.UsbDeviceCompat
import com.andrerinas.openheadunit.utils.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A Wi-Fi Direct group as read back from the framework; [known] is false when it could not be read. */
internal data class P2pGroupReading(val known: Boolean, val name: String? = null, val isOwner: Boolean = false) {
    companion object {
        val NONE = P2pGroupReading(known = true)
        val UNKNOWN = P2pGroupReading(known = false)
    }
}

/** Everything [AaCommandHandler] needs from DiAuto and the platform, so its logic is testable. ":aa" only. */
internal interface AaEngineAccess {
    fun isServiceRunning(): Boolean
    /** CommManager.isBusy: Connecting through TransportStarted (DA§2.1). */
    fun isBusy(): Boolean
    /** Starts AapService with [action] (null = plain start); false when Android refused it. */
    fun startService(action: String?, mac: String? = null): Boolean
    /**
     * True when a screen of the app (any process) is in front, so a foreground service started now
     * gets while-in-use; always true below Android 14, where nothing needs it.
     */
    fun inFront(): Boolean
    /** Brings CarLink's Home to the front (it may draw over other apps, so it may start activities). */
    fun bringToFront()
    fun wirelessConfig(): WirelessConfig
    /** Replaces DiAuto's wake-poke targets (`auto-start-bt-macs`) with exactly [mac], device-protected copy included. */
    fun setPokeTargets(mac: String)
    /** The kept Wi-Fi Direct name (PR #1006 port), or null before the first group. */
    fun keptGroupName(): String?
    /** The group name DiAuto last recorded as its own (`p2p_ownership`). */
    fun recordedGroupName(): String?
    fun currentGroup(): P2pGroupReading
    fun removeGroup()
    fun resetStaticBssid()
    fun setClaimHiddenBottomStrip(claim: Boolean)
    fun isAccessory(device: UsbDevice): Boolean
    fun hasUsbPermission(device: UsbDevice): Boolean
    fun requestUsbPermission(device: UsbDevice)
    fun switchToAccessory(device: UsbDevice): Boolean
    fun deviceName(device: UsbDevice): String
    /**
     * The same phone as it is attached right now. A phone re-enumerates (charging-mode changes,
     * the accessory switch), and an old [UsbDevice] then no longer works (S25 on the tablet).
     * Null when no phone from that maker is attached.
     */
    fun currentDevice(previous: UsbDevice): UsbDevice?
    /** The attached phone that is in accessory mode, if any. */
    fun accessoryDevice(): UsbDevice?
    /** DiAuto's `HostIntegration.usbOnlySession`: no wireless bring-up while true. */
    fun setUsbOnlySession(usbOnly: Boolean)
    fun sleep(millis: Long)
    fun now(): Long
}

/** The real [AaEngineAccess] over DiAuto's singletons in the ":aa" process. */
internal class DiAutoEngineAccess(context: Context) : AaEngineAccess {
    private val app = context.applicationContext
    private val settings get() = App.provide(app).settings

    @Suppress("DEPRECATION") // Still returns the caller's own services.
    override fun isServiceRunning(): Boolean {
        val manager = app.getSystemService(ActivityManager::class.java) ?: return false
        val name = AapService::class.java.name
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == name && it.started }
    }

    override fun isBusy(): Boolean = App.provide(app).commManager.isBusy

    override fun startService(action: String?, mac: String?): Boolean {
        val intent = Intent(app, AapService::class.java).apply {
            if (action != null) setAction(action)
            if (mac != null) putExtra(AapService.EXTRA_MAC, mac)
        }
        return runCatching { ContextCompat.startForegroundService(app, intent) }
            .onFailure { Log.w(TAG, "AapService $action refused: ${it.javaClass.simpleName}: ${it.message}") }
            .isSuccess
    }

    // Only the caller's own processes are listed; IMPORTANCE_FOREGROUND is a resumed activity.
    override fun inFront(): Boolean = Build.VERSION.SDK_INT < 34 ||
        app.getSystemService(ActivityManager::class.java)?.runningAppProcesses.orEmpty()
            .any { it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }

    override fun bringToFront() =
        com.carlink.core.connection.CarLinkCore.config.host.openHome(app, com.carlink.core.connection.CarLinkHost.OpenReason.ENGINE_START)

    override fun wirelessConfig(): WirelessConfig = settings.let {
        WirelessConfig(
            mode = it.wifiConnectionMode,
            transport = it.nativeApTransport,
            hotspotInterface = it.hotspotInterface,
            hotspotSsid = it.hotspotSsid,
            hotspotPassword = it.hotspotPassword,
            staticBssid = it.staticBSSID ?: "0",
        )
    }

    override fun setPokeTargets(mac: String) {
        val targets = setOf(mac.uppercase())
        settings.autoStartBluetoothDeviceMacs = targets
        Settings.syncAutoStartBtMacsToDeviceStorage(app, targets)
    }

    override fun keptGroupName(): String? = settings.wifiDirectGroupIdentity?.networkName

    override fun recordedGroupName(): String? =
        app.getSharedPreferences("p2p_ownership", Context.MODE_PRIVATE).getString("network_name", null)

    @SuppressLint("MissingPermission") // Nearby devices / location are pre-flight requirements.
    override fun currentGroup(): P2pGroupReading = withChannel { manager, channel ->
        var reading = P2pGroupReading.UNKNOWN
        val done = CountDownLatch(1)
        runCatching {
            manager.requestGroupInfo(channel) { group ->
                reading = if (group == null) P2pGroupReading.NONE
                    else P2pGroupReading(known = true, name = group.networkName, isOwner = group.isGroupOwner)
                done.countDown()
            }
        }.onFailure { Log.w(TAG, "requestGroupInfo failed: ${it.javaClass.simpleName}: ${it.message}"); done.countDown() }
        done.await(P2P_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        reading
    } ?: P2pGroupReading.UNKNOWN

    @SuppressLint("MissingPermission")
    override fun removeGroup() {
        withChannel { manager, channel ->
            val done = CountDownLatch(1)
            runCatching {
                manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = done.countDown()
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "removeGroup failed: $reason")
                        done.countDown()
                    }
                })
            }.onFailure { done.countDown() }
            done.await(P2P_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun <T> withChannel(block: (WifiP2pManager, WifiP2pManager.Channel) -> T): T? {
        val manager = app.getSystemService(WifiP2pManager::class.java) ?: return null
        val channel = runCatching { manager.initialize(app, Looper.getMainLooper(), null) }.getOrNull() ?: return null
        return try {
            block(manager, channel)
        } finally {
            if (Build.VERSION.SDK_INT >= 27) runCatching { channel.close() }
        }
    }

    override fun resetStaticBssid() {
        settings.staticBSSID = "0"
    }

    override fun setClaimHiddenBottomStrip(claim: Boolean) {
        HostIntegration.claimHiddenBottomStrip = claim
    }

    override fun isAccessory(device: UsbDevice) = UsbDeviceCompat.isInAccessoryMode(device)

    override fun hasUsbPermission(device: UsbDevice): Boolean =
        app.getSystemService(UsbManager::class.java)?.hasPermission(device) == true

    override fun requestUsbPermission(device: UsbDevice) {
        val usb = app.getSystemService(UsbManager::class.java) ?: return
        val reply = Intent(AaProtocol.ACTION_COMMAND).setClass(app, AaCommandReceiver::class.java)
            .putExtra(AaProtocol.EXTRA_COMMAND, AaProtocol.COMMAND_USB_PERMISSION)
            .putExtra(AaProtocol.EXTRA_USB_DEVICE, device)
        // Mutable: UsbManager adds EXTRA_PERMISSION_GRANTED. Explicit, so nothing else can fill it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        // After the accessory switch the phone is a new device. When CarLink is the default app for
        // it, Android grants access on its own as it dispatches the attach, a moment later; asking
        // at once raised the system prompt on every wired start (seen on the tablet, 2026-10-09).
        Thread({
            val deadline = android.os.SystemClock.uptimeMillis() + DEFAULT_GRANT_WAIT_MS
            while (!usb.hasPermission(device) && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
            if (usb.hasPermission(device)) {
                app.sendBroadcast(Intent(reply).putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true))
            } else {
                usb.requestPermission(device, PendingIntent.getBroadcast(app, USB_PERMISSION_REQUEST, reply, flags))
            }
        }, "carlink-aa-usb-permission").start()
    }

    override fun switchToAccessory(device: UsbDevice): Boolean {
        val usb = app.getSystemService(UsbManager::class.java) ?: return false
        return runCatching { UsbAccessoryMode(usb).connectAndSwitch(device, settings.useLibusb) }
            .onFailure { Log.w(TAG, "AOA switch threw ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
    }

    override fun deviceName(device: UsbDevice): String = UsbDeviceCompat(device).uniqueName

    override fun currentDevice(previous: UsbDevice): UsbDevice? {
        val attached = app.getSystemService(UsbManager::class.java)?.deviceList?.values.orEmpty()
        return attached.firstOrNull { it.deviceName == previous.deviceName }
            ?: attached.firstOrNull { it.vendorId == previous.vendorId && it.productId == previous.productId }
            ?: attached.firstOrNull { it.vendorId == previous.vendorId || UsbDeviceCompat.isInAccessoryMode(it) }
    }

    override fun accessoryDevice(): UsbDevice? =
        app.getSystemService(UsbManager::class.java)?.deviceList?.values.orEmpty().firstOrNull { UsbDeviceCompat.isInAccessoryMode(it) }

    override fun setUsbOnlySession(usbOnly: Boolean) {
        HostIntegration.usbOnlySession = usbOnly
    }

    override fun sleep(millis: Long) = Thread.sleep(millis)

    override fun now(): Long = android.os.SystemClock.elapsedRealtime()

    private companion object {
        const val TAG = "CarLink-AA2"
        const val P2P_TIMEOUT_MILLIS = 3_000L
        const val USB_PERMISSION_REQUEST = 0x4141
        const val DEFAULT_GRANT_WAIT_MS = 2_000L
    }
}
