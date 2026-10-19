package com.carlink.app.diagnostics

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.carlink.app.R
import com.carlink.app.ui.home.failureCopyRes
import com.carlink.core.connection.FailureReason
import com.carlink.core.connection.PhoneHealth
import com.carlink.core.connection.SessionState
import com.carlink.core.connection.toLinkProblem
import com.carlink.core.core.Engine
import com.carlink.core.core.Transport
import com.carlink.core.detect.ExternalBluetoothProbe
import com.carlink.core.settings.WirelessPath

/** A session state other than Idle, and when CarLink last saw it change (epoch millis). */
data class LastSession(val state: SessionState, val atMillis: Long)

/**
 * Gathers [GitHubIssueReport.Facts] on the device (off the main thread) and opens the result:
 * the browser when there is one, else the caller shows the QR code.
 */
object IssueReporter {

    /** Everything the report needs from the screens' state. */
    class Inputs(
        val lastSession: LastSession?,
        val health: List<PhoneHealth>,
        val carBrand: String?,
        val secrets: List<String>,
    )

    /** Builds the report. Reads the logs and waits for the Bluetooth probe: call off the main thread. */
    fun build(context: Context, inputs: Inputs): GitHubIssueReport.Report {
        val log = runCatching { ConnectionLog.read(context) }.getOrDefault(emptyList())
        val session = session(inputs.lastSession, inputs.health)?.let { s ->
            if (inputs.lastSession != null) s.copy(sessionNumber = GitHubIssueReport.sessionTag(log)) else s
        }
        val (titleRes, bodyRes) = failureCopyRes(session?.problem)
        val engineName = session?.engine?.let {
            context.getString(if (it == Engine.CARPLAY) R.string.carlink_engine_carplay else R.string.carlink_engine_android_auto)
        } ?: ""
        val probe = runCatching { ExternalBluetoothProbe.await(context) }.getOrNull()
        val facts = GitHubIssueReport.Facts(
            versionName = AppInfo.versionName,
            versionCode = AppInfo.versionCode,
            device = device(context),
            session = session,
            problemTitle = if (session?.failed == true) context.getString(titleRes,
                context.getString(R.string.carlink_home_error_pairing_lost_phone)) else null,
            problemBody = if (session?.failed == true) context.getString(bodyRes, engineName) else null,
            carBrand = inputs.carBrand,
            stereoBluetooth = probe?.let { report ->
                GitHubIssueReport.StereoBluetooth(report.classification.name, buildString {
                    append("signals ").append(report.signals.joinToString(",").ifEmpty { "none" })
                    append("; DiAuto: ").append(report.diAutoEvidence ?: "none")
                    append("; module port ").append(report.daemonPort)
                    report.hardware?.let { append("; hardware ").append(it) }
                })
            },
            log = log,
            secrets = inputs.secrets,
            nowMillis = System.currentTimeMillis(),
        )
        return GitHubIssueReport.build(facts)
    }

    /** The session the report describes: this process's last one, else the last problem in a phone's record. */
    fun session(last: LastSession?, health: List<PhoneHealth>): GitHubIssueReport.Session? {
        when (val state = last?.state) {
            is SessionState.Failed -> return GitHubIssueReport.Session(state.request.engine, state.request.transport, state.path,
                state.request.phoneName, failed = true, problem = state.reason.toLinkProblem(), reason = state.reason,
                detail = state.detail, atMillis = last.atMillis)
            is SessionState.Blocked -> return GitHubIssueReport.Session(state.request.engine, state.request.transport, null,
                state.request.phoneName, failed = true, problem = state.problems.firstOrNull()?.toLinkProblem(),
                detail = "blocked: " + state.problems.joinToString(","), atMillis = last.atMillis)
            is SessionState.Active -> return GitHubIssueReport.Session(state.request.engine, state.request.transport, state.path,
                state.request.phoneName, failed = false, detail = "phase ${state.phase}", atMillis = last.atMillis)
            SessionState.Idle, null -> Unit
        }
        val phone = health.firstOrNull() ?: return null
        val problem = phone.lastProblems.maxByOrNull { it.atMillis }
        val path = problem?.path ?: phone.bestPath
        return GitHubIssueReport.Session(
            engine = phone.engine,
            transport = if (path == null) null else if (path == "USB") Transport.USB else Transport.WIRELESS,
            path = path?.let { p -> WirelessPath.entries.firstOrNull { it.name == p } },
            phoneName = phone.phoneName,
            failed = false,
            reason = problem?.let { p -> FailureReason.entries.firstOrNull { it.name == p.kind } },
            detail = problem?.let { "last recorded problem: ${it.kind} at ${it.stage ?: "start"}" + (it.detail?.let { d -> ": $d" } ?: "") },
            atMillis = problem?.atMillis,
        )
    }

    fun device(context: Context): GitHubIssueReport.Device {
        val (w, h) = AppInfo.screenPixels(context)
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        return GitHubIssueReport.Device(AppInfo.manufacturer, AppInfo.model, Build.HARDWARE.orEmpty(), Build.BOARD.orEmpty(),
            AppInfo.androidRelease, Build.VERSION.SDK_INT, w, h, (w / density).toInt(), (h / density).toInt())
    }

    private fun viewIntent(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Whether any app opens web links (some stereos have no browser at all). */
    fun browserAvailable(context: Context, url: String): Boolean =
        runCatching { viewIntent(url).resolveActivity(context.packageManager) != null }.getOrDefault(false)

    /** Opens [url] in the browser; false when nothing could open it (show the QR code instead). */
    fun open(context: Context, url: String): Boolean = try {
        context.startActivity(viewIntent(url))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
