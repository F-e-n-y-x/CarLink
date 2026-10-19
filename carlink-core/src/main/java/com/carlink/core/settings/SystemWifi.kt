package com.carlink.core.settings

import android.content.Context
import android.os.Build
import com.andrerinas.openheadunit.utils.HotspotConfigReader
import com.carlink.core.diagnostics.EventLog
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.network.CarHotspotConfigRead
import com.shilapi.xcertplay.network.CarHotspotConfigWrite
import com.shilapi.xcertplay.network.HotspotCredentials
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import kotlin.concurrent.thread

/**
 * The stereo's own Wi-Fi secrets, read (and the hotspot written) where Android lets CarLink, so the
 * driver never types them. Every call blocks (local ADB up to 15 s, a superuser prompt up to 20 s).
 *
 * Who may read what (no ordinary app may on Android 8+, `getWifiApConfiguration`/
 * `getSoftApConfiguration` need OVERRIDE_WIFI_CONFIG or NETWORK_SETTINGS, saved passwords
 * READ_WIFI_CREDENTIAL):
 * 1. the app itself on Android 7.1 and on firmware that grants it (DiAuto's reflection reader);
 * 2. the head unit's own adbd shell on Android 11+ (DiPlay's `CarHotspotConfigRead`/`Write`,
 *    after the driver approved "Allow debugging?" once);
 * 3. root (`su`), any version: the files Android keeps them in.
 * Nothing here logs a name or password.
 */
object SystemWifi {
    private const val TAG = "CarLink-SystemWifi"
    private val WIFI_DIRS = listOf("/data/misc/apexdata/com.android.wifi", "/data/misc/wifi")

    /** The hotspot's saved name and password, or null. [allowRoot] may show the superuser prompt. */
    fun readHotspot(context: Context, allowRoot: Boolean): WifiDetails? {
        runCatching { HotspotConfigReader.getSystemHotspotConfig(context) }.getOrNull()
            ?.let { (ssid, password) -> WifiDetails(ssid, password) }?.takeIf { it.isValid() }
            ?.let { log("hotspot read by the app"); return it }
        runCatching { CarHotspotConfigRead.read(context, log = ::log) }.getOrNull()?.credentials
            ?.let { WifiDetails(it.ssid, it.passphrase) }?.takeIf { it.isValid() }
            ?.let { log("hotspot read through local ADB"); return it }
        if (!allowRoot) return null
        val root = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) su("/data/misc/wifi/softap.conf")?.let(::parseSoftApConf)
        else WIFI_DIRS.firstNotNullOfOrNull { su("$it/WifiConfigStoreSoftAp.xml") }?.let { parseSoftApXml(String(it)) }
        log("hotspot root read: ${if (root != null) "found" else "nothing"}")
        return root?.takeIf { it.isValid() }
    }

    enum class Write { APPLIED, NO_ACCESS, FAILED }

    /**
     * Saves [details] as the hotspot's name and password (Android 7.1 from the app, 11+ through
     * local ADB; may show "Allow debugging?"). Android 8-10 refuse even the shell.
     */
    fun writeHotspot(context: Context, details: WifiDetails): Write {
        if (!CarHotspotConfigWrite.supported()) return Write.NO_ACCESS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val access = runCatching { LocalAdb(AdbKeys.load(context)).use { it.connect(mayAsk = true) } }.getOrNull()
            log("hotspot write: ADB $access")
            if (access != LocalAdb.Access.READY) return Write.NO_ACCESS
        }
        val security = if (details.password.isEmpty()) ManualHotspotSecurity.OPEN else ManualHotspotSecurity.WPA2
        val outcome = runCatching {
            CarHotspotConfigWrite.write(context, HotspotCredentials(details.ssid, details.password, security, rename = true), log = ::log)
        }.getOrNull()
        log("hotspot write: ${outcome?.result}")
        return when (outcome?.result) {
            CarHotspotConfigWrite.Result.WRITTEN -> Write.APPLIED
            CarHotspotConfigWrite.Result.ADB_UNAVAILABLE, CarHotspotConfigWrite.Result.UNSUPPORTED_SDK -> Write.NO_ACCESS
            else -> Write.FAILED
        }
    }

    /**
     * The saved password of Wi-Fi network [ssid], root only: Android 10+ hides saved passwords from
     * every app (only the system's READ_WIFI_CREDENTIAL holders see them). Shows the superuser prompt.
     */
    fun readSavedPassword(ssid: String): String? =
        WIFI_DIRS.firstNotNullOfOrNull { su("$it/WifiConfigStore.xml") }?.let { parseSavedPassword(String(it), ssid) }
            .also { log("saved Wi-Fi root read: ${if (it != null) "found" else "nothing"}") }

    /** Android 8-10 `softap.conf` (WifiApConfigStore, versions 1-3): SSID, band/channel, hidden, key type, key. */
    internal fun parseSoftApConf(bytes: ByteArray): WifiDetails? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).run {
            val version = readInt()
            if (version !in 1..3) return null
            val ssid = readUTF()
            if (version >= 2) { readInt(); readInt() }
            if (version >= 3) readBoolean()
            val keyType = readInt() // WifiConfiguration.KeyMgmt: 0 NONE, 4 WPA2_PSK
            WifiDetails(ssid, if (keyType == 0) "" else readUTF())
        }
    }.getOrNull()

    /** Android 11+ `WifiConfigStoreSoftAp.xml`: `SSID` (11-12) or quoted `WifiSsid` (13+), `Passphrase`. */
    internal fun parseSoftApXml(xml: String): WifiDetails? {
        val ssid = xmlString(xml, "SSID") ?: xmlString(xml, "WifiSsid")?.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') }
            ?.removeSurrounding("\"") ?: return null
        return WifiDetails(ssid, xmlString(xml, "Passphrase") ?: "")
    }

    /** The quoted `PreSharedKey` of the network whose `SSID` is [ssid] in `WifiConfigStore.xml`; a raw 64-hex key is skipped. */
    internal fun parseSavedPassword(xml: String, ssid: String): String? =
        Regex("<WifiConfiguration>(.*?)</WifiConfiguration>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { it.groupValues[1] }
            .firstOrNull { xmlString(it, "SSID") == "\"$ssid\"" }
            ?.let { xmlString(it, "PreSharedKey") }
            ?.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') }
            ?.removeSurrounding("\"")

    private fun xmlString(xml: String, name: String): String? =
        Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.let(::unescape)

    private fun unescape(text: String): String = text
        .replace(Regex("&#(x?)([0-9a-fA-F]+);")) { m -> m.groupValues[2].toInt(if (m.groupValues[1].isEmpty()) 10 else 16).toChar().toString() }
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** [path]'s bytes read as root, or null without `su`, on refusal, or after 20 s. */
    private fun su(path: String): ByteArray? = runCatching {
        val process = ProcessBuilder("su", "-c", "cat '$path' 2>/dev/null").start()
        val out = ByteArrayOutputStream()
        val reader = thread(isDaemon = true) { runCatching { process.inputStream.copyTo(out) } }
        reader.join(20_000) // Process.waitFor(timeout) needs API 26
        if (reader.isAlive) { process.destroy(); return null }
        out.toByteArray().takeIf { process.waitFor() == 0 && it.isNotEmpty() }
    }.getOrNull()

    private fun log(message: String) = EventLog.add(TAG, message)
}
