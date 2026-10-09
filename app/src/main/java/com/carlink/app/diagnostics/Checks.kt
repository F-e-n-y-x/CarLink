package com.carlink.app.diagnostics

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.carlink.core.core.PhoneKind
import com.carlink.core.detect.BluetoothAvailability
import com.carlink.core.detect.PairedPhone
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File

/** How a check came out. Every status has its own icon; color is never the only signal. */
enum class CheckStatus { PASS, WARN, FAIL, INFO }

enum class CheckId {
    CARPLAY_AUTHENTICATION,
    BLUETOOTH,
    BLUETOOTH_PERMISSION,
    PAIRED_PHONES,
    AUTO_CONNECT,
    LOCATION_PERMISSION,
    NEARBY_WIFI_PERMISSION,
    MICROPHONE_PERMISSION,
    NOTIFICATIONS_PERMISSION,
    OVERLAY,
    BATTERY_OPTIMIZATION,
    USB_HOST,
    WIFI_DIRECT,
    H264_DECODER,
    HEVC_DECODER,
}

/** The single recovery action a failing check offers (DESIGN_SPEC §6.5). */
sealed interface CheckFix {
    data object OpenBluetoothSettings : CheckFix
    data object OpenAppSettings : CheckFix
    data object OpenOverlaySettings : CheckFix
    data object OpenBatterySettings : CheckFix
    data object ResumeAutoConnect : CheckFix

    /** Ask for runtime permissions; when Android no longer shows the prompt, open app settings. */
    data class RequestPermissions(val permissions: List<String>) : CheckFix
}

/** Why the CarPlay authentication check came out as it did. */
enum class CarPlayAuthSource {
    /** The files are inside this APK's assets. */
    BUNDLED,

    /** Not in this APK, but an earlier build installed them in app storage, where DiPlay loads them from. */
    INSTALLED,

    /** Neither: CarPlay with local authentication cannot start. */
    MISSING,

    /** DiPlay is set to an external authentication chip or server; the files are not used. */
    EXTERNAL,
}

/** A video decoder the engines can use, as MediaCodecList reports it. */
data class DecoderInfo(val name: String, val maxWidth: Int?, val maxHeight: Int?)

/**
 * The facts behind one check. [count], [decoder], [authSource] and [mfiTarget] carry values the
 * UI shows; the UI owns every string.
 */
data class CheckResult(
    val id: CheckId,
    val status: CheckStatus,
    val fix: CheckFix? = null,
    val count: Int? = null,
    val unknownCount: Int = 0,
    val decoder: DecoderInfo? = null,
    val authSource: CarPlayAuthSource? = null,
    val mfiTarget: MfiTarget? = null,
)

/** What the checks need from CarLink's own state (from CarLinkViewModel.home / .connection). */
data class CheckInputs(
    val bluetooth: BluetoothAvailability,
    val phones: List<PairedPhone>,
    val autoConnectPaused: Boolean,
    val autoConnectEnabled: Boolean,
)

/**
 * Readiness checks for Diagnostics (and, later, the Settings overview card). Each one reads the
 * real system state: nothing here is estimated. [evaluate] does binder and codec queries, so call
 * it off the main thread; it is cheap enough to repeat on every resume.
 */
object Checks {
    const val AUTH_DIRECTORY = "offline-mfi"
    val AUTH_FILES = listOf("identity.pk8", "certificate.p7b")

    fun evaluate(context: Context, inputs: CheckInputs): List<CheckResult> {
        val app = context.applicationContext
        val pm = app.packageManager
        val requested = requestedPermissions(app)
        val results = mutableListOf<CheckResult>()

        results += carPlayAuthentication(app)
        results += bluetooth(app, inputs)
        if (Build.VERSION.SDK_INT >= 31 && Manifest.permission.BLUETOOTH_CONNECT in requested) {
            results += permission(app, CheckId.BLUETOOTH_PERMISSION, CheckStatus.FAIL,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        }
        if (inputs.bluetooth == BluetoothAvailability.READY) results += pairedPhones(inputs.phones)
        results += autoConnect(inputs)
        if (Manifest.permission.ACCESS_FINE_LOCATION in requested) {
            results += permission(app, CheckId.LOCATION_PERMISSION, CheckStatus.WARN,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 && Manifest.permission.NEARBY_WIFI_DEVICES in requested) {
            results += permission(app, CheckId.NEARBY_WIFI_PERMISSION, CheckStatus.WARN, Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Manifest.permission.RECORD_AUDIO in requested) {
            results += permission(app, CheckId.MICROPHONE_PERMISSION, CheckStatus.WARN, Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 && Manifest.permission.POST_NOTIFICATIONS in requested) {
            results += permission(app, CheckId.NOTIFICATIONS_PERMISSION, CheckStatus.WARN, Manifest.permission.POST_NOTIFICATIONS)
        }
        results += CheckResult(CheckId.OVERLAY,
            if (Settings.canDrawOverlays(app)) CheckStatus.PASS else CheckStatus.WARN,
            fix = CheckFix.OpenOverlaySettings.takeUnless { Settings.canDrawOverlays(app) })
        val ignoring = app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) ?: true
        results += CheckResult(CheckId.BATTERY_OPTIMIZATION, if (ignoring) CheckStatus.PASS else CheckStatus.WARN,
            fix = CheckFix.OpenBatterySettings.takeUnless { ignoring })
        results += CheckResult(CheckId.USB_HOST,
            if (pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) CheckStatus.PASS else CheckStatus.WARN)
        results += CheckResult(CheckId.WIFI_DIRECT,
            if (pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) CheckStatus.PASS else CheckStatus.INFO)
        val decoders = Decoders.get()
        results += CheckResult(CheckId.H264_DECODER, if (decoders.avc != null) CheckStatus.PASS else CheckStatus.WARN,
            decoder = decoders.avc)
        results += CheckResult(CheckId.HEVC_DECODER, if (decoders.hevc != null) CheckStatus.PASS else CheckStatus.INFO,
            decoder = decoders.hevc)
        return results
    }

    /** Problems first (fail, warn), then notes, then passes; the order inside a group is kept. */
    fun sorted(results: List<CheckResult>): List<CheckResult> = results.sortedBy { it.status.rank() }

    /** Number of checks that need attention (fail or warn). */
    fun problemCount(results: List<CheckResult>): Int =
        results.count { it.status == CheckStatus.FAIL || it.status == CheckStatus.WARN }

    private fun CheckStatus.rank() = when (this) {
        CheckStatus.FAIL -> 0
        CheckStatus.WARN -> 1
        CheckStatus.INFO -> 2
        CheckStatus.PASS -> 3
    }

    /**
     * DiPlay's local authentication (DiPlayBootstrap) copies `offline-mfi/identity.pk8` and
     * `offline-mfi/certificate.p7b` from the APK assets into app storage once, then loads that
     * copy. So CarPlay can authenticate when this APK bundles the files or an earlier build
     * already installed them; it is not needed when DiPlay uses an external chip or server.
     */
    fun carPlayAuthentication(context: Context, bundled: Boolean = isBundled(context)): CheckResult {
        val target = runCatching { AirPlayPersistence.loadMfiTarget(context) }.getOrDefault(MfiTarget.LOCAL)
        val installedDir = File(context.noBackupFilesDir, AUTH_DIRECTORY)
        val installed = AUTH_FILES.all { File(installedDir, it).isFile }
        val source = when {
            target != MfiTarget.LOCAL -> CarPlayAuthSource.EXTERNAL
            bundled -> CarPlayAuthSource.BUNDLED
            installed -> CarPlayAuthSource.INSTALLED
            else -> CarPlayAuthSource.MISSING
        }
        val status = when (source) {
            CarPlayAuthSource.BUNDLED, CarPlayAuthSource.INSTALLED -> CheckStatus.PASS
            CarPlayAuthSource.EXTERNAL -> CheckStatus.INFO
            CarPlayAuthSource.MISSING -> CheckStatus.FAIL
        }
        return CheckResult(CheckId.CARPLAY_AUTHENTICATION, status, authSource = source, mfiTarget = target)
    }

    /** Whether this APK's assets carry both authentication files (local car-test builds do). */
    fun isBundled(context: Context): Boolean =
        runCatching { context.assets.list(AUTH_DIRECTORY)?.toSet().orEmpty() }
            .getOrDefault(emptySet()).containsAll(AUTH_FILES)

    private fun bluetooth(context: Context, inputs: CheckInputs): CheckResult {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val status = when {
            adapter == null || inputs.bluetooth == BluetoothAvailability.UNSUPPORTED -> CheckStatus.INFO
            // Without the permission the adapter state is still readable; isEnabled needs none.
            runCatching { adapter.isEnabled }.getOrDefault(false) -> CheckStatus.PASS
            else -> CheckStatus.FAIL
        }
        return CheckResult(CheckId.BLUETOOTH, status,
            fix = CheckFix.OpenBluetoothSettings.takeIf { status == CheckStatus.FAIL })
    }

    /** [CheckResult.count] is the number of paired phones; [CheckResult.unknownCount] those not yet identified. */
    private fun pairedPhones(phones: List<PairedPhone>): CheckResult =
        if (phones.isEmpty()) {
            CheckResult(CheckId.PAIRED_PHONES, CheckStatus.WARN, CheckFix.OpenBluetoothSettings, count = 0)
        } else {
            CheckResult(CheckId.PAIRED_PHONES, CheckStatus.PASS, count = phones.size,
                unknownCount = phones.count { it.kind == PhoneKind.UNKNOWN })
        }

    private fun autoConnect(inputs: CheckInputs): CheckResult = when {
        inputs.autoConnectPaused -> CheckResult(CheckId.AUTO_CONNECT, CheckStatus.WARN, CheckFix.ResumeAutoConnect)
        !inputs.autoConnectEnabled -> CheckResult(CheckId.AUTO_CONNECT, CheckStatus.INFO)
        else -> CheckResult(CheckId.AUTO_CONNECT, CheckStatus.PASS)
    }

    /**
     * Granted when the first permission is granted (the others are asked for alongside it).
     * [failStatus] is FAIL when CarLink cannot work without it, WARN when only a feature is lost.
     */
    private fun permission(context: Context, id: CheckId, failStatus: CheckStatus, vararg permissions: String): CheckResult {
        val granted = context.checkSelfPermission(permissions.first()) == PackageManager.PERMISSION_GRANTED
        return if (granted) CheckResult(id, CheckStatus.PASS)
        else CheckResult(id, failStatus, CheckFix.RequestPermissions(permissions.filter { declared(context, it) }))
    }

    private fun declared(context: Context, permission: String) = permission in requestedPermissions(context)

    private fun requestedPermissions(context: Context): Set<String> = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toSet()
    }.getOrNull().orEmpty()
}

/** Hardware video decoders, read once per process (the list does not change at runtime). */
internal object Decoders {
    data class Found(val avc: DecoderInfo?, val hevc: DecoderInfo?)

    @Volatile private var cached: Found? = null

    fun get(): Found = cached ?: synchronized(this) {
        cached ?: Found(find("video/avc"), find("video/hevc")).also { cached = it }
    }

    private fun find(mime: String): DecoderInfo? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }
            .filter(::isHardware)
            .map { info ->
                val video = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
                DecoderInfo(info.name, video?.supportedWidths?.upper, video?.supportedHeights?.upper)
            }
            .maxByOrNull { (it.maxWidth ?: 0).toLong() * (it.maxHeight ?: 0) }
    }.getOrNull()

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly
        else !isSoftwareCodecName(info.name)

    /** Android's own software codecs, for API < 29 where MediaCodecInfo cannot say. */
    fun isSoftwareCodecName(name: String): Boolean {
        val n = name.lowercase(java.util.Locale.ROOT)
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("omx.ffmpeg.") ||
            n.contains(".sw.") || n.endsWith(".sw")
    }
}
