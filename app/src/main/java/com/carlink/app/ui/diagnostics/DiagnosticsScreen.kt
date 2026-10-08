package com.carlink.app.ui.diagnostics

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.Transport
import com.carlink.app.diagnostics.CarPlayAuthSource
import com.carlink.app.diagnostics.CheckFix
import com.carlink.app.diagnostics.CheckId
import com.carlink.app.diagnostics.CheckInputs
import com.carlink.app.diagnostics.CheckResult
import com.carlink.app.diagnostics.CheckStatus
import com.carlink.app.diagnostics.Checks
import com.carlink.app.diagnostics.DecoderInfo
import com.carlink.app.diagnostics.SystemScreens
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.components.CarIcon
import com.carlink.app.ui.components.CarTopBar
import com.carlink.app.ui.components.CheckRow
import com.carlink.app.ui.components.EngineBadge
import com.carlink.app.ui.components.NavigationRow
import com.carlink.app.ui.components.RowDivider
import com.carlink.app.ui.components.SectionCard
import com.carlink.app.ui.components.SectionHeader
import com.carlink.app.ui.components.StatGrid
import com.carlink.app.ui.components.StatTile
import com.carlink.app.ui.components.StatTone
import com.carlink.app.ui.components.engineName
import com.carlink.app.ui.components.ltr
import com.carlink.app.ui.components.transportName
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.UiProfile
import com.carlink.app.ui.theme.WidthClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Diagnostics (DESIGN_SPEC §6.5): "why isn't it working" in one place. The live session panel
 * shows only what CarLink actually knows about the session; the checks read the real system
 * state and are re-run every time the screen resumes (e.g. back from a system settings page).
 *
 * Layout: Live session | Checks side by side from EXPANDED width; stacked on MEDIUM; on
 * COMPACT, Checks come first because readiness matters most there.
 */
@Composable
fun DiagnosticsScreen(vm: CarLinkViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val home by vm.home.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val attemptStartedAt by vm.attemptStartedAt.collectAsStateWithLifecycle()

    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }

    val inputs = CheckInputs(
        bluetooth = home.bluetooth,
        phones = home.phones,
        autoConnectPaused = connection.autoConnectPaused,
        autoConnectEnabled = connection.autoConnectUsb || connection.autoConnectBluetooth,
    )
    val checks by produceState<List<CheckResult>?>(null, inputs, resumes) {
        value = withContext(Dispatchers.Default) { Checks.sorted(Checks.evaluate(context, inputs)) }
    }

    // A permission is asked for once from here; after that (or when Android stops showing the
    // prompt) Fix opens CarLink's app page, where it can always be granted.
    val asked = remember { mutableSetOf<String>() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        resumes++
        vm.refreshPhones()
    }
    val onFix: (CheckFix) -> Unit = { fix ->
        when (fix) {
            CheckFix.OpenBluetoothSettings -> vm.openBluetoothSettings()
            CheckFix.OpenAppSettings -> vm.openAppSettings()
            CheckFix.OpenOverlaySettings -> SystemScreens.openOverlaySettings(context)
            CheckFix.OpenBatterySettings -> SystemScreens.openBatterySettings(context)
            CheckFix.ResumeAutoConnect -> vm.resumeAutoConnect()
            is CheckFix.RequestPermissions ->
                if (fix.permissions.isEmpty() || fix.permissions.any { it in asked }) vm.openAppSettings()
                else {
                    asked += fix.permissions
                    permissionLauncher.launch(fix.permissions.toTypedArray())
                }
        }
    }

    DiagnosticsContent(
        link = home.link,
        connectedSince = home.connectedSince,
        attemptStartedAt = attemptStartedAt,
        checks = checks,
        onFix = onFix,
        onBack = onBack,
        onOpenCarPlay = vm::openCarPlaySettings,
        onOpenAndroidAuto = vm::openAndroidAutoSettings,
    )
}

@Composable
private fun DiagnosticsContent(
    link: LinkState,
    connectedSince: Long?,
    attemptStartedAt: Long?,
    checks: List<CheckResult>?,
    onFix: (CheckFix) -> Unit,
    onBack: () -> Unit,
    onOpenCarPlay: () -> Unit,
    onOpenAndroidAuto: () -> Unit,
) {
    val layout = CarLinkTokens.layout
    val dimens = CarLinkTokens.dimens
    val margin = if (layout.width == WidthClass.COMPACT) dimens.compactMargin else dimens.screenMargin
    val live = @Composable { LiveSession(link, connectedSince, attemptStartedAt) }
    val checkList = @Composable { ChecksSection(checks, onFix) }
    val logs = @Composable { EngineLogs(onOpenCarPlay, onOpenAndroidAuto) }

    // A Surface sets the content color, so text that doesn't name one (the shared top bar and
    // section headers) is onBackground rather than black.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(Modifier.fillMaxSize()) {
        CarTopBar(stringResource(R.string.carlink_diag_title), onBack = onBack)
        if (layout.width >= WidthClass.EXPANDED) {
            Row(
                Modifier.fillMaxSize().widthIn(max = 1600.dp).align(Alignment.CenterHorizontally).padding(horizontal = margin),
                horizontalArrangement = Arrangement.spacedBy(dimens.gutter),
            ) {
                ScrollColumn(Modifier.weight(1f)) { live(); logs() }
                ScrollColumn(Modifier.weight(1f)) { checkList() }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                ScrollColumn(Modifier.widthIn(max = dimens.contentMaxWidth).padding(horizontal = margin)) {
                    if (layout.width == WidthClass.COMPACT) {
                        checkList(); live()
                    } else {
                        live(); checkList()
                    }
                    logs()
                }
            }
        }
    } }
}

@Composable
private fun ScrollColumn(modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = CarLinkTokens.dimens.gutter)) {
        content()
    }
}

// ---- Live session -----------------------------------------------------------------------

@Composable
private fun LiveSession(link: LinkState, connectedSince: Long?, attemptStartedAt: Long?) {
    val dimens = CarLinkTokens.dimens
    SectionHeader(stringResource(R.string.carlink_diag_live_session))
    SectionCard {
        Column(Modifier.padding(dimens.gutter)) {
            if (link.stage == LinkStage.IDLE) IdleSession() else ActiveSession(link, connectedSince, attemptStartedAt)
        }
    }
}

@Composable
private fun IdleSession() {
    val dimens = CarLinkTokens.dimens
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(dimens.avatarRow).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center) {
            CarIcon(R.drawable.carlink_ic_link_off, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(dimens.gutter))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.carlink_diag_idle_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.carlink_diag_idle_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActiveSession(link: LinkState, connectedSince: Long?, attemptStartedAt: Long?) {
    val dimens = CarLinkTokens.dimens
    val stage = stageName(link.stage)
    val unknown = stringResource(R.string.carlink_diag_not_known)
    // Time on the clock: connected time once live, the attempt's time while it runs, none after a failure.
    val (timerLabel, since) = when (link.stage) {
        LinkStage.CONNECTED -> stringResource(R.string.carlink_diag_tile_connected_for) to connectedSince
        LinkStage.WAITING, LinkStage.CONNECTING -> stringResource(R.string.carlink_diag_tile_attempt) to attemptStartedAt
        else -> null to null
    }
    val elapsed = rememberElapsed(since)

    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(link.phoneName ?: stringResource(R.string.carlink_diag_phone_unnamed), Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (link.engine != null) {
            Spacer(Modifier.width(dimens.targetGap))
            EngineBadge(link.engine)
        }
    }
    Spacer(Modifier.height(dimens.gutter))

    data class Tile(val label: String, val value: String, val technical: Boolean = false, val live: Boolean = false)
    val tiles = buildList {
        add(Tile(stringResource(R.string.carlink_diag_tile_engine), link.engine?.let { engineName(it) } ?: unknown))
        add(Tile(stringResource(R.string.carlink_diag_tile_transport), link.transport?.let { transportName(it) } ?: unknown))
        add(Tile(stringResource(R.string.carlink_diag_tile_stage), stage, live = true))
        if (link.stepCount > 0 && (link.stage == LinkStage.WAITING || link.stage == LinkStage.CONNECTING)) {
            add(Tile(stringResource(R.string.carlink_diag_tile_step),
                stringResource(R.string.carlink_diag_step_value, link.step.coerceIn(1, link.stepCount), link.stepCount)))
        }
        if (timerLabel != null && elapsed != null) add(Tile(timerLabel, ltr(formatElapsed(elapsed)), technical = true))
    }
    StatGrid(tiles.size) { i, m ->
        val tile = tiles[i]
        // The stage is announced when it changes; the ticking timer is not.
        StatTile(tile.label, tile.value, if (tile.live) m.semantics { liveRegion = LiveRegionMode.Polite } else m,
            technical = tile.technical)
    }

    val detail = link.detail?.takeIf { it.isNotBlank() }
    if (detail != null) {
        Spacer(Modifier.height(dimens.targetGap))
        val failed = link.stage == LinkStage.FAILED
        StatTile(
            label = stringResource(if (failed) R.string.carlink_diag_tile_last_error else R.string.carlink_diag_tile_current_step),
            value = detail,
            modifier = Modifier.fillMaxWidth(),
            tone = if (failed) StatTone.ERROR else StatTone.NORMAL,
            maxValueLines = 6,
        )
    }
}

@Composable
private fun stageName(stage: LinkStage): String = stringResource(when (stage) {
    LinkStage.IDLE -> R.string.carlink_diag_stage_idle
    LinkStage.WAITING -> R.string.carlink_diag_stage_waiting
    LinkStage.CONNECTING -> R.string.carlink_diag_stage_connecting
    LinkStage.CONNECTED -> R.string.carlink_diag_stage_connected
    LinkStage.FAILED -> R.string.carlink_diag_stage_failed
})

/** Milliseconds since [since] (elapsedRealtime), updated once a second on the second; null without a start. */
@Composable
private fun rememberElapsed(since: Long?): Long? {
    val now by produceState(SystemClock.elapsedRealtime(), since) {
        if (since == null) return@produceState
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(1000 - (value - since).mod(1000L))
        }
    }
    return since?.let { (now - it).coerceAtLeast(0) }
}

/** "4:05" or "1:02:03", with Western digits (a technical value, DESIGN_SPEC §7.3). */
internal fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

// ---- Checks -----------------------------------------------------------------------------

@Composable
private fun ChecksSection(checks: List<CheckResult>?, onFix: (CheckFix) -> Unit) {
    val problems = checks?.let(Checks::problemCount)
    SectionHeader(
        stringResource(R.string.carlink_diag_checks),
        subtitle = when {
            problems == null -> stringResource(R.string.carlink_diag_checking)
            problems == 0 -> stringResource(R.string.carlink_diag_checks_all_passed)
            else -> pluralStringResource(R.plurals.carlink_diag_checks_problems, problems, problems)
        },
    )
    if (checks == null) return
    SectionCard {
        checks.forEachIndexed { index, result ->
            if (index > 0) RowDivider()
            val text = checkText(result)
            val fix = result.fix
            CheckRow(
                status = result.status,
                title = text.title,
                supporting = text.supporting,
                note = text.note,
                fixLabel = text.fixLabel,
                onFix = fix?.let { { onFix(it) } },
            )
        }
    }
}

// ---- Engine logs ------------------------------------------------------------------------

@Composable
private fun EngineLogs(onOpenCarPlay: () -> Unit, onOpenAndroidAuto: () -> Unit) {
    SectionHeader(stringResource(R.string.carlink_diag_engine_logs), subtitle = stringResource(R.string.carlink_diag_engine_logs_subtitle))
    SectionCard {
        NavigationRow(stringResource(R.string.carlink_diag_carplay_logs), onOpenCarPlay,
            supporting = stringResource(R.string.carlink_diag_carplay_logs_body), icon = R.drawable.carlink_ic_terminal, external = true)
        RowDivider()
        NavigationRow(stringResource(R.string.carlink_diag_aa_logs), onOpenAndroidAuto,
            supporting = stringResource(R.string.carlink_diag_aa_logs_body), icon = R.drawable.carlink_ic_terminal, external = true)
    }
}

// ---- Previews ---------------------------------------------------------------------------

private val previewChecks = listOf(
    CheckResult(CheckId.CARPLAY_AUTHENTICATION, CheckStatus.FAIL, authSource = CarPlayAuthSource.MISSING),
    CheckResult(CheckId.BATTERY_OPTIMIZATION, CheckStatus.WARN, CheckFix.OpenBatterySettings),
    CheckResult(CheckId.AUTO_CONNECT, CheckStatus.WARN, CheckFix.ResumeAutoConnect),
    CheckResult(CheckId.BLUETOOTH, CheckStatus.PASS),
    CheckResult(CheckId.PAIRED_PHONES, CheckStatus.PASS, count = 2, unknownCount = 1),
    CheckResult(CheckId.H264_DECODER, CheckStatus.PASS, decoder = DecoderInfo("c2.qti.avc.decoder", 4096, 2176)),
    CheckResult(CheckId.WIFI_DIRECT, CheckStatus.INFO),
)

private val previewLink = LinkState(LinkStage.CONNECTING, Engine.CARPLAY, Transport.WIRELESS, "Sara's iPhone", 3, 5,
    "Starting video")

@Preview(name = "Car landscape", widthDp = 1440, heightDp = 810)
@Composable
private fun DiagnosticsCarPreview() {
    CarLinkTheme(profile = UiProfile.CAR) {
        DiagnosticsContent(previewLink, null, SystemClock.elapsedRealtime() - 12_000, previewChecks, {}, {}, {}, {})
    }
}

@Preview(name = "Phone portrait", widthDp = 400, heightDp = 860)
@Composable
private fun DiagnosticsPhonePreview() {
    CarLinkTheme(profile = UiProfile.HANDHELD) {
        DiagnosticsContent(LinkState.Idle, null, null, previewChecks, {}, {}, {}, {})
    }
}
