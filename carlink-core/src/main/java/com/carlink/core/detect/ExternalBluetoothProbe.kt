package com.carlink.core.detect

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.andrerinas.openheadunit.aap.ExternalBtPolicy
import com.carlink.core.core.PhoneKind
import com.carlink.core.diagnostics.EventLog
import java.io.File
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * What [ExternalBluetoothProbe] concluded about this head unit's Bluetooth
 * (design/EXTERNAL_BLUETOOTH.md says which future port each one points to).
 */
enum class StereoBluetooth {
    /** No sign of an external module: Android's Bluetooth is the one the phone pairs with. */
    NONE,

    /** A ZLink-type module daemon (`gocsdk_zj`) answers on 127.0.0.1:3152 (upstream's ZBT route). */
    ZLINK_DAEMON,

    /** FYT's DUDUAUTO module: `sys.fyt.bluetooth_type` > 0 or `/dev/auto_serial` (upstream's BLINK route). */
    FYT_MODULE,

    /** ZLink-type markers (the ones DiAuto's ExternalBtPolicy uses) but no daemon answers. */
    EXTERNAL_UNKNOWN,

    /**
     * Markers are present, but a known phone is bonded to Android's own adapter and that adapter
     * has a real address: Android's Bluetooth probably works here after all.
     */
    LIKELY_FALSE_POSITIVE,
    ;

    /** CarLink does not start wireless sessions here (unless the driver overrides it). */
    val blocksWireless: Boolean get() = this == ZLINK_DAEMON || this == FYT_MODULE || this == EXTERNAL_UNKNOWN

    /** Any sign of an external module was found, even a doubtful one. */
    val hasSignals: Boolean get() = this != NONE
}

/** One piece of evidence, so a screen can name it in plain words. */
enum class StereoBluetoothSignal {
    /** Something accepts connections on the ZLink module daemon's port. */
    MODULE_SERVICE_ANSWERS,
    /** `sys.fyt.bluetooth_type` names an external FYT module. */
    FYT_SETTING,
    /** `/dev/auto_serial`, the FYT module's serial port. */
    FYT_SERIAL_PORT,
    /** `/dev/rf_serial` or `/dev/zj_bt_serial`, a ZLink module's serial port. */
    MODULE_SERIAL_PORT,
    /** `rw.zlink.bt.type` or `rw.zj.bt.type` is `extra`. */
    MODULE_SETTING,
}

/** Whether a TCP port accepted a connection. */
enum class PortState { OPEN, CLOSED, TIMEOUT, ERROR, NOT_CHECKED }

/** An installed app that matters for the module routes: its name and version only. */
data class InstalledApp(val packageName: String, val versionName: String?, val versionCode: Long)

/** Android's own Bluetooth adapter (never its address, only whether the address is a real one). */
data class AdapterFacts(
    val present: Boolean,
    val enabled: Boolean?,
    /** Null when unknown; false for Android's privacy placeholder 02:00:00:00:00:00. */
    val addressReal: Boolean?,
)

/** A phone CarLink knows, and whether Android's adapter lists it as bonded (null: unreadable). */
data class KnownPhoneBond(val kind: PhoneKind?, val bondedToAndroid: Boolean?)

/** Everything the probe collected, and its [classification]. */
data class StereoBluetoothReport(
    val classification: StereoBluetooth,
    val signals: List<StereoBluetoothSignal>,
    /** DiAuto's own [ExternalBtPolicy.detect] answer. */
    val diAutoEvidence: String?,
    /** System properties read (null: unset or unreadable). */
    val properties: Map<String, String?>,
    /** Device nodes and whether each exists. */
    val nodes: Map<String, Boolean>,
    val daemonPort: PortState,
    val packages: List<InstalledApp>,
    val adapter: AdapterFacts,
    /** Number of devices Android's adapter lists as bonded; null when unreadable. */
    val bondedCount: Int?,
    val knownPhones: List<KnownPhoneBond>,
    val hardware: String? = null,
    val tookMillis: Long = 0,
    /** False for the quick, main-thread-safe version (no port check, no package scan). */
    val complete: Boolean = true,
) {
    /** One structured block for carlink.log and the shared log. Never holds a hardware address. */
    fun format(): String = buildString {
        append("External Bluetooth probe: classification=").append(classification)
        append(" complete=").append(complete).append(" took=").append(tookMillis).append("ms\n")
        append("  signals=").append(signals.joinToString(",").ifEmpty { "none" }).append('\n')
        append("  diauto.evidence=").append(diAutoEvidence ?: "none").append('\n')
        hardware?.let { append("  hardware=").append(it).append('\n') }
        properties.forEach { (k, v) -> append("  prop ").append(k).append('=').append(v ?: "<unset>").append('\n') }
        nodes.forEach { (k, v) -> append("  node ").append(k).append('=').append(if (v) "exists" else "absent").append('\n') }
        append("  port 127.0.0.1:").append(ExternalBluetoothProbe.DAEMON_PORT).append('=').append(daemonPort).append('\n')
        append("  packages=").append(
            if (!complete) "not checked"
            else packages.joinToString(", ") { "${it.packageName} ${it.versionName ?: "?"} (${it.versionCode})" }.ifEmpty { "none" },
        ).append('\n')
        append("  adapter present=").append(adapter.present).append(" enabled=").append(adapter.enabled ?: "unknown")
            .append(" realAddress=").append(adapter.addressReal ?: "unknown").append('\n')
        append("  bonded=").append(bondedCount ?: "unreadable").append('\n')
        append("  knownPhones=").append(
            knownPhones.mapIndexed { i, p ->
                "phone${i + 1}(${p.kind ?: "UNKNOWN"}) bondedToAndroid=${p.bondedToAndroid ?: "unknown"}"
            }.joinToString(", ").ifEmpty { "none" },
        )
    }.let(com.carlink.core.diagnostics.LogMasking::mask)
}

/**
 * Collects the evidence about this head unit's Bluetooth that the app itself can see (there is no
 * adb on the user's stereos), so a shared log says which upstream route a port needs
 * (design/EXTERNAL_BLUETOOTH.md), and classifies it ([StereoBluetooth]).
 *
 * [probe] and [classify] are pure over the small [Sources] interfaces; [start]/[await]/[current]
 * run them on a device, off the main thread, once per process. The TCP check only connects to
 * 127.0.0.1:[DAEMON_PORT] and closes at once; nothing is sent.
 */
object ExternalBluetoothProbe {
    const val DAEMON_PORT = 3152
    const val PORT_TIMEOUT_MILLIS = 300
    const val FYT_TYPE_PROPERTY = "sys.fyt.bluetooth_type"
    const val ZLINK_TYPE_PROPERTY = "rw.zlink.bt.type"
    const val ZJ_TYPE_PROPERTY = "rw.zj.bt.type"
    const val AUTO_SERIAL = "/dev/auto_serial"
    val NODES = listOf(AUTO_SERIAL, "/dev/BT_serial", "/dev/rf_serial", "/dev/zj_bt_serial")
    private val ZLINK_NODES = setOf("/dev/rf_serial", "/dev/zj_bt_serial")
    val PROPERTIES = listOf(FYT_TYPE_PROPERTY, ZLINK_TYPE_PROPERTY, ZJ_TYPE_PROPERTY)

    /** Apps named outright: FYT's stock projection app (BLINK must disable it) and ZLink's. */
    val KNOWN_PACKAGES = listOf("com.syu.carlink", "com.zjinnova.zlink")

    /** Package-name fragments of vendor projection and module apps (ZLink/zjinnova, FYT, others). */
    val PACKAGE_PATTERNS = listOf(
        "zlink", "zjinnova", "carlink", "carplay", "androidauto", "easyconn", "autokit", "phonelink",
        "carbit", "speedplay", "gocsdk", "tlink",
    )

    fun interface FileCheck { fun exists(path: String): Boolean }
    fun interface PropertyReader { fun get(key: String): String? }
    fun interface PortCheck { fun check(host: String, port: Int, timeoutMillis: Int): PortState }
    fun interface PackageSource { fun installed(): List<InstalledApp> }
    interface BluetoothSource {
        fun adapter(): AdapterFacts
        /** Addresses of Android's bonded devices; null when unreadable (no permission, no adapter). */
        fun bondedAddresses(): List<String>?
    }
    fun interface KnownPhoneSource { fun phones(): Map<String, PhoneKind> }

    class Sources(
        val files: FileCheck,
        val properties: PropertyReader,
        val port: PortCheck,
        val packages: PackageSource,
        val bluetooth: BluetoothSource,
        val knownPhones: KnownPhoneSource,
        val hardware: String? = null,
        val ownPackages: Set<String> = emptySet(),
    )

    /** Collects the evidence. [complete] = false skips the port check and the package scan. */
    fun probe(sources: Sources, complete: Boolean = true, clock: () -> Long = { 0L }): StereoBluetoothReport {
        val started = clock()
        val diAuto = ExternalBtPolicy.detect({ runCatching { sources.files.exists(it) }.getOrDefault(false) },
            { runCatching { sources.properties.get(it) }.getOrNull() })
        val properties = PROPERTIES.associateWith { key -> runCatching { sources.properties.get(key) }.getOrNull()?.trim()?.ifEmpty { null } }
        val nodes = NODES.associateWith { runCatching { sources.files.exists(it) }.getOrDefault(false) }
        val port = if (complete) runCatching { sources.port.check("127.0.0.1", DAEMON_PORT, PORT_TIMEOUT_MILLIS) }
            .getOrDefault(PortState.ERROR) else PortState.NOT_CHECKED
        val packages = if (complete) runCatching { relevantPackages(sources.packages.installed(), sources.ownPackages) }
            .getOrDefault(emptyList()) else emptyList()
        val adapter = runCatching { sources.bluetooth.adapter() }.getOrDefault(AdapterFacts(false, null, null))
        val bonded = runCatching { sources.bluetooth.bondedAddresses() }.getOrNull()?.map(::normalize)?.toSet()
        val known = runCatching { sources.knownPhones.phones() }.getOrDefault(emptyMap())
            .map { (address, kind) -> KnownPhoneBond(kind, bonded?.contains(normalize(address))) }
        val signals = signals(properties, nodes, port)
        return StereoBluetoothReport(
            classification = classify(signals, adapter, known),
            signals = signals, diAutoEvidence = diAuto, properties = properties, nodes = nodes, daemonPort = port,
            packages = packages, adapter = adapter, bondedCount = bonded?.size, knownPhones = known,
            hardware = sources.hardware, tookMillis = clock() - started, complete = complete,
        )
    }

    /**
     * The signals behind a classification, strongest first. The ZLink markers are the ones DiAuto's
     * [ExternalBtPolicy] checks (its answer is also kept in the report, as `diauto.evidence`).
     */
    fun signals(properties: Map<String, String?>, nodes: Map<String, Boolean>, port: PortState): List<StereoBluetoothSignal> =
        buildList {
            if (port == PortState.OPEN) add(StereoBluetoothSignal.MODULE_SERVICE_ANSWERS)
            if (fytType(properties[FYT_TYPE_PROPERTY]) > 0) add(StereoBluetoothSignal.FYT_SETTING)
            if (nodes[AUTO_SERIAL] == true) add(StereoBluetoothSignal.FYT_SERIAL_PORT)
            if (nodes.any { (node, exists) -> exists && node in ZLINK_NODES }) add(StereoBluetoothSignal.MODULE_SERIAL_PORT)
            if (listOf(ZLINK_TYPE_PROPERTY, ZJ_TYPE_PROPERTY).any { properties[it].equals("extra", ignoreCase = true) }) {
                add(StereoBluetoothSignal.MODULE_SETTING)
            }
        }

    /**
     * The rule: no signal → [StereoBluetooth.NONE]; a known phone bonded to Android's adapter that
     * has a real address → [StereoBluetooth.LIKELY_FALSE_POSITIVE] (Android's radio reaches the
     * phone, so the markers may describe a second, unused module); a daemon on 3152 →
     * [StereoBluetooth.ZLINK_DAEMON]; FYT signals → [StereoBluetooth.FYT_MODULE]; ZLink markers
     * alone → [StereoBluetooth.EXTERNAL_UNKNOWN]. `/dev/BT_serial` is recorded but decides nothing.
     */
    fun classify(signals: List<StereoBluetoothSignal>, adapter: AdapterFacts, knownPhones: List<KnownPhoneBond>): StereoBluetooth = when {
        signals.isEmpty() -> StereoBluetooth.NONE
        adapter.addressReal == true && knownPhones.any { it.bondedToAndroid == true } -> StereoBluetooth.LIKELY_FALSE_POSITIVE
        StereoBluetoothSignal.MODULE_SERVICE_ANSWERS in signals -> StereoBluetooth.ZLINK_DAEMON
        StereoBluetoothSignal.FYT_SETTING in signals || StereoBluetoothSignal.FYT_SERIAL_PORT in signals -> StereoBluetooth.FYT_MODULE
        else -> StereoBluetooth.EXTERNAL_UNKNOWN
    }

    /** `sys.fyt.bluetooth_type` as a number (0 when unset or not a number). */
    fun fytType(value: String?): Int = value?.trim()?.toIntOrNull() ?: 0

    /** The installed apps worth naming: [KNOWN_PACKAGES] and names matching [PACKAGE_PATTERNS], never CarLink itself. */
    fun relevantPackages(installed: List<InstalledApp>, own: Set<String>): List<InstalledApp> = installed
        .filter { app ->
            val name = app.packageName.lowercase(Locale.ROOT)
            app.packageName !in own && !name.startsWith("com.carlink.app") &&
                (app.packageName in KNOWN_PACKAGES || PACKAGE_PATTERNS.any { it in name })
        }
        .distinctBy { it.packageName }
        .sortedBy { it.packageName }

    /** Android's privacy placeholder and an all-zero address are not real addresses. */
    fun isRealAddress(address: String?): Boolean {
        val a = address?.trim()?.uppercase(Locale.ROOT) ?: return false
        if (a == "02:00:00:00:00:00" || a == "00:00:00:00:00:00") return false
        return Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(a)
    }

    private fun normalize(address: String) = address.trim().uppercase(Locale.ROOT)

    // --- On a device ------------------------------------------------------------------------------

    @Volatile private var cached: StereoBluetoothReport? = null
    private var task: FutureTask<StereoBluetoothReport>? = null

    /** The full report once it is known (null before [start] finished). */
    fun cached(): StereoBluetoothReport? = cached

    /**
     * Runs the full probe once per process on its own thread and writes its block to carlink.log.
     * Cheap to call again.
     */
    fun start(context: Context): FutureTask<StereoBluetoothReport> = synchronized(this) {
        task ?: FutureTask {
            val report = probe(deviceSources(context.applicationContext), complete = true, clock = SystemClock::uptimeMillis)
            cached = report
            EventLog.add("CarLink", report.format())
            report
        }.also { t ->
            task = t
            Thread(t, "carlink-stereo-bt").apply { isDaemon = true }.start()
        }
    }

    /** The full report, waiting up to [timeoutMillis] for it; the quick one when it is late. Not on the main thread. */
    fun await(context: Context, timeoutMillis: Long = 2_000): StereoBluetoothReport =
        cached ?: runCatching { start(context).get(timeoutMillis, TimeUnit.MILLISECONDS) }.getOrNull() ?: quick(context)

    /**
     * The full report when known, else the quick one (files, properties, Bluetooth bonds; no
     * network, no package scan). Safe on the main thread: whether wireless is blocked does not
     * depend on the parts it skips except for a daemon with no other marker.
     */
    fun current(context: Context): StereoBluetoothReport = cached ?: quick(context)

    private fun quick(context: Context): StereoBluetoothReport =
        probe(deviceSources(context.applicationContext), complete = false, clock = SystemClock::uptimeMillis)

    /** Test hook: forget the cached report. */
    internal fun reset() = synchronized(this) { cached = null; task = null }

    private fun deviceSources(app: Context) = Sources(
        files = FileCheck { path -> File(path).exists() },
        properties = PropertyReader(::systemProperty),
        port = PortCheck(::checkPort),
        packages = PackageSource { installedApps(app) },
        bluetooth = DeviceBluetooth(app),
        knownPhones = KnownPhoneSource { KnownPhones(app).all() },
        hardware = "${Build.HARDWARE}/${Build.BOARD}",
        ownPackages = setOf(app.packageName),
    )

    private fun systemProperty(key: String): String? = runCatching {
        val method = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
        (method.invoke(null, key, "") as? String)?.ifEmpty { null }
    }.getOrNull()

    private fun checkPort(host: String, port: Int, timeoutMillis: Int): PortState = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMillis) }
        PortState.OPEN
    } catch (e: ConnectException) {
        PortState.CLOSED
    } catch (e: SocketTimeoutException) {
        PortState.TIMEOUT
    } catch (e: Exception) {
        PortState.ERROR
    }

    @Suppress("DEPRECATION")
    private fun installedApps(app: Context): List<InstalledApp> {
        val pm = app.packageManager
        fun info(p: android.content.pm.PackageInfo) = InstalledApp(p.packageName, p.versionName,
            if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong())
        val visible = runCatching { pm.getInstalledPackages(0).map(::info) }.getOrDefault(emptyList())
        // Android 11+ only lists what the manifest's <queries> make visible; ask for the named ones too.
        val named = KNOWN_PACKAGES.mapNotNull { name -> runCatching { info(pm.getPackageInfo(name, 0)) }.getOrNull() }
        return visible + named
    }

    private class DeviceBluetooth(private val app: Context) : BluetoothSource {
        private val adapter get() = app.getSystemService(BluetoothManager::class.java)?.adapter

        private fun canConnect() = Build.VERSION.SDK_INT < 31 ||
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        @SuppressLint("MissingPermission", "HardwareIds") // Read only with the permission; the address is never kept.
        override fun adapter(): AdapterFacts {
            val a = adapter ?: return AdapterFacts(present = false, enabled = null, addressReal = null)
            val enabled = runCatching { a.isEnabled }.getOrNull()
            val fromAdapter = if (canConnect()) runCatching { a.address }.getOrNull() else null
            // Android 6+ answers 02:00:00:00:00:00 to apps; older firmware still keeps the real one here.
            val fromSettings = runCatching { Settings.Secure.getString(app.contentResolver, "bluetooth_address") }.getOrNull()
            val real = when {
                isRealAddress(fromAdapter) || isRealAddress(fromSettings) -> true
                fromAdapter == null && fromSettings == null -> null
                else -> false
            }
            return AdapterFacts(present = true, enabled = enabled, addressReal = real)
        }

        @SuppressLint("MissingPermission") // Only read with BLUETOOTH_CONNECT granted.
        override fun bondedAddresses(): List<String>? {
            val a = adapter ?: return null
            if (!canConnect()) return null
            return runCatching { a.bondedDevices.map { it.address } }.getOrNull()
        }
    }
}
