package com.carlink.app.ui.diagnostics

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.Transport
import com.carlink.app.diagnostics.AppInfo
import com.carlink.app.diagnostics.CarLinkFileProvider
import com.carlink.app.diagnostics.CarPlayAuthSource
import com.carlink.app.diagnostics.CheckFix
import com.carlink.app.diagnostics.CheckId
import com.carlink.app.diagnostics.CheckInputs
import com.carlink.app.diagnostics.CheckResult
import com.carlink.app.diagnostics.CheckStatus
import com.carlink.app.diagnostics.Checks
import com.carlink.app.diagnostics.ConnectionLog
import com.carlink.app.diagnostics.DecoderInfo
import com.carlink.app.diagnostics.EventLog
import com.carlink.app.diagnostics.LogLine
import com.carlink.app.diagnostics.LogSource
import com.carlink.app.diagnostics.SystemScreens
import com.carlink.app.settings.SettingsCategory
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.components.StatGrid
import com.carlink.app.ui.components.engineName
import com.carlink.app.ui.components.formatElapsed
import com.carlink.app.ui.components.ltr
import com.carlink.app.ui.components.transportName
import com.carlink.app.ui.premium.GlassButton
import com.carlink.app.ui.premium.GlassCard
import com.carlink.app.ui.premium.GlyphDisc
import com.carlink.app.ui.premium.PremiumScreen
import com.carlink.app.ui.settings.GlassGroup
import com.carlink.app.ui.settings.GroupDivider
import com.carlink.app.ui.settings.GroupHeader
import com.carlink.app.ui.settings.RowGlyph
import com.carlink.app.ui.settings.SelectableGlass
import com.carlink.app.ui.settings.Tag
import com.carlink.app.ui.settings.isCar
import com.carlink.app.ui.settings.rowPadding
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.UiProfile
import com.carlink.app.ui.theme.WidthClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything Diagnostics renders, so previews and screenshots need no ViewModel. */
internal data class DiagnosticsUi(
    val link: LinkState,
    val connectedSince: Long?,
    val attemptStartedAt: Long?,
    /** Null while the checks run. */
    val checks: List<CheckResult>?,
    /** Newest first; null while the engine logs are read. */
    val log: List<LogLine>?,
    /** Each known phone's connection record, most recently used first. */
    val health: List<com.carlink.app.connection.PhoneHealth> = emptyList(),
)

internal interface DiagnosticsActions {
    fun fix(fix: CheckFix) {}
    fun openSettings(category: SettingsCategory) {}
    fun shareLog() {}
    fun back() {}
}

/** The connection-log filter chips. */
internal enum class LogFilter(val source: LogSource?) { ALL(null), CARPLAY(LogSource.CARPLAY), ANDROID_AUTO(LogSource.ANDROID_AUTO), CARLINK(LogSource.CARLINK) }

/**
 * Diagnostics: "why isn't it working" in one place. A summary, the live session, readiness
 * checks (re-run on every resume, e.g. back from a system settings page) and the connection log
 * of both engines and CarLink itself, all on CarLink's own canvas: nothing opens an engine screen.
 *
 * Wide windows: summary, session and checks on the start side, the log filling the end side.
 * Narrow windows: one column; on COMPACT the checks come before the session.
 */
@Composable
fun DiagnosticsScreen(vm: CarLinkViewModel, onBack: () -> Unit, openSettings: (SettingsCategory) -> Unit) {
    val context = LocalContext.current
    val home by vm.home.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val attemptStartedAt by vm.attemptStartedAt.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()

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
    val log = rememberConnectionLog()

    // A permission is asked for once from here; after that (or when Android stops showing the
    // prompt) Fix opens CarLink's app page, where it can always be granted.
    val asked = remember { mutableSetOf<String>() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        resumes++
        vm.refreshPhones()
    }
    val scope = rememberCoroutineScope()
    val shareSubject = stringResource(R.string.carlink_diag_log_share_subject)
    val shareChooser = stringResource(R.string.carlink_diag_log_share_chooser)
    val shareHeader = stringResource(R.string.carlink_diag_log_report_header, AppInfo.versionName, AppInfo.versionCode,
        AppInfo.carPlayEngineVersion, AppInfo.androidAutoEngineVersion, AppInfo.manufacturer, AppInfo.model, AppInfo.androidRelease)
    val actions = remember(vm) {
        object : DiagnosticsActions {
            override fun fix(fix: CheckFix) = when (fix) {
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
            override fun openSettings(category: SettingsCategory) = openSettings(category)
            override fun shareLog() {
                scope.launch {
                    val file = withContext(Dispatchers.IO) { runCatching { ConnectionLog.export(context, shareHeader) }.getOrNull() }
                    if (file != null) CarLinkFileProvider.shareText(context, file, shareSubject, shareChooser)
                }
            }
            override fun back() = onBack()
        }
    }
    DiagnosticsContent(DiagnosticsUi(home.link, home.connectedSince, attemptStartedAt, checks, log, health), actions)
}

/**
 * The engine logs, re-read every two seconds while the screen is resumed (only when a file
 * changed), merged with CarLink's live [EventLog]. Newest first.
 */
@Composable
private fun rememberConnectionLog(): List<LogLine>? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val events by EventLog.entries.collectAsStateWithLifecycle()
    var engines by remember { mutableStateOf<List<List<LogLine>>?>(null) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var stamp: Long? = null
            while (true) {
                val now = withContext(Dispatchers.IO) { ConnectionLog.stamp(context) }
                if (now != stamp) {
                    engines = withContext(Dispatchers.IO) { ConnectionLog.readEngines(context) }
                    stamp = now
                }
                delay(2000)
            }
        }
    }
    val loaded = engines ?: return null
    return remember(loaded, events) { ConnectionLog.merge(*(loaded + listOf(ConnectionLog.events(events))).toTypedArray()) }
}

@Composable
internal fun DiagnosticsContent(ui: DiagnosticsUi, actions: DiagnosticsActions, initialFilter: LogFilter = LogFilter.ALL) {
    val dimens = CarLinkTokens.dimens
    val compact = CarLinkTokens.layout.width == WidthClass.COMPACT
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    PremiumScreen(stringResource(R.string.carlink_diag_title), onBack = actions::back) { margin ->
        val gap = if (isCar) 24.dp else 16.dp
        val twoPane = maxWidth >= 840.dp && maxHeight >= 420.dp
        if (twoPane) {
            Row(Modifier.fillMaxSize().padding(horizontal = margin)) {
                Column(
                    Modifier.weight(0.47f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(top = 8.dp, bottom = dimens.gutter),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    Summary(ui.checks)
                    SessionCard(ui)
                    PhonesHealthCard(ui.health)
                    ChecksCard(ui.checks, actions)
                }
                Spacer(Modifier.width(dimens.gutter * 1.5f))
                LogPanel(ui.log, filter, { filter = it }, actions::shareLog,
                    Modifier.weight(0.53f).fillMaxHeight().padding(top = 8.dp, bottom = dimens.gutter))
            }
        } else {
            val logHeight = (maxHeight * 0.8f).coerceIn(360.dp, 760.dp)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = dimens.contentMaxWidth).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(horizontal = margin).padding(top = 8.dp, bottom = dimens.gutter * 2),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    Summary(ui.checks)
                    if (compact) {
                        ChecksCard(ui.checks, actions); SessionCard(ui); PhonesHealthCard(ui.health)
                    } else {
                        SessionCard(ui); PhonesHealthCard(ui.health); ChecksCard(ui.checks, actions)
                    }
                    LogPanel(ui.log, filter, { filter = it }, actions::shareLog, Modifier.fillMaxWidth().height(logHeight))
                }
            }
        }
    }
}

// ---- Summary ------------------------------------------------------------------------------

/** The headline: are we ready? One glyph, one sentence. */
@Composable
private fun Summary(checks: List<CheckResult>?) {
    val colors = CarLinkTokens.colors
    val scheme = MaterialTheme.colorScheme
    val problems = checks?.let(Checks::problemCount)
    val failed = checks?.any { it.status == CheckStatus.FAIL } == true
    val (icon, tint) = when {
        problems == null -> R.drawable.carlink_ic_sync to scheme.onSurfaceVariant
        problems == 0 -> R.drawable.carlink_ic_check_circle_filled to colors.success
        failed -> R.drawable.carlink_ic_error_filled to scheme.error
        else -> R.drawable.carlink_ic_warning_filled to colors.warning
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically) {
        com.carlink.app.ui.premium.IconTile(icon, tint = tint, size = if (isCar) 88.dp else 56.dp)
        Spacer(Modifier.width(if (isCar) 28.dp else 18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    problems == null -> stringResource(R.string.carlink_diag_checking)
                    problems == 0 -> stringResource(R.string.carlink_diag_checks_all_passed)
                    else -> pluralStringResource(R.plurals.carlink_diag_checks_problems, problems, problems)
                },
                style = MaterialTheme.typography.headlineMedium, color = scheme.onSurface, modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.carlink_diag_summary_body), style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant)
        }
    }
}

// ---- Live session -------------------------------------------------------------------------

@Composable
private fun SessionCard(ui: DiagnosticsUi) {
    val link = ui.link
    GlassGroup(header = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { GroupHeader(stringResource(R.string.carlink_diag_live_session), icon = R.drawable.carlink_ic_monitor_heart) }
            if (link.stage != LinkStage.IDLE) StageTag(link.stage)
        }
    }) {
        Column(Modifier.fillMaxWidth().padding(start = rowPadding, end = rowPadding, top = 12.dp, bottom = rowPadding)) {
            if (link.stage == LinkStage.IDLE) IdleSession() else ActiveSession(ui)
        }
    }
}

@Composable
private fun StageTag(stage: LinkStage) {
    val colors = CarLinkTokens.colors
    val scheme = MaterialTheme.colorScheme
    // The same tell-tale as Home's: a lit dot in the state's colour.
    val color = when (stage) {
        LinkStage.CONNECTED -> colors.success
        LinkStage.FAILED -> scheme.error
        else -> scheme.primary
    }
    Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { com.carlink.app.ui.premium.TellTale(stageName(stage), color) }
}

@Composable
private fun IdleSession() {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        RowGlyph(R.drawable.carlink_ic_link_off, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(if (isCar) 24.dp else 16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.carlink_diag_idle_title), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.carlink_diag_idle_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActiveSession(ui: DiagnosticsUi) {
    val link = ui.link
    val scheme = MaterialTheme.colorScheme
    val unknown = stringResource(R.string.carlink_diag_not_known)
    // Time on the clock: connected time once live, the attempt's time while it runs, none after a failure.
    val (timerLabel, since) = when (link.stage) {
        LinkStage.CONNECTED -> stringResource(R.string.carlink_diag_tile_connected_for) to ui.connectedSince
        LinkStage.WAITING, LinkStage.CONNECTING -> stringResource(R.string.carlink_diag_tile_attempt) to ui.attemptStartedAt
        else -> null to null
    }
    val elapsed = rememberElapsed(since)

    Text(link.phoneName ?: stringResource(R.string.carlink_diag_phone_unnamed), style = MaterialTheme.typography.headlineMedium,
        color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(if (isCar) 20.dp else 14.dp))

    data class Tile(val label: String, val value: String)
    val tiles = buildList {
        add(Tile(stringResource(R.string.carlink_diag_tile_engine), link.engine?.let { engineName(it) } ?: unknown))
        add(Tile(stringResource(R.string.carlink_diag_tile_transport), link.transport?.let { transportName(it) } ?: unknown))
        if (link.stepCount > 0 && (link.stage == LinkStage.WAITING || link.stage == LinkStage.CONNECTING)) {
            add(Tile(stringResource(R.string.carlink_diag_tile_step),
                stringResource(R.string.carlink_diag_step_value, link.step.coerceIn(1, link.stepCount), link.stepCount)))
        }
        if (timerLabel != null && elapsed != null) add(Tile(timerLabel, formatElapsed(elapsed)))
    }
    StatGrid(tiles.size, minTileWidth = if (isCar) 200.dp else 130.dp) { i, m ->
        StatTile(tiles[i].label, tiles[i].value, m)
    }

    // The driver's wording of why it waits; a failure's own detail; never the internal "phone_not_answering" marker.
    val detail = com.carlink.app.ui.home.noticeText(link)
        ?: link.detail?.takeIf { it.isNotBlank() && it != com.carlink.app.connection.DETAIL_PHONE_NOT_ANSWERING }
    if (detail != null) {
        val failed = link.stage == LinkStage.FAILED
        Spacer(Modifier.height(CarLinkTokens.dimens.targetGap))
        StatTile(stringResource(if (failed) R.string.carlink_diag_tile_last_error else R.string.carlink_diag_tile_current_step),
            detail, Modifier.fillMaxWidth(), tint = if (failed) scheme.error else null, maxLines = 6)
    }
}

/** A value on a lighter glass tile: label above, value in tabular figures. */
@Composable
internal fun StatTile(label: String, value: String, modifier: Modifier, tint: Color? = null, maxLines: Int = 2, labelLines: Int = 1) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.clip(MaterialTheme.shapes.medium).background(scheme.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = if (isCar) 24.dp else 16.dp, vertical = if (isCar) 18.dp else 12.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = CarLinkTokens.colors.onSurfaceTertiary, maxLines = labelLines,
            overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Top) {
            if (tint != null) {
                Icon(painterResource(R.drawable.carlink_ic_error_filled), null, Modifier.padding(top = 2.dp).size(if (isCar) 28.dp else 18.dp),
                    tint = tint)
                Spacer(Modifier.width(8.dp))
            }
            Text(value, style = MaterialTheme.typography.titleMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
                color = tint ?: scheme.onSurface, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
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

// ---- Checks -------------------------------------------------------------------------------

@Composable
private fun ChecksCard(checks: List<CheckResult>?, actions: DiagnosticsActions) {
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_diag_checks), icon = R.drawable.carlink_ic_check_circle) }) {
        Spacer(Modifier.height(4.dp))
        if (checks == null) {
            Text(stringResource(R.string.carlink_diag_checking), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = rowPadding, end = rowPadding, bottom = rowPadding))
            return@GlassGroup
        }
        checks.forEachIndexed { index, result ->
            if (index > 0) GroupDivider()
            CheckLine(result, actions)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * One readiness check: status glyph (never color alone), title, supporting text, an optional
 * note, and at most one action. The text merges into one screen-reader node; the action stays
 * a separate target. On narrow cards the action moves under the text.
 */
@Composable
private fun CheckLine(result: CheckResult, actions: DiagnosticsActions) {
    val text = checkText(result)
    val scheme = MaterialTheme.colorScheme
    // Automatic connection that is simply off is turned on in Settings, not by a system screen.
    val settingsLink = result.id == CheckId.AUTO_CONNECT && result.status == CheckStatus.INFO
    val label = text.fixLabel ?: if (settingsLink) stringResource(R.string.carlink_diag_open_settings) else null
    val onAction: (() -> Unit)? = result.fix?.let { fix -> { actions.fix(fix) } }
        ?: if (settingsLink) ({ actions.openSettings(SettingsCategory.CONNECTION) }) else null
    val actionDescription = stringResource(R.string.carlink_diag_fix_a11y, text.title)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stack = maxWidth < if (isCar) 680.dp else 440.dp
        val action: (@Composable (Modifier) -> Unit)? = if (label != null && onAction != null) {
            { m -> GlassButton(label, onAction, m.semantics { contentDescription = actionDescription }) }
        } else null
        val glyph = if (isCar) 56.dp else 40.dp
        Column(Modifier.fillMaxWidth().padding(horizontal = rowPadding, vertical = if (isCar) 18.dp else 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The glyph lines up with the title, so long texts hang under it instead of centring on it.
                Row(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                    StatusGlyph(result.status, glyph)
                    Spacer(Modifier.width(if (isCar) 24.dp else 16.dp))
                    Column(Modifier.weight(1f).padding(top = if (isCar) 10.dp else 9.dp)) {
                        Text(text.title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                        if (text.supporting != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(text.supporting, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                        if (text.note != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(text.note, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                }
                if (action != null && !stack) {
                    Spacer(Modifier.width(CarLinkTokens.dimens.targetGap))
                    action(Modifier)
                }
            }
            if (action != null && stack) {
                Spacer(Modifier.height(12.dp))
                action(Modifier.padding(start = glyph + if (isCar) 24.dp else 16.dp))
            }
        }
    }
}

@Composable
private fun StatusGlyph(status: CheckStatus, size: Dp) {
    val colors = CarLinkTokens.colors
    val scheme = MaterialTheme.colorScheme
    val (icon, tint, label) = when (status) {
        CheckStatus.PASS -> Triple(R.drawable.carlink_ic_check_circle_filled, colors.success, R.string.carlink_diag_status_pass)
        CheckStatus.WARN -> Triple(R.drawable.carlink_ic_warning_filled, colors.warning, R.string.carlink_diag_status_warn)
        CheckStatus.FAIL -> Triple(R.drawable.carlink_ic_error_filled, scheme.error, R.string.carlink_diag_status_fail)
        CheckStatus.INFO -> Triple(R.drawable.carlink_ic_info, scheme.onSurfaceVariant, R.string.carlink_diag_status_info)
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), stringResource(label), Modifier.size(size * 0.55f), tint = tint)
    }
}

// ---- Connection log -----------------------------------------------------------------------

@Composable
private fun LogPanel(log: List<LogLine>?, filter: LogFilter, onFilter: (LogFilter) -> Unit, onShare: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shown = remember(log, filter) { log?.let { lines -> filter.source?.let { s -> lines.filter { it.source == s } } ?: lines } }
    GlassCard(modifier, contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = rowPadding, end = rowPadding, top = if (isCar) 24.dp else 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { GroupHeader(stringResource(R.string.carlink_diag_log_title), icon = R.drawable.carlink_ic_terminal) }
                    Spacer(Modifier.width(12.dp))
                    GlassButton(stringResource(R.string.carlink_diag_log_share), onShare, icon = R.drawable.carlink_ic_ios_share)
                }
                Spacer(Modifier.height(if (isCar) 4.dp else 2.dp))
                Text(stringResource(R.string.carlink_diag_log_body), style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(if (isCar) 18.dp else 12.dp))
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = rowPadding),
                horizontalArrangement = Arrangement.spacedBy(if (isCar) 12.dp else 8.dp)) {
                LogFilter.entries.forEach { f -> FilterPill(filterName(f), f == filter) { onFilter(f) } }
            }
            Spacer(Modifier.height(if (isCar) 16.dp else 10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onSurface.copy(alpha = 0.08f)))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    shown == null -> LogMessage(R.drawable.carlink_ic_sync, stringResource(R.string.carlink_diag_log_loading), null)
                    shown.isEmpty() -> LogMessage(R.drawable.carlink_ic_terminal,
                        if (filter == LogFilter.ALL) stringResource(R.string.carlink_diag_log_empty_title)
                        else stringResource(R.string.carlink_diag_log_empty_filtered, filterName(filter)),
                        stringResource(R.string.carlink_diag_log_empty_body))
                    else -> LogLines(shown)
                }
            }
        }
    }
}

@Composable
private fun filterName(filter: LogFilter): String = when (filter) {
    LogFilter.ALL -> stringResource(R.string.carlink_diag_log_filter_all)
    LogFilter.CARPLAY -> engineName(Engine.CARPLAY)
    LogFilter.ANDROID_AUTO -> engineName(Engine.ANDROID_AUTO)
    LogFilter.CARLINK -> stringResource(R.string.app_name)
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SelectableGlass(selected, onClick, Modifier.heightIn(min = CarLinkTokens.dimens.minTouch), shape = CircleShape) {
        Box(Modifier.heightIn(min = CarLinkTokens.dimens.minTouch).padding(horizontal = if (isCar) 22.dp else 16.dp),
            contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) scheme.primary else scheme.onSurface, maxLines = 1)
        }
    }
}

@Composable
private fun LogMessage(icon: Int, title: String, body: String?) {
    Column(Modifier.fillMaxSize().padding(rowPadding), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        RowGlyph(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.widthIn(max = 560.dp))
        }
    }
}

@Composable
private fun LogLines(lines: List<LogLine>) {
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val mono = MaterialTheme.typography.bodySmall.merge(TextStyle(fontFamily = FontFamily.Monospace, letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = rowPadding, vertical = if (isCar) 12.dp else 8.dp)) {
        items(lines) { line -> LogRow(line, clock.format(Date(line.timeMillis)), mono) }
    }
}

@Composable
private fun LogRow(line: LogLine, time: String, mono: TextStyle) {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    val sourceColor = when (line.source) {
        LogSource.CARPLAY -> colors.engineCarPlay
        LogSource.ANDROID_AUTO -> colors.engineAndroidAuto
        LogSource.CARLINK -> scheme.secondary
    }
    val textColor = when (line.level) {
        'E', 'A' -> scheme.error
        'W' -> colors.warning
        else -> scheme.onSurface.copy(alpha = 0.9f)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = if (isCar) 10.dp else 6.dp).semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (isCar) 10.dp else 7.dp).clip(CircleShape).background(sourceColor))
            Spacer(Modifier.width(if (isCar) 12.dp else 8.dp))
            Text(ltr(time), style = MaterialTheme.typography.labelMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
                color = colors.onSurfaceTertiary, maxLines = 1)
            Spacer(Modifier.width(if (isCar) 14.dp else 10.dp))
            Text(sourceName(line.source), style = MaterialTheme.typography.labelMedium, color = sourceColor, maxLines = 1)
            if (line.isProblem) {
                Spacer(Modifier.width(10.dp))
                Icon(painterResource(if (line.level == 'W') R.drawable.carlink_ic_warning_filled else R.drawable.carlink_ic_error_filled),
                    null, Modifier.size(if (isCar) 22.dp else 14.dp), tint = textColor)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(line.text, style = mono, color = textColor, maxLines = 6, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = if (isCar) 22.dp else 15.dp))
    }
}

@Composable
private fun sourceName(source: LogSource): String = when (source) {
    LogSource.CARPLAY -> engineName(Engine.CARPLAY)
    LogSource.ANDROID_AUTO -> engineName(Engine.ANDROID_AUTO)
    LogSource.CARLINK -> stringResource(R.string.app_name)
}

// ---- Previews -----------------------------------------------------------------------------

internal val previewChecks = listOf(
    CheckResult(CheckId.CARPLAY_AUTHENTICATION, CheckStatus.FAIL, authSource = CarPlayAuthSource.MISSING),
    CheckResult(CheckId.BATTERY_OPTIMIZATION, CheckStatus.WARN, CheckFix.OpenBatterySettings),
    CheckResult(CheckId.AUTO_CONNECT, CheckStatus.WARN, CheckFix.ResumeAutoConnect),
    CheckResult(CheckId.BLUETOOTH, CheckStatus.PASS),
    CheckResult(CheckId.PAIRED_PHONES, CheckStatus.PASS, count = 2, unknownCount = 1),
    CheckResult(CheckId.H264_DECODER, CheckStatus.PASS, decoder = DecoderInfo("c2.qti.avc.decoder", 4096, 2176)),
    CheckResult(CheckId.WIFI_DIRECT, CheckStatus.INFO),
)

internal val previewLink = LinkState(LinkStage.CONNECTING, Engine.CARPLAY, Transport.WIRELESS, "Sara’s iPhone", 3, 5,
    "Starting video")

internal fun previewLog(now: Long = System.currentTimeMillis()): List<LogLine> = listOf(
    LogLine(now - 1_000, LogSource.CARPLAY, null, "Video stream started 1920x1080 @ 60 fps"),
    LogLine(now - 2_400, LogSource.CARLINK, null, "SessionManager: CarPlay session is live"),
    LogLine(now - 4_100, LogSource.CARPLAY, 'W', "AirPlay timeout waiting for RTSP SETUP, retrying"),
    LogLine(now - 6_900, LogSource.CARPLAY, null, "Wi-Fi joined, phone at [ip]"),
    LogLine(now - 9_300, LogSource.CARLINK, null, "Bluetooth: Sara’s iPhone ([address]) connected, starting CarPlay"),
    LogLine(now - 61_000, LogSource.ANDROID_AUTO, 'E', "Handshake failed: SSL read returned -1"),
    LogLine(now - 64_000, LogSource.ANDROID_AUTO, 'I', "Starting wireless Android Auto on port 5288"),
    LogLine(now - 66_500, LogSource.CARLINK, null, "UsbWatcher: Pixel 8 attached, Android Auto"),
)

private object PreviewActions : DiagnosticsActions

@Preview(name = "Car landscape", widthDp = 1440, heightDp = 810)
@Composable
private fun DiagnosticsCarPreview() {
    CarLinkTheme(profile = UiProfile.CAR) {
        DiagnosticsContent(DiagnosticsUi(previewLink, null, SystemClock.elapsedRealtime() - 12_000, previewChecks, previewLog()), PreviewActions)
    }
}

@Preview(name = "Phone portrait", widthDp = 400, heightDp = 860)
@Composable
private fun DiagnosticsPhonePreview() {
    CarLinkTheme(profile = UiProfile.HANDHELD) {
        DiagnosticsContent(DiagnosticsUi(LinkState.Idle, null, null, previewChecks, emptyList()), PreviewActions)
    }
}
