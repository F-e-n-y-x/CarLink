package com.carlink.app.ui.diagnostics

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.diagnostics.AppInfo
import com.carlink.app.diagnostics.CarLinkFileProvider
import com.carlink.app.diagnostics.ConnectionLog
import com.carlink.app.diagnostics.GitHubIssueReport
import com.carlink.app.diagnostics.IssueReporter
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.components.CarSheet
import com.carlink.app.ui.components.QrCode
import com.carlink.app.ui.premium.GlassButton
import com.carlink.app.ui.premium.IonButton
import com.carlink.app.ui.settings.isCar
import com.carlink.app.ui.theme.CarLinkTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the "Report on GitHub" flow is: the confirmation sheet (null report while it is built) or the QR fallback. */
internal sealed interface ReportStage {
    data object Closed : ReportStage
    data class Confirm(val report: GitHubIssueReport.Report?) : ReportStage
    data class Qr(val report: GitHubIssueReport.Report) : ReportStage
}

/**
 * "Report a problem on GitHub" for Diagnostics and Home: returns the action that starts it and
 * shows its sheets. The report is built off the main thread (logs, Bluetooth probe); the sheet
 * opens at once and fills in. Open GitHub goes to the browser, or to the QR code when this screen
 * has none ([GitHubIssueReport.route]).
 */
@Composable
fun rememberReportProblem(vm: CarLinkViewModel): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<ReportStage>(ReportStage.Closed) }
    when (val s = stage) {
        ReportStage.Closed -> Unit
        is ReportStage.Confirm -> IssueConfirmSheet(s.report,
            onOpen = {
                val report = s.report ?: return@IssueConfirmSheet
                val route = GitHubIssueReport.route(IssueReporter.browserAvailable(context, report.url))
                stage = if (route == GitHubIssueReport.Route.BROWSER && IssueReporter.open(context, report.url)) ReportStage.Closed
                else ReportStage.Qr(report)
            },
            onCopyLog = { scope.launch { copyConnectionLog(context) } },
            onCancel = { stage = ReportStage.Closed })
        is ReportStage.Qr -> IssueQrSheet(s.report,
            onShareLog = { scope.launch { shareConnectionLog(context) } },
            onCopyLog = { scope.launch { copyConnectionLog(context) } },
            onDone = { stage = ReportStage.Closed })
    }
    return remember(vm) {
        {
            if (stage == ReportStage.Closed) {
                stage = ReportStage.Confirm(null)
                val inputs = IssueReporter.Inputs(vm.lastSessions(), vm.health.value, vm.carBrandName(), vm.savedSecrets(),
                    vm.reportSettings())
                scope.launch {
                    val report = withContext(Dispatchers.IO) { runCatching { IssueReporter.build(context, inputs) }.getOrNull() }
                    if (stage is ReportStage.Confirm) stage = if (report != null) ReportStage.Confirm(report) else ReportStage.Closed
                }
            }
        }
    }
}

private fun logHeader(context: Context) = context.getString(R.string.carlink_diag_log_report_header, AppInfo.versionName,
    AppInfo.versionCode, AppInfo.carPlayEngineVersion, AppInfo.androidAutoEngineVersion, AppInfo.manufacturer, AppInfo.model,
    AppInfo.androidRelease)

/** Writes the combined log and opens the share sheet (Diagnostics' Share log). */
internal suspend fun shareConnectionLog(context: Context) {
    val file = withContext(Dispatchers.IO) { runCatching { ConnectionLog.export(context, logHeader(context)) }.getOrNull() }
    if (file != null) {
        CarLinkFileProvider.shareText(context, file, context.getString(R.string.carlink_diag_log_share_subject),
            context.getString(R.string.carlink_diag_log_share_chooser))
    }
}

/**
 * Puts the combined log on the clipboard (Copy full log) for pasting into the issue. A clip travels
 * through one Binder transaction (1 MB), so a log over [CLIP_MAX_CHARS] keeps its head (versions,
 * stereo probe) and newest lines.
 */
internal suspend fun copyConnectionLog(context: Context) {
    val text = withContext(Dispatchers.IO) {
        runCatching { ConnectionLog.export(context, logHeader(context)).readText() }.getOrNull()
    } ?: return
    val clip = if (text.length <= CLIP_MAX_CHARS) text
    else text.take(4_000) + "\n…\n" + text.takeLast(CLIP_MAX_CHARS - 4_000)
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    runCatching { clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.carlink_diag_log_share_subject), clip)) }
        .onSuccess {
            // Android 13+ shows its own "Copied" confirmation.
            if (android.os.Build.VERSION.SDK_INT < 33) {
                android.widget.Toast.makeText(context, R.string.carlink_diag_log_copied, android.widget.Toast.LENGTH_LONG).show()
            }
        }
}

private const val CLIP_MAX_CHARS = 300_000

private val monoStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodySmall.merge(TextStyle(fontFamily = FontFamily.Monospace,
        letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified))

/**
 * Before anything leaves the stereo: what the issue will hold (readable, with the first lines),
 * that it is public with addresses and passwords removed, and that the browser must be signed in.
 * Open GitHub and Cancel stay docked under the scrolling details, as large targets.
 */
@Composable
internal fun IssueConfirmSheet(report: GitHubIssueReport.Report?, onOpen: () -> Unit, onCopyLog: () -> Unit, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dimens = CarLinkTokens.dimens
    CarSheet(stringResource(R.string.carlink_report_title), onDismiss = onCancel, scrollable = false) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.carlink_report_intro), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(if (isCar) 20.dp else 14.dp))
            Note(R.drawable.carlink_ic_lock, stringResource(R.string.carlink_report_public))
            Spacer(Modifier.height(10.dp))
            Note(R.drawable.carlink_ic_info, stringResource(R.string.carlink_report_sign_in))
            Spacer(Modifier.height(10.dp))
            Note(R.drawable.carlink_ic_content_copy, stringResource(R.string.carlink_report_full_log))
            Spacer(Modifier.height(if (isCar) 24.dp else 16.dp))
            if (report == null) {
                Text(stringResource(R.string.carlink_report_preparing), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            } else {
                Included(report)
            }
            Spacer(Modifier.height(16.dp))
        }
        // A hairline marks where the details scroll under the docked buttons.
        Box(Modifier.fillMaxWidth().height(1.dp).background(CarLinkTokens.colors.hairline))
        FlowRow(Modifier.fillMaxWidth().padding(vertical = if (isCar) 20.dp else 14.dp),
            horizontalArrangement = Arrangement.spacedBy(dimens.targetGap), verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
            IonButton(stringResource(R.string.carlink_report_open), { if (report != null) onOpen() }, icon = R.drawable.carlink_ic_open_in_new)
            GlassButton(stringResource(R.string.carlink_diag_log_copy), onCopyLog, icon = R.drawable.carlink_ic_content_copy)
            GlassButton(stringResource(R.string.carlink_report_cancel), onCancel, icon = R.drawable.carlink_ic_close)
        }
    }
}

@Composable
private fun Note(@DrawableRes icon: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), null, Modifier.padding(top = 2.dp).size(CarLinkTokens.dimens.iconTertiary),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ColumnScope.Included(report: GitHubIssueReport.Report) {
    val scheme = MaterialTheme.colorScheme
    val tertiary = CarLinkTokens.colors.onSurfaceTertiary
    Text(stringResource(R.string.carlink_report_included).uppercase(), style = MaterialTheme.typography.labelSmall, color = tertiary)
    Spacer(Modifier.height(8.dp))
    val labels = listOf(
        "title" to R.string.carlink_report_field_title, "version" to R.string.carlink_report_field_version,
        "screen" to R.string.carlink_report_field_screen, "phone" to R.string.carlink_report_field_phone,
        "projection" to R.string.carlink_report_field_projection, "connection" to R.string.carlink_report_field_connection,
        "car_brand" to R.string.carlink_report_field_car_brand,
    )
    labels.forEach { (key, label) ->
        val value = report.fields[key] ?: return@forEach
        FieldRow(stringResource(label), value)
    }
    report.fields["what_happened"]?.let { what ->
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.carlink_report_field_what_happened), style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(what.lines().filter { it.isNotBlank() }.take(7).joinToString("\n"), style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(14.dp))
    Text(pluralStringResource(R.plurals.carlink_report_log_lines, report.logLines.size, report.logLines.size),
        style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
    if (report.omittedLines > 0) {
        Text(pluralStringResource(R.plurals.carlink_report_log_more, report.omittedLines, report.omittedLines),
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
    if (report.logLines.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(scheme.onSurface.copy(alpha = 0.06f))
            .padding(12.dp)) {
            Text(report.logLines.take(6).joinToString("\n"), style = monoStyle, color = scheme.onSurfaceVariant,
                maxLines = 12, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = CarLinkTokens.colors.onSurfaceTertiary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The screen has no browser: a QR code of the short report (title, version, device and the error)
 * for the phone to open, and Share log for the full log.
 */
@Composable
internal fun IssueQrSheet(report: GitHubIssueReport.Report, onShareLog: () -> Unit, onCopyLog: () -> Unit, onDone: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dimens = CarLinkTokens.dimens
    CarSheet(stringResource(R.string.carlink_report_qr_title), onDismiss = onDone) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val qr = minOf(maxWidth, if (isCar) 360.dp else 300.dp)
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.carlink_report_qr_body), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface,
                    modifier = Modifier.widthIn(max = 720.dp))
                Spacer(Modifier.height(if (isCar) 20.dp else 14.dp))
                QrCode(report.shortUrl, qr, stringResource(R.string.carlink_report_qr_a11y), Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(if (isCar) 20.dp else 14.dp))
                Text(stringResource(R.string.carlink_report_qr_log), style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(if (isCar) 20.dp else 14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(dimens.targetGap), verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
                    IonButton(stringResource(R.string.carlink_diag_log_share), onShareLog, icon = R.drawable.carlink_ic_ios_share)
                    GlassButton(stringResource(R.string.carlink_diag_log_copy), onCopyLog, icon = R.drawable.carlink_ic_content_copy)
                    GlassButton(stringResource(R.string.carlink_report_done), onDone, icon = R.drawable.carlink_ic_check)
                }
            }
        }
    }
}

/** A report for previews and the screenshot gallery: wireless Android Auto on an FYT stereo, the phone did not answer; an earlier USB CarPlay session. */
internal fun previewIssueReport(now: Long = System.currentTimeMillis()): GitHubIssueReport.Report {
    val aa = com.carlink.app.diagnostics.LogSource.ANDROID_AUTO
    val cp = com.carlink.app.diagnostics.LogSource.CARPLAY
    val cl = com.carlink.app.diagnostics.LogSource.CARLINK
    fun line(age: Long, source: com.carlink.app.diagnostics.LogSource, text: String, level: Char? = null) =
        com.carlink.app.diagnostics.LogLine(now - age, source, level, text)
    return GitHubIssueReport.build(
        GitHubIssueReport.Facts(
            versionName = "1.2.0", versionCode = 10200,
            device = GitHubIssueReport.Device("FYT", "UIS7862", "ums512", "s9863a", "10", 29, 1280, 720, 1280, 720, 160),
            sessions = listOf(
                GitHubIssueReport.Session(com.carlink.core.core.Engine.ANDROID_AUTO, com.carlink.core.core.Transport.WIRELESS,
                    com.carlink.core.settings.WirelessPath.WIFI_DIRECT, "Pixel 8", failed = true,
                    problem = com.carlink.core.core.LinkProblem.PHONE_DID_NOT_ANSWER,
                    reason = com.carlink.core.connection.FailureReason.PHONE_DID_NOT_ANSWER,
                    detail = "no RFCOMM answer within 30000 ms", sessionNumber = 4, atMillis = now - 40_000, phoneModel = "Google Pixel 8"),
                GitHubIssueReport.Session(com.carlink.core.core.Engine.CARPLAY, com.carlink.core.core.Transport.USB, null, "iPhone",
                    failed = false, detail = "phase Connected", sessionNumber = 2, atMillis = now - 600_000,
                    phoneModel = "iPhone13,1", phoneOs = "26.5.2"),
            ),
            problemTitle = "Your phone didn’t answer",
            problemBody = "Unlock your phone, make sure its Bluetooth is on and connected to this screen, then try again.",
            carBrand = "Hyundai",
            stereoBluetooth = GitHubIssueReport.StereoBluetooth("NONE", "signals none; DiAuto: none; module port CLOSED"),
            log = listOf(
                line(40_000, cl, "CarLink: ANDROID_AUTO failed on WIFI_DIRECT (PHONE_DID_NOT_ANSWER) #s4"),
                line(41_000, aa, "RFCOMM connect failed: read failed, socket might closed", 'E'),
                line(52_000, aa, "WIRELESS_TIMING group-created start+1210ms"),
                line(53_000, aa, "WIRELESS_TIMING begin start+0ms"),
                line(53_500, cl, "CarLink: start ANDROID_AUTO WIRELESS known=true #s4"),
                line(590_000, cp, "Video: video stats rx=29.8fps shown=29.6fps maxGap=67ms kbps=3789 decode p50=45ms p90=67ms late=0"),
                line(595_000, cl, "CarLink: CARPLAY connected on USB in 3412 ms #s2"),
                line(596_000, cp, "WIRED_TIMING first-frame plug+3300ms session+3100ms"),
                line(598_000, cp, "WIRED_TIMING iphone-found plug+120ms session+0ms"),
                line(598_400, cl, "CarLink: start CARPLAY USB known=true #s2"),
            ) + previewLog(now - 600_000),
            secrets = emptyList(),
            nowMillis = now,
            settings = "connection AUTOMATIC; picture AUTOMATIC; Direct video output off; root mode off; overlay granted; " +
                "VPN consent granted; auto-connect USB on, Bluetooth on, on boot off; car HYUNDAI (GENERIC); UI profile AUTO, scale 1.0",
            hardware = com.carlink.core.connection.DeviceSignals(2_900, false, 8, listOf("arm64-v8a"), 1_600, "PowerVR Rogue GE8322"),
        ),
    )
}
