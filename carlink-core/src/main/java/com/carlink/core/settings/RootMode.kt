package com.carlink.core.settings

import android.content.Context
import com.carlink.core.diagnostics.EventLog
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Optional root helpers (Settings › Connection › Root mode, advanced; off by default). Every call
 * goes through `su -c`, so the root manager (Magisk, KernelSU, APatch) asks the driver once.
 * Never called on the main thread: `su` can wait for that prompt.
 *
 * - [grant]: the grants the setup otherwise asks for one screen at a time (VPN, display over other
 *   apps, no battery limits, the runtime permissions), with no dialog.
 * - [projecting]: Wi-Fi low-latency mode while a phone projects (no power-save naps on the link the
 *   picture travels over), restored when it ends.
 *
 * ponytail: no CPU governor or scheduler changes; kernels differ too much to do that safely blind.
 */
object RootMode {
    private const val TAG = "CarLink-Root"
    @Volatile private var lowLatency = false

    /** True when `su` runs commands as uid 0; shows the root manager's prompt the first time. */
    fun available(): Boolean = run("id")?.contains("uid=0") == true

    fun grant(context: Context, runtimePermissions: List<String>): Boolean {
        val pkg = context.packageName
        val commands = grantCommands(pkg, runtimePermissions)
        val ok = run(commands.joinToString("; ")) != null
        EventLog.add(TAG, "grants over root: ${if (ok) "done" else "failed"}")
        return ok
    }

    fun projecting(on: Boolean) {
        if (on == lowLatency) return
        lowLatency = on
        val ok = run(projectionCommands(on).joinToString("; ")) != null
        EventLog.add(TAG, "Wi-Fi low-latency mode ${if (on) "on" else "off"}: ${if (ok) "done" else "failed"}")
    }

    internal fun grantCommands(pkg: String, runtimePermissions: List<String>): List<String> = listOf(
        "appops set $pkg ACTIVATE_VPN allow",
        "appops set $pkg SYSTEM_ALERT_WINDOW allow",
        "dumpsys deviceidle whitelist +$pkg >/dev/null",
    ) + runtimePermissions.map { "pm grant $pkg $it 2>/dev/null" } + "true"

    // `cmd wifi force-low-latency-mode` exists from Android 11; `iw` only on some firmware. Each
    // is best effort (errors hidden), and the last `true` keeps the exit status 0.
    internal fun projectionCommands(on: Boolean): List<String> = if (on) listOf(
        "cmd wifi force-low-latency-mode enabled 2>/dev/null",
        "iw dev wlan0 set power_save off 2>/dev/null",
        "iw dev p2p0 set power_save off 2>/dev/null",
        "true",
    ) else listOf("cmd wifi force-low-latency-mode disabled 2>/dev/null", "true")

    /** [command]'s output as root, or null without `su`, on refusal, a non-zero exit or after 20 s. */
    private fun run(command: String): String? = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        val reader = thread(isDaemon = true) { runCatching { process.inputStream.copyTo(out) } }
        reader.join(20_000) // Process.waitFor(timeout) needs API 26
        if (reader.isAlive) { process.destroy(); return null }
        out.toString().takeIf { process.waitFor() == 0 }
    }.getOrNull()
}
