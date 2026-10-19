package com.carlink.app.ui.setup

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.app.R
import com.carlink.app.diagnostics.SystemScreens
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.components.CarLinkMark
import com.carlink.app.ui.components.engineName
import com.carlink.app.ui.components.isolate
import com.carlink.app.ui.components.phoneIcon
import com.carlink.app.ui.home.NoticeLine
import com.carlink.app.ui.home.failureCopy
import com.carlink.app.ui.home.oneTapFix
import com.carlink.app.ui.home.noticeIcon
import com.carlink.app.ui.home.noticeText
import com.carlink.app.ui.premium.Atmosphere
import com.carlink.app.ui.premium.Eyebrow
import com.carlink.app.ui.premium.GlassButton
import com.carlink.app.ui.premium.GlassCard
import com.carlink.app.ui.premium.IconTile
import com.carlink.app.ui.premium.IonButton
import com.carlink.app.ui.premium.ProgressLine
import com.carlink.app.ui.premium.RoundGlassIconButton
import com.carlink.app.ui.premium.TellTale
import com.carlink.app.ui.settings.CarBrandNote
import com.carlink.app.ui.settings.CarBrandPicker
import com.carlink.app.ui.settings.GlassGroup
import com.carlink.app.ui.settings.GroupDivider
import com.carlink.app.ui.settings.SettingsRow
import com.carlink.app.ui.settings.Tag
import com.carlink.app.ui.settings.isCar
import com.carlink.app.ui.settings.carBrandLabel
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.CarMotion
import com.carlink.app.ui.theme.LocalReduceMotion
import com.carlink.app.ui.theme.WidthClass
import com.carlink.core.connection.AdbVpnConsent
import com.carlink.core.connection.PairingSession
import com.carlink.core.core.Engine
import com.carlink.core.core.LinkProblem
import com.carlink.core.core.LinkStage
import com.carlink.core.core.LinkState
import com.carlink.core.core.PhoneKind
import com.carlink.core.core.Transport
import com.carlink.core.detect.PairedPhone
import com.carlink.core.settings.CarBrand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-time setup (design/RESEARCH_PLUG_AND_PLAY.md "Lessons"): welcome, one permission step
 * that asks for everything a session needs, the car brand, pairing a phone the way shipping
 * receivers do (this screen visible under its Bluetooth name, the driver pairs from the phone,
 * the bond is detected live and the first connection starts by itself), and done.
 *
 * Runs at launch until finished or skipped once ([CarLinkViewModel.setupNeeded]); Settings ›
 * General › Set up again re-runs it, and Home's "Pair a phone" opens it at [SetupStep.PAIR].
 */
@Composable
fun SetupScreen(vm: CarLinkViewModel, start: SetupStep, onExit: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(start) }
    var cable by rememberSaveable { mutableStateOf(false) }
    var found by rememberSaveable(stateSaver = phoneSaver) { mutableStateOf<PairedPhone?>(null) }
    var connectedFor by rememberSaveable { mutableStateOf<String?>(null) }
    var visibleUntil by rememberSaveable { mutableLongStateOf(0L) }
    var askedVisible by rememberSaveable { mutableStateOf(false) }
    var lastCarName by rememberSaveable { mutableStateOf<String?>(null) }

    // Permissions are re-read on every resume: most are granted on Android's own screens.
    var reads by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { reads++; onPauseOrDispose { } }
    val permissions = remember(reads) { readPermissions(context) }
    // "Allow all" walks every missing item, one system screen after another: the items already
    // asked in this run (so a refusal is not asked again) and whether a system screen is still open.
    var chain by remember { mutableStateOf<Set<SetupItem>?>(null) }
    var waiting by remember { mutableStateOf(false) }

    val vehicle by vm.vehicle.collectAsStateWithLifecycle()
    val wirelessUnavailable by vm.wirelessUnavailable.collectAsStateWithLifecycle()
    val home by vm.home.collectAsStateWithLifecycle()
    val pairing = remember { PairingSession(context) }
    val scope = rememberCoroutineScope()
    DisposableEffect(step == SetupStep.PAIR) {
        if (step == SetupStep.PAIR) pairing.start()
        onDispose { pairing.close() }
    }
    val pairState by pairing.state.collectAsStateWithLifecycle()
    (pairState as? PairingSession.State.Waiting)?.carName?.let { if (it != lastCarName) lastCarName = it }

    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        waiting = false
        reads++
        vm.refreshPhones()
    }
    val system = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { waiting = false; reads++ }
    val discover = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // The system dialog answers with the visible time in seconds, or "cancelled".
        if (result.resultCode > 0) visibleUntil = SystemClock.elapsedRealtime() + result.resultCode * 1000L
    }
    val now by produceState(SystemClock.elapsedRealtime(), visibleUntil) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(5_000)
        }
    }

    fun makeVisible() {
        askedVisible = true
        runCatching { discover.launch(PairingSession.discoverableIntent()) }
    }

    // Entering the pairing step asks the system to make this screen visible, once.
    LaunchedEffect(step, pairState is PairingSession.State.BluetoothOff) {
        if (step == SetupStep.PAIR && !cable && !askedVisible && found == null && pairState is PairingSession.State.Waiting) makeVisible()
    }

    // A phone bonded: remember it, and start its first connection once its kind is known.
    LaunchedEffect(pairState) {
        val paired = pairState as? PairingSession.State.Paired ?: return@LaunchedEffect
        val kind = if (found?.address == paired.address && found?.kind != PhoneKind.UNKNOWN) found!!.kind else paired.kind
        found = PairedPhone(paired.address, paired.phoneName ?: found?.name.orEmpty(), kind, connected = false)
        if (kind != PhoneKind.UNKNOWN && connectedFor != paired.address) {
            connectedFor = paired.address
            vm.connect(found!!)
        }
    }

    val fixProblem = com.carlink.app.ui.home.rememberProblemFix(vm)
    val exit = { vm.finishSetup(); onExit() }
    val chooseCable: (Boolean) -> Unit = { cable = it }
    val actions = object : SetupActions {
        override fun next() {
            step = SetupStep.entries.getOrElse(step.ordinal + 1) { SetupStep.DONE }
        }
        override fun back() {
            if (step == start || step == SetupStep.WELCOME) exit() else step = SetupStep.entries[step.ordinal - 1]
        }
        override fun exit() = exit()
        override fun allowAll() {
            chain = emptySet()
            waiting = false
            reads++ // the effect below asks for the first missing item
        }
        override fun allow(item: SetupItem) {
            when (item.grant) {
                GrantKind.RUNTIME -> permissions.firstOrNull { it.item == item }?.permissions?.takeIf { it.isNotEmpty() }
                    ?.let { runtime.launch(it.toTypedArray()) }
                GrantKind.OVERLAY -> launchOr(system, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"))) { SystemScreens.openOverlaySettings(context) }
                GrantKind.BATTERY -> launchOr(system, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"))) { SystemScreens.openBatterySettings(context) }
                // The stereo's own approved adb can allow it with no dialog; Android's dialog otherwise.
                GrantKind.VPN -> scope.launch {
                    if (withContext(Dispatchers.IO) { AdbVpnConsent.ensure(context) }) reads++
                    else android.net.VpnService.prepare(context)?.let { launchOr(system, it) { } } ?: run { reads++ }
                }
            }
        }
        override fun setCarBrand(brand: CarBrand) = vm.setCarBrand(brand)
        override fun makeVisible() = makeVisible()
        override fun turnOnBluetooth() {
            launchOr(system, Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) { vm.openBluetoothSettings() }
        }
        override fun setKind(kind: PhoneKind) {
            val phone = found ?: return
            vm.setPhoneKind(phone, kind)
            found = phone.copy(kind = kind)
            connectedFor = phone.address
            vm.connect(phone.copy(kind = kind))
        }
        override fun connectKnown(phone: PairedPhone) {
            cable = false
            found = phone
            connectedFor = phone.address
            vm.connect(phone)
        }
        override fun useCable(cable: Boolean) = chooseCable(cable)
        override fun retry() = vm.retry()
        override fun fix(problem: LinkProblem) = fixProblem(problem)
    }
    BackHandler { actions.back() }

    // Each answer (and each return to this screen) re-reads the grants and asks the next one.
    LaunchedEffect(reads, step) {
        val asked = chain ?: return@LaunchedEffect
        if (waiting) return@LaunchedEffect
        val next = SetupPermissions.nextToAllow(permissions, asked)
        if (step != SetupStep.PERMISSIONS || next.isEmpty()) { chain = null; return@LaunchedEffect }
        chain = asked + next.map { it.item }
        val item = next.first().item
        if (item.grant == GrantKind.RUNTIME) {
            waiting = true
            runtime.launch(next.flatMap { it.permissions }.toTypedArray())
        } else {
            // VPN answers through its own path (adb or the system launcher), the others through [system].
            waiting = item.grant != GrantKind.VPN
            actions.allow(item)
        }
    }

    val ui = SetupUi(
        step = step,
        permissions = permissions,
        carBrand = vehicle.brand,
        detectedBrand = vehicle.detected,
        pairing = pairState,
        carName = (pairState as? PairingSession.State.Waiting)?.carName ?: lastCarName,
        visible = now < visibleUntil,
        link = home.link,
        phone = found,
        knownPhones = home.phones.filter { it.kind != PhoneKind.UNKNOWN }.take(3),
        cable = cable,
        wirelessUnavailable = wirelessUnavailable,
    )
    SetupContent(ui, actions)
}

private val phoneSaver = listSaver<PairedPhone?, String>(
    save = { phone -> phone?.let { listOf(it.address, it.name, it.kind.name) } ?: emptyList() },
    restore = { values -> if (values.size == 3) PairedPhone(values[0], values[1], PhoneKind.valueOf(values[2]), false) else null },
)

private fun launchOr(launcher: androidx.activity.result.ActivityResultLauncher<Intent>, intent: Intent, fallback: () -> Unit) {
    runCatching { launcher.launch(intent) }.onFailure { fallback() }
}

/** Every setup item and whether it is granted now. */
private fun readPermissions(context: Context): List<SetupPermission> {
    val sdk = Build.VERSION.SDK_INT
    return SetupPermissions.items(sdk).map { (item, names) ->
        val granted = when (item.grant) {
            GrantKind.RUNTIME -> names.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            GrantKind.OVERLAY -> Settings.canDrawOverlays(context)
            GrantKind.BATTERY -> runCatching {
                context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
            }.getOrDefault(false)
            GrantKind.VPN -> runCatching { android.net.VpnService.prepare(context) == null }.getOrDefault(false)
        }
        SetupPermission(item, names, granted)
    }
}

// ---- Stateless content ------------------------------------------------------------------------

/** The light of the current step: Ion while setting up, Live once a phone is found or connected, Fault on a failure. */
@Composable
private fun stepColor(ui: SetupUi): Color {
    val colors = CarLinkTokens.colors
    val scheme = MaterialTheme.colorScheme
    return when {
        ui.step == SetupStep.DONE -> colors.success
        ui.step != SetupStep.PAIR -> scheme.primary
        sessionOf(ui)?.stage == LinkStage.FAILED -> scheme.error
        sessionOf(ui)?.stage == LinkStage.CONNECTED -> colors.success
        ui.pairing is PairingSession.State.BluetoothOff && !ui.cable -> colors.warning
        ui.phone != null && ui.phone.kind != PhoneKind.UNKNOWN -> colors.success
        else -> scheme.primary
    }
}

/** The session started from this setup (its phone, or a cable), or null for anything else. */
private fun sessionOf(ui: SetupUi): LinkState? {
    val link = ui.link
    if (link.stage == LinkStage.IDLE) return null
    return when {
        ui.cable -> link.takeIf { it.transport == Transport.USB }
        ui.phone != null -> link.takeIf { it.transport == Transport.WIRELESS && it.phoneName == ui.phone.name }
        else -> null
    }
}

/**
 * Stateless setup, used by [SetupScreen], previews and the screenshot gallery. Landscape windows
 * put the step's words on the start side and its instrument (the plan, the permission list, the
 * car tiles, this screen's Bluetooth name) on the end side; narrow windows stack them. Each side
 * scrolls on its own when it does not fit, so nothing is ever cut.
 */
@Composable
internal fun SetupContent(ui: SetupUi, actions: SetupActions) {
    val dimens = CarLinkTokens.dimens
    val compact = CarLinkTokens.layout.width == WidthClass.COMPACT
    val margin = if (compact) dimens.compactMargin else dimens.screenMargin
    val light = stepColor(ui)
    Atmosphere(Modifier.fillMaxSize(), glow = light.copy(alpha = 0.55f), glowCenter = Offset(0.74f, 0.5f),
        lightCenter = Offset(0.7f, -0.2f)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            SetupTopBar(ui.step, actions, margin)
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val landscape = maxWidth > maxHeight && maxWidth >= 720.dp
                val viewport = maxHeight
                val reduceMotion = LocalReduceMotion.current
                AnimatedContent(ui.step, transitionSpec = {
                    val duration = if (reduceMotion) 0 else CarMotion.MEDIUM
                    fadeIn(tween(duration)) togetherWith fadeOut(tween(duration / 2))
                }, label = "setup-step") { step ->
                    val shown = ui.copy(step = step)
                    if (landscape) {
                        Row(Modifier.fillMaxSize().padding(horizontal = margin)) {
                            ScrollPane(Modifier.weight(0.46f), viewport) { StepCopy(shown, actions) }
                            Spacer(Modifier.width(dimens.gutter * 2))
                            ScrollPane(Modifier.weight(0.54f), viewport) { StepPanel(shown, actions) }
                        }
                    } else {
                        ScrollPane(Modifier.fillMaxWidth().padding(horizontal = margin), viewport) {
                            StepCopy(shown, actions)
                            Spacer(Modifier.height(dimens.gutter * 1.5f))
                            StepPanel(shown, actions)
                        }
                    }
                }
            }
            SetupBottomBar(ui, actions, margin)
        }
    }
}

/** A column that centres its content in [viewport] and scrolls when it is taller. */
@Composable
private fun ScrollPane(modifier: Modifier, viewport: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxHeight().verticalScroll(rememberScrollState()).heightIn(min = viewport)
            .padding(vertical = CarLinkTokens.dimens.gutter),
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun SetupTopBar(step: SetupStep, actions: SetupActions, margin: Dp) {
    val dimens = CarLinkTokens.dimens
    val wide = CarLinkTokens.layout.width != WidthClass.COMPACT
    Row(
        Modifier.fillMaxWidth().heightIn(min = dimens.topBar + if (isCar) 8.dp else 0.dp).padding(horizontal = margin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CarLinkMark(Modifier.size(if (isCar) 34.dp else 24.dp))
        Spacer(Modifier.width(if (isCar) 16.dp else 12.dp))
        // Narrow windows (BYD ⅓ split) keep the mark, the meter and Close; the step names itself below.
        if (CarLinkTokens.layout.widthDp >= (if (isCar) 560.dp else 360.dp)) {
            Text(stringResource(R.string.carlink_setup_title), Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(dimens.targetGap))
        StepMeter(step, showLabel = wide)
        Spacer(Modifier.width(dimens.gutter))
        RoundGlassIconButton(R.drawable.carlink_ic_close, stringResource(R.string.carlink_setup_close), actions::exit)
    }
}

/** Five short segments, lit up to the current step, like a cluster's segment bar. */
@Composable
private fun StepMeter(step: SetupStep, showLabel: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    val total = SetupStep.entries.size
    val label = stringResource(R.string.carlink_setup_step_of, step.ordinal + 1, total)
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
        if (showLabel) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceTertiary, maxLines = 1)
            Spacer(Modifier.width(if (isCar) 16.dp else 12.dp))
        }
        SetupStep.entries.forEachIndexed { index, s ->
            if (index > 0) Spacer(Modifier.width(if (isCar) 8.dp else 5.dp))
            val color = when {
                s.ordinal < step.ordinal -> colors.success
                s == step -> scheme.primary
                else -> scheme.outline.copy(alpha = 0.45f)
            }
            Box(Modifier.size(width = if (isCar) 36.dp else if (showLabel) 24.dp else 16.dp, height = if (isCar) 6.dp else 5.dp)
                .clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun SetupBottomBar(ui: SetupUi, actions: SetupActions, margin: Dp) {
    val dimens = CarLinkTokens.dimens
    val session = sessionOf(ui)
    Row(
        Modifier.fillMaxWidth().padding(start = margin, end = margin, top = dimens.targetGap, bottom = dimens.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ui.step != SetupStep.WELCOME && ui.step != SetupStep.DONE) {
            GlassButton(stringResource(R.string.carlink_setup_back), actions::back, icon = R.drawable.carlink_ic_arrow_back)
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(dimens.targetGap))
        when (ui.step) {
            SetupStep.WELCOME -> IonButton(stringResource(R.string.carlink_setup_welcome_start), actions::next,
                icon = R.drawable.carlink_ic_arrow_forward)
            // One lit action per screen: while something needed is missing, "Allow all" is the lit one.
            SetupStep.PERMISSIONS -> if (ui.missingNeeded > 0) {
                GlassButton(stringResource(R.string.carlink_setup_continue), actions::next, icon = R.drawable.carlink_ic_arrow_forward)
            } else {
                IonButton(stringResource(R.string.carlink_setup_continue), actions::next, icon = R.drawable.carlink_ic_arrow_forward)
            }
            SetupStep.CAR -> IonButton(stringResource(R.string.carlink_setup_continue), actions::next,
                icon = R.drawable.carlink_ic_arrow_forward)
            SetupStep.PAIR -> if (session?.stage == LinkStage.CONNECTED) {
                IonButton(stringResource(R.string.carlink_setup_continue), actions::next, icon = R.drawable.carlink_ic_arrow_forward)
            } else {
                GlassButton(stringResource(R.string.carlink_setup_pair_skip), actions::next, icon = R.drawable.carlink_ic_arrow_forward)
            }
            SetupStep.DONE -> IonButton(stringResource(R.string.carlink_setup_done_action), actions::exit,
                icon = R.drawable.carlink_ic_home)
        }
    }
}

// ---- Words (start side) -------------------------------------------------------------------

private class Copy(val eyebrow: String, val color: Color, val title: androidx.compose.ui.text.AnnotatedString, val body: String?)

@Composable
private fun StepCopy(ui: SetupUi, actions: SetupActions) {
    val scheme = MaterialTheme.colorScheme
    val dimens = CarLinkTokens.dimens
    val copy = copyOf(ui)
    TellTale(copy.eyebrow, copy.color)
    Spacer(Modifier.height(if (isCar) 28.dp else 18.dp))
    val length = copy.title.length
    val style = when {
        length <= 20 -> MaterialTheme.typography.displayMedium
        else -> MaterialTheme.typography.displaySmall
    }
    Text(copy.title, style = style.copy(lineBreak = LineBreak.Heading), color = scheme.onSurface,
        modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
    if (copy.body != null) {
        Spacer(Modifier.height(if (isCar) 16.dp else 10.dp))
        Text(copy.body, Modifier.widthIn(max = if (isCar) 760.dp else 520.dp),
            style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph), color = scheme.onSurfaceVariant)
    }
    val extra: (@Composable () -> Unit)? = when (ui.step) {
        SetupStep.PERMISSIONS -> ({ PermissionSummary(ui, actions) })
        SetupStep.PAIR -> ({ PairActions(ui, actions) })
        // What the brand does stays in view while the grid on the other side scrolls.
        SetupStep.CAR -> ({ Column { CarBrandNote(ui.carBrand, ui.detectedBrand, showChoice = true) } })
        else -> null
    }
    if (extra != null) {
        Spacer(Modifier.height(dimens.gutter * 1.5f))
        extra()
    }
}

@Composable
private fun copyOf(ui: SetupUi): Copy {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    fun plain(text: String) = androidx.compose.ui.text.AnnotatedString(text)
    return when (ui.step) {
        SetupStep.WELCOME -> Copy(stringResource(R.string.carlink_setup_welcome_eyebrow), scheme.primary,
            plain(stringResource(R.string.carlink_setup_welcome_title)), stringResource(R.string.carlink_setup_welcome_body))
        SetupStep.PERMISSIONS -> Copy(stringResource(R.string.carlink_setup_permissions_eyebrow), scheme.primary,
            plain(stringResource(R.string.carlink_setup_permissions_title)), stringResource(R.string.carlink_setup_permissions_body))
        SetupStep.CAR -> Copy(stringResource(R.string.carlink_setup_car_eyebrow), scheme.primary,
            plain(stringResource(R.string.carlink_setup_car_title)), stringResource(R.string.carlink_setup_car_body))
        SetupStep.PAIR -> pairCopy(ui)
        SetupStep.DONE -> Copy(stringResource(R.string.carlink_setup_done_eyebrow), colors.success,
            plain(stringResource(R.string.carlink_setup_done_title)),
            ui.phone?.let { stringResource(R.string.carlink_setup_done_body_phone, isolate(it.name)) }
                ?: stringResource(R.string.carlink_setup_done_body_none))
    }
}

@Composable
private fun pairCopy(ui: SetupUi): Copy {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    fun plain(text: String) = androidx.compose.ui.text.AnnotatedString(text)
    val eyebrow = stringResource(R.string.carlink_setup_pair_eyebrow)
    val session = sessionOf(ui)
    val phone = ui.phone
    val phoneName = isolate(phone?.name?.ifBlank { null } ?: session?.phoneName ?: stringResource(R.string.carlink_notice_phone_other))
    val engine = session?.engine ?: phone?.kind?.engine
    return when {
        session?.stage == LinkStage.CONNECTED -> Copy(stringResource(R.string.carlink_home_eyebrow_connected), colors.success,
            plain(stringResource(R.string.carlink_setup_pair_connected_title, phoneName)),
            stringResource(R.string.carlink_setup_pair_connected_body, engine?.let { engineName(it) } ?: ""))
        // A known problem (blocked before starting, or a precise failure) in Home's words, never "try again".
        session?.stage == LinkStage.FAILED && knownProblem(session) != null -> {
            val (title, body) = failureCopy(session)
            val external = session.problem == LinkProblem.EXTERNAL_BLUETOOTH || session.problem == LinkProblem.EXTERNAL_MODULE
            Copy(stringResource(R.string.carlink_home_eyebrow_problem), if (external) colors.warning else scheme.error, plain(title), body)
        }
        session?.stage == LinkStage.FAILED -> Copy(stringResource(R.string.carlink_home_eyebrow_problem), scheme.error,
            plain(stringResource(R.string.carlink_setup_pair_failed_title)), stringResource(R.string.carlink_setup_pair_failed_body))
        ui.cable -> Copy(eyebrow, scheme.primary, plain(stringResource(R.string.carlink_setup_pair_cable_title)),
            stringResource(R.string.carlink_setup_pair_cable_body))
        ui.pairing is PairingSession.State.BluetoothOff && phone == null -> Copy(eyebrow, colors.warning,
            plain(stringResource(R.string.carlink_setup_pair_bt_off_title)), stringResource(R.string.carlink_setup_pair_bt_off_body))
        phone != null && phone.kind == PhoneKind.UNKNOWN -> Copy(stringResource(R.string.carlink_setup_pair_found), colors.success,
            plain(stringResource(R.string.carlink_setup_pair_kind_title, phoneName)), stringResource(R.string.carlink_setup_pair_kind_body))
        phone != null && phone.kind == PhoneKind.IPHONE -> Copy(stringResource(R.string.carlink_setup_pair_found_iphone), colors.success,
            plain(stringResource(R.string.carlink_setup_pair_accept_iphone)),
            stringResource(R.string.carlink_setup_pair_accept_iphone_body, phoneName))
        phone != null -> Copy(stringResource(R.string.carlink_setup_pair_found_android), colors.success,
            plain(stringResource(R.string.carlink_setup_pair_accept_android)),
            stringResource(R.string.carlink_setup_pair_accept_android_body, phoneName))
        ui.pairing is PairingSession.State.Pairing -> Copy(eyebrow, scheme.primary,
            plain((ui.pairing as PairingSession.State.Pairing).phoneName?.let { stringResource(R.string.carlink_setup_pair_pairing_title, isolate(it)) }
                ?: stringResource(R.string.carlink_setup_pair_pairing_title_unnamed)),
            stringResource(R.string.carlink_setup_pair_pairing_body))
        else -> {
            val name = ui.carName
            val title = if (name == null) plain(stringResource(R.string.carlink_setup_pair_waiting_title_unnamed)) else {
                // The name the driver looks for on the phone is lit, so the eye finds it at once.
                val text = stringResource(R.string.carlink_setup_pair_waiting_title, name)
                val at = text.indexOf(name)
                buildAnnotatedString {
                    append(text)
                    if (at >= 0) addStyle(SpanStyle(color = scheme.primary), at, at + name.length)
                }
            }
            Copy(eyebrow, scheme.primary, title, stringResource(R.string.carlink_setup_pair_waiting_body))
        }
    }
}

/** The session's problem when it says more than "the engine failed" (Home has words and a fix for it), else null. */
private fun knownProblem(session: LinkState): LinkProblem? = session.problem?.takeIf { it != LinkProblem.ENGINE }

/** Allow all, and how many items are still missing. */
@Composable
private fun PermissionSummary(ui: SetupUi, actions: SetupActions) {
    val colors = CarLinkTokens.colors
    val all = ui.missingNeeded == 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(if (all) R.drawable.carlink_ic_check_circle_filled else R.drawable.carlink_ic_lock,
            tint = if (all) colors.success else colors.warning, size = if (isCar) 48.dp else 36.dp)
        Spacer(Modifier.width(if (isCar) 16.dp else 12.dp))
        Text(if (all) stringResource(R.string.carlink_setup_permissions_done)
            else stringResource(R.string.carlink_setup_permissions_missing, ui.missing),
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
    if (ui.missing > 0) {
        Spacer(Modifier.height(CarLinkTokens.dimens.gutter))
        if (all) GlassButton(stringResource(R.string.carlink_setup_permissions_all), actions::allowAll, icon = R.drawable.carlink_ic_check)
        else IonButton(stringResource(R.string.carlink_setup_permissions_all), actions::allowAll, icon = R.drawable.carlink_ic_check)
    }
}

/** The pairing step's actions under its words: the fix of the moment, and the cable as a way out. */
@Composable
private fun PairActions(ui: SetupUi, actions: SetupActions) {
    val dimens = CarLinkTokens.dimens
    val session = sessionOf(ui)
    val problem = session?.takeIf { it.stage == LinkStage.FAILED }?.let(::knownProblem)
    val fix = oneTapFix(problem)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(dimens.targetGap), verticalArrangement = Arrangement.spacedBy(dimens.targetGap),
        itemVerticalAlignment = Alignment.CenterVertically) {
        when {
            session?.stage == LinkStage.CONNECTED -> Unit
            // Retrying cannot help on this stereo; the cable button below can.
            problem == LinkProblem.EXTERNAL_BLUETOOTH || problem == LinkProblem.EXTERNAL_MODULE -> Unit
            // The fix itself (Location, a radio, a permission); coming back checks again.
            problem != null && fix != null -> IonButton(stringResource(fix.first), { actions.fix(problem) }, icon = fix.second)
            session?.stage == LinkStage.FAILED ->
                IonButton(stringResource(R.string.carlink_home_try_again), actions::retry, icon = R.drawable.carlink_ic_refresh)
            ui.cable -> Unit
            ui.pairing is PairingSession.State.BluetoothOff && ui.phone == null ->
                IonButton(stringResource(R.string.carlink_setup_pair_bt_on), actions::turnOnBluetooth, icon = R.drawable.carlink_ic_bluetooth)
            ui.phone != null && ui.phone.kind == PhoneKind.UNKNOWN -> {
                IonButton(stringResource(R.string.carlink_home_kind_iphone), { actions.setKind(PhoneKind.IPHONE) },
                    icon = R.drawable.carlink_ic_phone_iphone)
                GlassButton(stringResource(R.string.carlink_home_kind_android), { actions.setKind(PhoneKind.ANDROID) },
                    icon = R.drawable.carlink_ic_phone_android)
            }
        }
        if (session?.stage != LinkStage.CONNECTED) {
            if (ui.cable) {
                GlassButton(stringResource(R.string.carlink_setup_pair_wireless), { actions.useCable(false) },
                    icon = R.drawable.carlink_ic_bluetooth)
            } else {
                GlassButton(stringResource(R.string.carlink_setup_pair_cable), { actions.useCable(true) }, icon = R.drawable.carlink_ic_usb)
            }
        }
    }
}

// ---- Instruments (end side) ---------------------------------------------------------------

@Composable
private fun StepPanel(ui: SetupUi, actions: SetupActions) {
    when (ui.step) {
        SetupStep.WELCOME -> PlanCard()
        SetupStep.PERMISSIONS -> PermissionList(ui, actions)
        SetupStep.CAR -> Column { CarBrandPicker(ui.carBrand, ui.detectedBrand, actions::setCarBrand, note = false) }
        SetupStep.PAIR -> PairPanel(ui, actions)
        SetupStep.DONE -> DoneSummary(ui)
    }
}

/** The three things the setup will do, as numbered instrument rows. */
@Composable
private fun PlanCard() {
    val rows = listOf(
        Triple(R.string.carlink_setup_plan_access, R.string.carlink_setup_plan_access_detail, R.drawable.carlink_ic_lock),
        Triple(R.string.carlink_setup_plan_car, R.string.carlink_setup_plan_car_detail, R.drawable.carlink_ic_directions_car),
        Triple(R.string.carlink_setup_plan_pair, R.string.carlink_setup_plan_pair_detail, R.drawable.carlink_ic_bluetooth),
    )
    GlassGroup {
        rows.forEachIndexed { index, (title, detail, icon) ->
            if (index > 0) GroupDivider()
            SettingsRow(stringResource(title), description = stringResource(detail), icon = icon) {
                StepNumber(index + 1)
            }
        }
    }
}

/** A step number on a faint Ion disc. */
@Composable
private fun StepNumber(n: Int) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.size(if (isCar) 48.dp else 34.dp).clip(CircleShape).background(scheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center) {
        Text(n.toString(), style = MaterialTheme.typography.titleMedium, color = scheme.primary)
    }
}

@Composable
private fun PermissionList(ui: SetupUi, actions: SetupActions) {
    val colors = CarLinkTokens.colors
    val scheme = MaterialTheme.colorScheme
    GlassGroup {
        ui.permissions.forEachIndexed { index, p ->
            if (index > 0) GroupDivider()
            SettingsRow(
                stringResource(p.item.title),
                description = stringResource(p.item.purpose),
                icon = p.item.icon,
                onClick = if (p.granted) null else ({ actions.allow(p.item) }),
                role = androidx.compose.ui.semantics.Role.Button,
                tags = when {
                    p.granted -> null
                    p.item.need == Need.RECOMMENDED -> ({ Tag(stringResource(R.string.carlink_setup_permission_recommended), scheme.primary) })
                    p.item.need == Need.OPTIONAL -> ({ Tag(stringResource(R.string.carlink_setup_permission_optional), scheme.onSurfaceVariant) })
                    else -> null
                },
            ) {
                if (p.granted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.carlink_ic_check_circle_filled), null,
                            Modifier.size(CarLinkTokens.dimens.iconTertiary + 6.dp), tint = colors.success)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.carlink_setup_permission_allowed), style = MaterialTheme.typography.labelLarge,
                            color = colors.success, maxLines = 1)
                    }
                } else {
                    Text(stringResource(R.string.carlink_setup_permission_allow), style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PairPanel(ui: SetupUi, actions: SetupActions) {
    val dimens = CarLinkTokens.dimens
    val session = sessionOf(ui)
    Column(verticalArrangement = Arrangement.spacedBy(dimens.gutter)) {
        if (ui.cable) CableCard(session) else NameCard(ui, actions)
        // The first cable connection's two one-time prompts, said before they appear.
        if (ui.cable && session?.stage != LinkStage.CONNECTED) {
            NoticeLine(stringResource(R.string.carlink_setup_pair_cable_prompts), R.drawable.carlink_ic_info)
        }
        // On a stereo whose Bluetooth apps cannot use, say so before the driver pairs (the failure copy says it after).
        val externalFailure = session?.stage == LinkStage.FAILED && session.problem == com.carlink.core.core.LinkProblem.EXTERNAL_BLUETOOTH
        if (ui.wirelessUnavailable && !ui.cable && !externalFailure && session?.stage != LinkStage.CONNECTED) {
            NoticeLine(stringResource(R.string.carlink_setup_pair_external_bt), R.drawable.carlink_ic_usb)
        }
        if (ui.phone != null || session != null) FoundCard(ui, session)
        if (!ui.cable && ui.phone == null && ui.knownPhones.isNotEmpty()) KnownPhones(ui.knownPhones, actions)
    }
}

/** This screen's Bluetooth name, large, and whether phones can see it right now. */
@Composable
private fun NameCard(ui: SetupUi, actions: SetupActions) {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    val dimens = CarLinkTokens.dimens
    val off = ui.pairing is PairingSession.State.BluetoothOff
    // Once a phone is found the name has done its job: it steps down so the connection gets the room.
    val compact = ui.phone != null
    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(if (compact) (if (isCar) 24.dp else 16.dp)
        else if (isCar) 36.dp else 24.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(if (off) R.drawable.carlink_ic_bluetooth_disabled else R.drawable.carlink_ic_bluetooth_searching),
                    null, Modifier.size(dimens.iconTertiary + 4.dp), tint = colors.onSurfaceTertiary)
                Spacer(Modifier.width(10.dp))
                Eyebrow(stringResource(R.string.carlink_setup_pair_name_label))
            }
            Spacer(Modifier.height(if (compact) 6.dp else if (isCar) 18.dp else 12.dp))
            Text(ui.carName?.let(::isolate) ?: stringResource(R.string.carlink_setup_pair_name_unknown),
                style = when {
                    ui.carName == null -> MaterialTheme.typography.headlineSmall
                    compact -> MaterialTheme.typography.headlineMedium
                    else -> MaterialTheme.typography.displayMedium.copy(lineBreak = LineBreak.Heading)
                },
                color = if (ui.carName != null) scheme.onSurface else scheme.onSurfaceVariant)
            if (!off && !compact) {
                Spacer(Modifier.height(if (isCar) 24.dp else 16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(dimens.gutter), verticalArrangement = Arrangement.spacedBy(dimens.targetGap),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    LitStatus(stringResource(if (ui.visible) R.string.carlink_setup_pair_visible else R.string.carlink_setup_pair_not_visible),
                        if (ui.visible) colors.success else colors.warning)
                    if (!ui.visible) GlassButton(stringResource(R.string.carlink_setup_pair_make_visible), actions::makeVisible,
                        icon = R.drawable.carlink_ic_bluetooth_searching)
                }
            }
        }
    }
}

/** A lit dot and a sentence, the tell-tale's quieter sibling. */
@Composable
private fun LitStatus(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (isCar) 10.dp else 8.dp).drawBehind {
            drawCircle(color.copy(alpha = 0.28f), radius = size.minDimension)
            drawCircle(color)
        })
        Spacer(Modifier.width(if (isCar) 14.dp else 10.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CableCard(session: LinkState?) {
    val scheme = MaterialTheme.colorScheme
    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(if (isCar) 36.dp else 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(R.drawable.carlink_ic_usb, tint = scheme.primary, size = if (isCar) 88.dp else 64.dp)
            Spacer(Modifier.width(if (isCar) 28.dp else 18.dp))
            Text(session?.phoneName?.let(::isolate) ?: stringResource(R.string.carlink_setup_pair_cable_waiting),
                style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
        }
    }
}

/** The phone that paired (iPhone → CarPlay, Android → Android Auto) and its first connection, step by step. */
@Composable
private fun FoundCard(ui: SetupUi, session: LinkState?) {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    val phone = ui.phone
    val kind = phone?.kind ?: when (session?.engine) {
        Engine.CARPLAY -> PhoneKind.IPHONE
        Engine.ANDROID_AUTO -> PhoneKind.ANDROID
        null -> PhoneKind.UNKNOWN
    }
    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(if (isCar) 32.dp else 20.dp)) {
        Column {
            if (phone != null || session?.phoneName != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(phoneIcon(kind), tint = if (kind != PhoneKind.UNKNOWN) colors.success else null,
                        size = if (isCar) 64.dp else 44.dp)
                    Spacer(Modifier.width(if (isCar) 20.dp else 14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(isolate(phone?.name?.ifBlank { null } ?: session?.phoneName ?: ""), style = MaterialTheme.typography.titleLarge,
                            color = scheme.onSurface)
                        if (kind != PhoneKind.UNKNOWN) {
                            Text(stringResource(if (kind == PhoneKind.IPHONE) R.string.carlink_setup_pair_found_iphone
                                else R.string.carlink_setup_pair_found_android), style = MaterialTheme.typography.bodyMedium,
                                color = colors.success)
                        }
                    }
                }
            }
            if (session != null) {
                Spacer(Modifier.height(if (isCar) 24.dp else 16.dp))
                val active = when (session.stage) {
                    LinkStage.CONNECTED -> 4
                    else -> (session.step - 1).coerceIn(0, 3)
                }
                ProgressLine(stepNames(), active, failed = session.stage == LinkStage.FAILED,
                    still = session.notice?.kind == com.carlink.core.core.LinkNoticeKind.NOT_ANSWERING)
                noticeText(session)?.let {
                    Spacer(Modifier.height(if (isCar) 18.dp else 12.dp))
                    NoticeLine(it, noticeIcon(session))
                }
            }
        }
    }
}

@Composable
private fun stepNames() = listOf(
    stringResource(R.string.carlink_home_step_reaching), stringResource(R.string.carlink_home_step_answered),
    stringResource(R.string.carlink_home_step_linking), stringResource(R.string.carlink_home_step_picture),
)

/** Phones paired before: one tap starts their connection instead of pairing again. */
@Composable
private fun KnownPhones(phones: List<PairedPhone>, actions: SetupActions) {
    val dimens = CarLinkTokens.dimens
    Column {
        Eyebrow(stringResource(R.string.carlink_setup_pair_known), Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(dimens.targetGap))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(dimens.targetGap), verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
            phones.forEach { phone ->
                GlassButton(stringResource(R.string.carlink_setup_pair_connect_known, isolate(phone.name)), { actions.connectKnown(phone) },
                    icon = phoneIcon(phone.kind))
            }
        }
    }
}

@Composable
private fun DoneSummary(ui: SetupUi) {
    val colors = CarLinkTokens.colors
    val phone = ui.phone
    GlassGroup {
        SettingsRow(stringResource(R.string.carlink_setup_done_phone), icon = phone?.let { phoneIcon(it.kind) } ?: R.drawable.carlink_ic_smartphone,
            value = phone?.let { p -> stringResource(R.string.carlink_setup_done_phone_value, isolate(p.name),
                p.kind.engine?.let { engineName(it) } ?: "") } ?: stringResource(R.string.carlink_setup_done_phone_none))
        GroupDivider()
        SettingsRow(stringResource(R.string.carlink_setup_done_car), icon = R.drawable.carlink_ic_directions_car,
            value = carBrandLabel(ui.carBrand ?: ui.detectedBrand))
        GroupDivider()
        SettingsRow(stringResource(R.string.carlink_setup_done_access), icon = R.drawable.carlink_ic_lock,
            value = if (ui.missing == 0) stringResource(R.string.carlink_setup_done_access_all)
                else stringResource(R.string.carlink_setup_done_access_missing, ui.missing)) {
            if (ui.missing == 0) Icon(painterResource(R.drawable.carlink_ic_check_circle_filled), null,
                Modifier.size(CarLinkTokens.dimens.iconTertiary + 6.dp), tint = colors.success)
        }
    }
}
