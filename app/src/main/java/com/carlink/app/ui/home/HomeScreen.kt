package com.carlink.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.app.R
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.ConnectionPrefs
import com.carlink.app.ui.HomeState
import com.carlink.app.ui.NetworkPreview
import com.carlink.app.ui.Route
import com.carlink.app.ui.components.CarLinkMark
import com.carlink.app.ui.components.TertiaryButton
import com.carlink.app.ui.components.engineName
import com.carlink.app.ui.components.formatElapsed
import com.carlink.app.ui.components.isolate
import com.carlink.app.ui.components.phoneIcon
import com.carlink.app.ui.components.rememberElapsedMillis
import com.carlink.app.ui.premium.Atmosphere
import com.carlink.app.ui.premium.DialReading
import com.carlink.app.ui.premium.GlassButton
import com.carlink.app.ui.premium.IonButton
import com.carlink.app.ui.premium.LinkDial
import com.carlink.app.ui.premium.ProgressLine
import com.carlink.app.ui.premium.RoundGlassIconButton
import com.carlink.app.ui.premium.TellTale
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.CarMotion
import com.carlink.app.ui.theme.LocalReduceMotion
import com.carlink.app.ui.theme.UiProfile
import com.carlink.app.ui.theme.WidthClass
import com.carlink.core.connection.DETAIL_PHONE_NOT_ANSWERING
import com.carlink.core.core.Engine
import com.carlink.core.core.LinkProblem
import com.carlink.core.core.LinkStage
import com.carlink.core.core.LinkState
import com.carlink.core.core.PhoneKind
import com.carlink.core.core.Transport
import com.carlink.core.detect.BluetoothAvailability
import com.carlink.core.detect.PairedPhone
import com.carlink.core.settings.ConnectionMethod
import com.carlink.core.settings.WifiDetails
import com.carlink.core.settings.WirelessPath
import com.carlink.core.settings.isValid

/** The modes of Home's stage (design/RESEARCH_PREMIUM_UX.md §7.3). */
internal enum class HomeMode {
    PERMISSION, BLUETOOTH_OFF, READY_EMPTY, READY_PHONE, CONNECTING,
    /** A wireless attempt the phone has not answered for a while: calm, caution, nothing animates. */
    NOT_ANSWERING,
    CONNECTED, FAILED,
}

internal fun homeMode(home: HomeState, permissionsNeeded: Boolean): HomeMode = when {
    home.link.stage == LinkStage.CONNECTED -> HomeMode.CONNECTED
    phoneSilent(home.link) -> HomeMode.NOT_ANSWERING
    home.link.stage == LinkStage.WAITING || home.link.stage == LinkStage.CONNECTING -> HomeMode.CONNECTING
    home.link.stage == LinkStage.FAILED -> HomeMode.FAILED
    permissionsNeeded -> HomeMode.PERMISSION
    home.bluetooth == BluetoothAvailability.OFF -> HomeMode.BLUETOOTH_OFF
    home.defaultPhone != null -> HomeMode.READY_PHONE
    else -> HomeMode.READY_EMPTY
}

/** The phone has not answered for a while (wireless or USB), and the attempt is still on its first step. */
private fun phoneSilent(link: LinkState): Boolean =
    (link.stage == LinkStage.WAITING || link.stage == LinkStage.CONNECTING) && link.step <= 1 &&
        (link.detail == DETAIL_PHONE_NOT_ANSWERING || link.notice?.kind == com.carlink.core.core.LinkNoticeKind.NOT_ANSWERING)

/** Everything Home needs, so previews and the screenshot gallery can render it without a ViewModel. */
internal data class HomeUi(
    val home: HomeState,
    val connection: ConnectionPrefs,
    val network: NetworkPreview?,
    val permissionsNeeded: Boolean,
    /** How the default phone usually connects (from its connection record), or null before it has one. */
    val usual: PhoneUsual? = null,
)

internal interface HomeActions {
    fun requestPermissions() {}
    fun openBluetoothSettings() {}
    /** Guided first-time pairing (the setup's pairing step); Android's Bluetooth settings where there is none. */
    fun pairPhone() = openBluetoothSettings()
    fun connect(phone: PairedPhone) {}
    fun switchTo(phone: PairedPhone) {}
    fun setPhoneKind(phone: PairedPhone, kind: PhoneKind) {}
    fun retry() {}
    fun disconnect() {}
    fun returnToProjection() {}
    fun resumeAutoConnect() {}
    fun saveSameWifi(ssid: String, password: String) {}
    fun saveCarHotspot(ssid: String, password: String) {}
    fun setConnectionMethod(method: ConnectionMethod) {}
    /** The one-tap fix for [problem]: turn a radio on, open a permission screen, switch path. */
    fun fix(problem: LinkProblem) {}
    fun navigate(route: Route) {}
    /** "Report a problem on GitHub", offered on the failure states. */
    fun reportProblem() {}
    /** CarPlay sound but no picture ([LinkNoticeKind.NO_PICTURE]): turn on Direct video output and reconnect. */
    fun turnOnDirectVideo() {}
}

@Composable
fun HomeScreen(vm: CarLinkViewModel, navigate: (Route) -> Unit) {
    val home by vm.home.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    val permissionsNeeded by vm.permissionsNeeded.collectAsStateWithLifecycle()
    val usual by vm.usual.collectAsStateWithLifecycle()
    val fixProblem = rememberProblemFix(vm)
    val reportProblem = com.carlink.app.ui.diagnostics.rememberReportProblem(vm)
    val actions = remember(vm) {
        object : HomeActions {
            override fun requestPermissions() = fixProblem(LinkProblem.PERMISSIONS)
            override fun openBluetoothSettings() = vm.openBluetoothSettings()
            override fun pairPhone() = navigate(Route.SETUP_PAIR)
            override fun connect(phone: PairedPhone) = vm.connect(phone)
            override fun switchTo(phone: PairedPhone) = vm.switchTo(phone)
            override fun setPhoneKind(phone: PairedPhone, kind: PhoneKind) = vm.setPhoneKind(phone, kind)
            override fun retry() = vm.retry()
            override fun disconnect() = vm.disconnect()
            override fun returnToProjection() = vm.returnToProjection()
            override fun resumeAutoConnect() = vm.resumeAutoConnect()
            override fun saveSameWifi(ssid: String, password: String) = vm.saveSameWifi(ssid, password)
            override fun saveCarHotspot(ssid: String, password: String) = vm.saveCarHotspot(ssid, password)
            override fun setConnectionMethod(method: ConnectionMethod) = vm.updateSettings { it.copy(connectionMethod = method) }
            override fun fix(problem: LinkProblem) = fixProblem(problem)
            override fun navigate(route: Route) = navigate(route)
            override fun reportProblem() = reportProblem()
            override fun turnOnDirectVideo() = vm.turnOnDirectVideo()
        }
    }
    HomeContent(HomeUi(home, connection, network, permissionsNeeded, usual), actions)
    // A second USB phone: the floating overlay asks over any screen; without the overlay
    // permission Home asks itself.
    val pendingSwitch by vm.pendingSwitch.collectAsStateWithLifecycle()
    pendingSwitch?.let { request ->
        val context = androidx.compose.ui.platform.LocalContext.current
        if (android.os.Build.VERSION.SDK_INT < 23 || !android.provider.Settings.canDrawOverlays(context)) {
            SwitchPhoneDialog(request.phoneName ?: "", home.link.engine, request.engine, request.transport, onSwitch = vm::acceptSwitch, onKeep = vm::declineSwitch)
        }
    }
}

/**
 * The one-tap fix for a [LinkProblem], shared by Home and the setup's pairing step: the
 * permission dialog, Android's VPN consent (which only stays open when started for a result from
 * an activity), or the system screen from [CarLinkViewModel.fixIntent]. Coming back checks again,
 * so the driver does not have to tap Try again after fixing it.
 */
@Composable
internal fun rememberProblemFix(vm: CarLinkViewModel): (LinkProblem) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshPhones()
        vm.recheckAfterFix()
    }
    val screen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.recheckAfterFix() }
    return remember(vm) {
        { problem: LinkProblem ->
            when (problem) {
                LinkProblem.PERMISSIONS -> permissions.launch(vm.missingPermissions().toTypedArray())
                LinkProblem.VPN_CONSENT -> android.net.VpnService.prepare(context)?.let { screen.launch(it) } ?: vm.retry()
                else -> vm.fixIntent(problem)?.let { intent -> runCatching { screen.launch(intent) }.onFailure { vm.fix(problem) } }
                    ?: vm.fix(problem)
            }
        }
    }
}

/** The label and icon of [problem]'s one-tap fix (a switch, a permission, a system screen), or null when it has none. */
internal fun oneTapFix(problem: LinkProblem?): Pair<Int, Int>? = when (problem) {
    LinkProblem.BLUETOOTH_OFF, LinkProblem.WIFI_OFF -> R.string.carlink_home_fix_turn_on to R.drawable.carlink_ic_check
    LinkProblem.PERMISSIONS, LinkProblem.OVERLAY, LinkProblem.VPN_CONSENT -> R.string.carlink_home_fix_allow to R.drawable.carlink_ic_check
    LinkProblem.LOCATION_OFF -> R.string.carlink_home_fix_open_settings to R.drawable.carlink_ic_settings
    LinkProblem.SAME_WIFI_NOT_JOINED -> R.string.carlink_home_fix_open_wifi to R.drawable.carlink_ic_wifi
    LinkProblem.CAR_HOTSPOT_OFF -> R.string.carlink_home_fix_open_hotspot to R.drawable.carlink_ic_wifi
    else -> null
}

@Composable
private fun SwitchPhoneDialog(phoneName: String, running: Engine?, newEngine: Engine, newTransport: Transport,
                              onSwitch: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.carlink_switch_title, phoneName)) },
        text = {
            Text(stringResource(R.string.carlink_switch_how, engineName(newEngine),
                stringResource(if (newTransport == Transport.USB) R.string.carlink_switch_via_usb else R.string.carlink_switch_via_wireless)) +
                "\n" + stringResource(R.string.carlink_switch_body, running?.let { engineName(it) } ?: ""))
        },
        confirmButton = { IonButton(stringResource(R.string.carlink_switch_confirm), onSwitch) },
        dismissButton = { GlassButton(stringResource(R.string.carlink_switch_keep), onKeep) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** "How to connect" on a stereo where wireless cannot start: the cable, step by step. */
@Composable
private fun CableHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.carlink_ic_usb), null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.carlink_home_cable_title)) },
        text = { Text(stringResource(R.string.carlink_home_cable_body), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { IonButton(stringResource(R.string.carlink_home_cable_ok), onDismiss) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** The state's light: Ion while ready or connecting, Live when connected, Fault, Caution, or neutral. */
@Composable
private fun modeColor(mode: HomeMode): Color = when (mode) {
    HomeMode.CONNECTED -> CarLinkTokens.colors.success
    HomeMode.CONNECTING, HomeMode.READY_PHONE -> MaterialTheme.colorScheme.primary
    HomeMode.FAILED -> MaterialTheme.colorScheme.error
    HomeMode.PERMISSION, HomeMode.BLUETOOTH_OFF, HomeMode.NOT_ANSWERING -> CarLinkTokens.colors.warning
    HomeMode.READY_EMPTY -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Where the dial sits, as fractions of the window, and its radius in px: the stage light follows it. */
private data class DialSpot(val center: Offset, val radius: Float)

/**
 * Home, "Instrument" (design/RESEARCH_HOME_V2.md §4): a link dial as the focal object, one
 * tell-tale, one sentence, one action, and the status bar docked to the bottom edge. On large
 * handhelds the theme scales the whole app to read like the car canvas (`largeHandheldScale`).
 */
@Composable
internal fun HomeContent(ui: HomeUi, actions: HomeActions) {
    var phonesOpen by rememberSaveable { mutableStateOf(false) }
    var networkOpen by rememberSaveable { mutableStateOf(false) }
    HomeStageScreen(ui, actions, onPhones = { phonesOpen = true }, onNetwork = { networkOpen = true })
    if (phonesOpen) PhonesSheet(ui, actions, onDismiss = { phonesOpen = false })
    if (networkOpen) NetworkSheet(ui, actions, onDismiss = { networkOpen = false })
}

@Composable
private fun HomeStageScreen(ui: HomeUi, actions: HomeActions, onPhones: () -> Unit, onNetwork: () -> Unit) {
    val mode = homeMode(ui.home, ui.permissionsNeeded)
    val layout = CarLinkTokens.layout
    val dimens = CarLinkTokens.dimens
    val car = CarLinkTokens.profile == UiProfile.CAR
    val dark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3f < 0.5f }
    var spot by remember { mutableStateOf<DialSpot?>(null) }
    val light = modeColor(mode).let { if (mode == HomeMode.READY_EMPTY) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else it }
    val fallbackCenter = if (layout.isLandscape) Offset(0.72f, 0.45f) else Offset(0.5f, 0.3f)

    Atmosphere(
        Modifier.fillMaxSize(),
        glow = light.copy(alpha = light.alpha * (if (dark) 0.85f else 0.6f) * (if (mode == HomeMode.FAILED) 0.55f else 1f)),
        glowCenter = spot?.center ?: fallbackCenter,
        glowRadius = spot?.radius?.times(2.1f),
        lightCenter = (spot?.center ?: fallbackCenter).let { Offset(it.x, it.y - 0.35f) },
        floor = true,
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopBar(onSettings = { actions.navigate(Route.SETTINGS) })
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val landscape = maxWidth > maxHeight && layout.width >= WidthClass.MEDIUM
                val compact = layout.width == WidthClass.COMPACT
                val margin = if (compact) dimens.compactMargin else dimens.screenMargin
                val taps = StatusTaps(onBluetooth = actions::openBluetoothSettings, onNetwork = onNetwork, onPhones = onPhones)
                HomeStage(
                    Modifier.fillMaxSize().padding(start = margin, end = margin, bottom = if (car) dimens.gutter else dimens.targetGap * 2),
                    landscape = landscape,
                    statusGap = if (car) dimens.gutter * 1.5f else dimens.gutter,
                    columnGap = dimens.gutter * 2,
                    dialMin = if (car) 300.dp else 176.dp,
                    dialMax = if (car) 560.dp else 520.dp,
                    copy = { dense, steps -> HeroText(mode, ui, actions, dense, steps, centered = !landscape) },
                    panel = {
                        val items = statusItems(mode, ui, taps)
                        if (compact) StatusRows(items) else StatusPanel(items)
                    },
                    strip = { StatusStrip(statusItems(mode, ui, taps)) },
                    dial = {
                        HomeDial(mode, ui, Modifier.onGloballyPositioned { c ->
                            val root = c.findRootCoordinates().size
                            val pos = c.localToRoot(Offset(c.size.width / 2f, c.size.height / 2f))
                            if (root.width > 0 && root.height > 0) {
                                val next = DialSpot(Offset(pos.x / root.width, pos.y / root.height), c.size.width / 2f)
                                if (next != spot) spot = next
                            }
                        })
                    },
                )
            }
        }
    }
}

@Composable
private fun TopBar(onSettings: () -> Unit) {
    val dimens = CarLinkTokens.dimens
    val car = CarLinkTokens.profile == UiProfile.CAR
    val margin = if (CarLinkTokens.layout.width == WidthClass.COMPACT) dimens.compactMargin else dimens.screenMargin
    Row(
        Modifier.fillMaxWidth().height(dimens.topBar + if (car) 8.dp else 0.dp).padding(horizontal = margin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CarLinkMark(Modifier.size(if (car) 34.dp else 24.dp))
        Spacer(Modifier.width(if (car) 16.dp else 12.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.weight(1f))
        RoundGlassIconButton(R.drawable.carlink_ic_settings, stringResource(R.string.carlink_home_settings), onSettings)
    }
}

/** Which of the four attempt steps (Found, Identified, Linked, Picture) is in progress. */
private fun activeStep(link: LinkState): Int = when {
    link.step <= 1 -> 0
    link.step == 2 -> 1
    link.step == 3 -> 2
    else -> 3
}

@Composable
private fun stepNames() = listOf(
    stringResource(R.string.carlink_home_step_reaching), stringResource(R.string.carlink_home_step_answered),
    stringResource(R.string.carlink_home_step_linking), stringResource(R.string.carlink_home_step_picture),
)

/** The link dial with the state's glyph and reading in its core. */
@Composable
private fun HomeDial(mode: HomeMode, ui: HomeUi, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    val link = ui.home.link
    val color = modeColor(mode)
    val reading = when (mode) {
        HomeMode.CONNECTING -> DialReading(color, segments = 4, active = activeStep(link))
        // Held at the first step: its slot is marked, but nothing charges while the phone is silent.
        HomeMode.NOT_ANSWERING -> DialReading(color, segments = 4, active = 0, still = true)
        HomeMode.CONNECTED -> DialReading(color, progress = 1f)
        HomeMode.FAILED -> DialReading(color, progress = 1f, dimmed = true)
        HomeMode.READY_PHONE -> DialReading(color, armed = true)
        else -> DialReading(color)
    }
    val kind = when (link.engine) {
        Engine.CARPLAY -> PhoneKind.IPHONE
        Engine.ANDROID_AUTO -> PhoneKind.ANDROID
        null -> ui.home.defaultPhone?.kind ?: PhoneKind.UNKNOWN
    }
    val engine = link.engine?.let { engineName(it) } ?: ui.home.defaultPhone?.kind?.engine?.let { engineName(it) }
    LinkDial(reading, modifier) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val side = maxWidth
            val glyph = side * 0.34f
            val small = side < 150.dp
            val caption = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall
            AnimatedContent(mode, transitionSpec = { fadeIn(tween(CarMotion.MEDIUM)) togetherWith fadeOut(tween(CarMotion.MEDIUM)) },
                label = "dial") { state ->
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(side * 0.05f)) {
                    when (state) {
                        HomeMode.READY_EMPTY, HomeMode.PERMISSION -> CarLinkMark(Modifier.size(glyph))
                        HomeMode.BLUETOOTH_OFF -> Icon(painterResource(R.drawable.carlink_ic_bluetooth_disabled), null,
                            Modifier.size(glyph), tint = colors.warning)
                        HomeMode.READY_PHONE, HomeMode.CONNECTING, HomeMode.NOT_ANSWERING -> Icon(painterResource(phoneIcon(kind)),
                            null, Modifier.size(glyph), tint = when (state) {
                                HomeMode.CONNECTING -> scheme.primary
                                HomeMode.NOT_ANSWERING -> colors.warning
                                else -> scheme.onSurface
                            })
                        HomeMode.CONNECTED -> {
                            val elapsed by rememberElapsedMillis(ui.home.connectedSince)
                            Icon(painterResource(R.drawable.carlink_ic_check_circle_filled), null, Modifier.size(glyph * 0.55f),
                                tint = colors.success)
                            Text(formatElapsed(elapsed), style = (if (small) MaterialTheme.typography.headlineMedium
                                else MaterialTheme.typography.displaySmall).tabular(), color = scheme.onSurface, maxLines = 1)
                        }
                        HomeMode.FAILED -> Icon(painterResource(R.drawable.carlink_ic_error_filled), null, Modifier.size(glyph),
                            tint = scheme.error)
                    }
                    val showEngine = engine != null && state != HomeMode.READY_EMPTY && state != HomeMode.PERMISSION &&
                        state != HomeMode.BLUETOOTH_OFF
                    if (showEngine) {
                        Text(engine!!, style = caption, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
                    }
                    if (state == HomeMode.CONNECTED && link.transport != null && !small) {
                        // The instrument also says how the phone is linked: a plug or a wireless glyph.
                        val usb = link.transport == Transport.USB
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(com.carlink.app.ui.premium.transportIcon(link.transport, ui.network?.path)), null,
                                Modifier.size(side * 0.08f), tint = colors.success)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (usb) R.string.carlink_transport_cable else R.string.carlink_transport_wireless)
                                .uppercase(java.util.Locale.ROOT), style = MaterialTheme.typography.labelSmall, color = colors.success,
                                maxLines = 1)
                        }
                    }
                    if (state == HomeMode.CONNECTING || state == HomeMode.NOT_ANSWERING) {
                        val step = if (state == HomeMode.NOT_ANSWERING) 0 else activeStep(link)
                        Text(stepNames()[step].uppercase(java.util.Locale.ROOT), style = MaterialTheme.typography.labelSmall,
                            color = if (state == HomeMode.NOT_ANSWERING) colors.warning else scheme.primary,
                            textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
            }
        }
    }
}

/** Tabular figures so a running timer does not jitter. */
private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

/** Tell-tale, headline, body and actions for the current mode. */
@Composable
private fun HeroText(mode: HomeMode, ui: HomeUi, actions: HomeActions, dense: Boolean, steps: Boolean, centered: Boolean) {
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(mode, transitionSpec = {
        val duration = if (reduceMotion) 0 else CarMotion.MEDIUM
        (fadeIn(tween(duration)) + slideInVertically(tween(duration, easing = CarMotion.EmphasizedDecelerate)) { it / 12 }) togetherWith
            fadeOut(tween(duration / 2))
    }, label = "hero", contentAlignment = if (centered) Alignment.TopCenter else Alignment.TopStart) { state ->
        Column(Modifier.widthIn(max = 900.dp).then(if (centered) Modifier.fillMaxWidth() else Modifier),
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
            HeroCopy(state, ui, actions, dense, steps, centered)
        }
    }
}

@Composable
private fun ColumnScope.HeroCopy(mode: HomeMode, ui: HomeUi, actions: HomeActions, dense: Boolean, steps: Boolean,
                                 centered: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    val dimens = CarLinkTokens.dimens
    val car = CarLinkTokens.profile == UiProfile.CAR
    // Dense: the variant Home falls back to when the window is short (tighter rhythm, no pill, no card body).
    val sectionGap = if (dense) dimens.gutter else dimens.gutter * 1.5f
    val align = if (centered) TextAlign.Center else TextAlign.Start
    val link = ui.home.link
    val engine = link.engine?.let { engineName(it) } ?: ""
    val phone = ui.home.defaultPhone
    val phoneName = isolate(link.phoneName ?: phone?.name ?: stringResource(R.string.carlink_home_kind_iphone))

    val eyebrow = when (mode) {
        HomeMode.PERMISSION -> R.string.carlink_home_eyebrow_setup
        HomeMode.CONNECTING -> R.string.carlink_home_eyebrow_connecting
        HomeMode.NOT_ANSWERING -> R.string.carlink_home_eyebrow_not_answering
        HomeMode.CONNECTED -> R.string.carlink_home_eyebrow_connected
        HomeMode.FAILED, HomeMode.BLUETOOTH_OFF -> R.string.carlink_home_eyebrow_problem
        else -> R.string.carlink_home_eyebrow_ready
    }
    TellTale(stringResource(eyebrow), modeColor(mode), pill = !dense)
    Spacer(Modifier.height(if (dense) 12.dp else if (car) 28.dp else 18.dp))

    val (title, body) = when (mode) {
        HomeMode.PERMISSION -> stringResource(R.string.carlink_home_permission_title) to stringResource(R.string.carlink_home_permission_body)
        HomeMode.BLUETOOTH_OFF -> stringResource(R.string.carlink_home_bt_off_title) to stringResource(R.string.carlink_home_bt_off_body)
        HomeMode.READY_EMPTY -> stringResource(R.string.carlink_home_ready_title) to stringResource(R.string.carlink_home_ready_body)
        HomeMode.READY_PHONE -> stringResource(R.string.carlink_home_ready_phone_title, phoneName) to
            stringResource(R.string.carlink_home_ready_phone_body, phone?.kind?.engine?.let { engineName(it) } ?: "",
                pathName(ui.network?.path, Transport.WIRELESS))
        HomeMode.CONNECTING -> stringResource(R.string.carlink_home_connecting_title, engine) to
            // Android phones only start wireless Android Auto when their Bluetooth connects.
            when {
                !androidWaiting(ui) -> stringResource(R.string.carlink_home_connecting_body, phoneName)
                // Apps cannot open a phone's Bluetooth link; the driver connects it once.
                !sessionPhoneConnected(ui) -> stringResource(R.string.carlink_home_connecting_connect_bluetooth, phoneName)
                else -> stringResource(R.string.carlink_home_connecting_android_wireless, phoneName)
            }
        HomeMode.NOT_ANSWERING -> {
            val usb = link.transport == Transport.USB
            stringResource(if (!usb && link.engine == Engine.CARPLAY) R.string.carlink_home_not_answering_title_iphone
                else R.string.carlink_home_not_answering_title) to stringResource(when {
                usb -> R.string.carlink_home_not_answering_body_usb
                link.engine == Engine.ANDROID_AUTO -> R.string.carlink_home_not_answering_body_android
                else -> R.string.carlink_home_not_answering_body_iphone
            }, phoneName)
        }
        HomeMode.CONNECTED -> {
            val elapsed by rememberElapsedMillis(ui.home.connectedSince)
            stringResource(R.string.carlink_home_connected_title, engine) to
                stringResource(R.string.carlink_home_connected_body, phoneName, pathName(ui.network?.path, link.transport),
                    formatElapsed(elapsed))
        }
        HomeMode.FAILED -> failureCopy(link)
    }
    val titleStyle = when {
        title.length <= 14 -> MaterialTheme.typography.displayLarge
        title.length <= 26 -> MaterialTheme.typography.displayMedium
        else -> MaterialTheme.typography.displaySmall
    }
    Text(title, style = titleStyle.copy(lineBreak = LineBreak.Heading), color = scheme.onSurface, textAlign = align,
        modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
    if ((mode == HomeMode.CONNECTING || mode == HomeMode.NOT_ANSWERING || mode == HomeMode.CONNECTED) && link.transport != null) {
        // Cable or wireless, in words and a glyph, right under the headline.
        Spacer(Modifier.height(if (car) 18.dp else 12.dp))
        val usb = link.transport == Transport.USB
        val connected = mode == HomeMode.CONNECTED
        val lead = stringResource(when {
            usb && connected -> R.string.carlink_transport_connected_cable
            usb -> R.string.carlink_transport_connecting_cable
            connected -> R.string.carlink_transport_connected_wireless
            else -> R.string.carlink_transport_connecting_wireless
        })
        val path = ui.network?.path.takeIf { !usb }
        com.carlink.app.ui.premium.TransportBadge(
            com.carlink.app.ui.premium.transportIcon(link.transport, path),
            if (path != null) stringResource(R.string.carlink_transport_with_path, lead,
                com.carlink.app.ui.premium.wirelessPathName(path)) else lead,
            tint = if (connected) colors.success else modeColor(mode),
        )
    }
    Spacer(Modifier.height(if (car) 16.dp else 10.dp))
    // A reading measure of about 60 characters, so lines do not run across the whole stage.
    Text(body, Modifier.widthIn(max = if (car) 760.dp else 520.dp),
        style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph),
        color = scheme.onSurfaceVariant, textAlign = align)
    // Why it is waiting (slower than usual, recovering, trying another path), in plain words.
    val notice = if (mode == HomeMode.CONNECTING) noticeText(link) else null
    if (notice != null) {
        Spacer(Modifier.height(if (car) 18.dp else 12.dp))
        NoticeLine(notice, noticeIcon(link))
    }
    // A known phone's record: "Usually connects in 5 s by Wi-Fi Direct".
    // Only when it describes the path the next start takes, so it never contradicts the line above.
    val usual = ui.usual.takeIf { mode == HomeMode.READY_PHONE && it?.path == ui.network?.path }
    if (usual != null) {
        Spacer(Modifier.height(if (car) 14.dp else 8.dp))
        com.carlink.app.ui.premium.TransportInline(R.drawable.carlink_ic_schedule, usualText(usual), colors.onSurfaceTertiary)
    }

    if ((mode == HomeMode.CONNECTING || mode == HomeMode.NOT_ANSWERING) && steps) {
        Spacer(Modifier.height(sectionGap))
        val holding = mode == HomeMode.NOT_ANSWERING
        ProgressLine(stepNames(), if (holding) 0 else activeStep(link), failed = false, still = holding,
            modifier = Modifier.widthIn(max = 720.dp))
    }

    Spacer(Modifier.height(sectionGap))
    // Actions wrap to a second line on narrow windows instead of running off the edge.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(dimens.targetGap, if (centered) Alignment.CenterHorizontally else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(dimens.targetGap), itemVerticalAlignment = Alignment.CenterVertically) {
        when (mode) {
            HomeMode.PERMISSION -> IonButton(stringResource(R.string.carlink_home_permission_action), actions::requestPermissions,
                icon = R.drawable.carlink_ic_check)
            HomeMode.BLUETOOTH_OFF -> IonButton(stringResource(R.string.carlink_home_bt_off_action), actions::openBluetoothSettings,
                icon = R.drawable.carlink_ic_bluetooth)
            HomeMode.READY_EMPTY -> GlassButton(stringResource(R.string.carlink_home_pair_action), actions::pairPhone,
                icon = R.drawable.carlink_ic_bluetooth)
            HomeMode.READY_PHONE -> phone?.let {
                IonButton(stringResource(R.string.carlink_home_connect), { actions.connect(it) }, icon = R.drawable.carlink_ic_link)
            }
            HomeMode.CONNECTING -> {
                if (link.notice?.kind == com.carlink.core.core.LinkNoticeKind.NO_PICTURE) {
                    IonButton(stringResource(R.string.carlink_home_direct_video_action), actions::turnOnDirectVideo,
                        icon = R.drawable.carlink_ic_display_settings)
                }
                if (androidWaiting(ui) && !sessionPhoneConnected(ui)) {
                    IonButton(stringResource(R.string.carlink_home_bluetooth_settings), actions::openBluetoothSettings,
                        icon = R.drawable.carlink_ic_bluetooth)
                }
                GlassButton(stringResource(R.string.carlink_home_cancel), actions::disconnect, icon = R.drawable.carlink_ic_close)
            }
            HomeMode.NOT_ANSWERING -> {
                // The session is still running: Try again restarts it, Cancel ends it.
                IonButton(stringResource(R.string.carlink_home_try_again), actions::retry, icon = R.drawable.carlink_ic_refresh)
                GlassButton(stringResource(R.string.carlink_home_cancel), actions::disconnect, icon = R.drawable.carlink_ic_close)
            }
            HomeMode.CONNECTED -> {
                IonButton(stringResource(R.string.carlink_home_return, engine), actions::returnToProjection,
                    icon = R.drawable.carlink_ic_play_arrow_filled)
                GlassButton(stringResource(R.string.carlink_home_disconnect), actions::disconnect, icon = R.drawable.carlink_ic_link_off)
            }
            HomeMode.FAILED -> {
                FailureActions(link, ui.network, actions)
                // The log and the error go straight into a GitHub issue (no copying from the stereo).
                TertiaryButton(stringResource(R.string.carlink_report_action), actions::reportProblem, icon = R.drawable.carlink_ic_bug_report)
            }
        }
    }
    if (ui.connection.autoConnectPaused && (mode == HomeMode.READY_PHONE || mode == HomeMode.READY_EMPTY)) {
        Spacer(Modifier.height(dimens.targetGap))
        FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.carlink_home_paused), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceTertiary)
            TertiaryButton(stringResource(R.string.carlink_home_resume), actions::resumeAutoConnect)
        }
    }
}

/**
 * Home's headline and sentence for a failure, as string resources (the issue report quotes them
 * too). The engine sentence takes the engine's name as its argument.
 */
internal fun failureCopyRes(problem: LinkProblem?): Pair<Int, Int> = when (problem) {
    LinkProblem.CARPLAY_AUTH_MISSING -> R.string.carlink_home_error_auth_title to R.string.carlink_home_error_auth_body
    LinkProblem.NO_IPHONE_SELECTED -> R.string.carlink_home_error_iphone_title to R.string.carlink_home_error_iphone_body
    LinkProblem.CAR_HOTSPOT_DETAILS_MISSING -> R.string.carlink_home_error_hotspot_details_title to R.string.carlink_home_error_hotspot_details_body
    LinkProblem.CAR_HOTSPOT_OFF -> R.string.carlink_home_error_hotspot_off_title to R.string.carlink_home_error_hotspot_off_body
    LinkProblem.SAME_WIFI_DETAILS_MISSING -> R.string.carlink_home_error_wifi_details_title to R.string.carlink_home_error_wifi_details_body
    LinkProblem.SAME_WIFI_NOT_JOINED -> R.string.carlink_home_error_wifi_not_joined_title to R.string.carlink_home_error_wifi_not_joined_body
    LinkProblem.BLUETOOTH_OFF -> R.string.carlink_home_error_bt_off_title to R.string.carlink_home_error_bt_off_body
    LinkProblem.PHONE_NOT_PAIRED -> R.string.carlink_home_error_not_paired_title to R.string.carlink_home_error_not_paired_body
    LinkProblem.PAIRING_LOST -> R.string.carlink_home_error_pairing_lost_title to R.string.carlink_home_error_pairing_lost_body
    LinkProblem.WIFI_OFF -> R.string.carlink_home_error_wifi_off_title to R.string.carlink_home_error_wifi_off_body
    LinkProblem.LOCATION_OFF -> R.string.carlink_home_error_location_off_title to R.string.carlink_home_error_location_off_body
    LinkProblem.PERMISSIONS -> R.string.carlink_home_error_permissions_title to R.string.carlink_home_error_permissions_body
    LinkProblem.OVERLAY -> R.string.carlink_home_error_overlay_title to R.string.carlink_home_error_overlay_body
    LinkProblem.VPN_CONSENT -> R.string.carlink_home_error_vpn_title to R.string.carlink_home_error_vpn_body
    LinkProblem.PHONE_REFUSED_WIFI -> R.string.carlink_home_error_refused_title to R.string.carlink_home_error_refused_body
    LinkProblem.PHONE_NEVER_REACHED_CAR -> R.string.carlink_home_error_unreached_title to R.string.carlink_home_error_unreached_body
    LinkProblem.PHONE_WIFI_OFF -> R.string.carlink_home_error_wifi_phone_off_title to R.string.carlink_home_error_wifi_phone_off_body
    LinkProblem.LINK_TOO_WEAK -> R.string.carlink_home_error_weak_title to R.string.carlink_home_error_weak_body
    LinkProblem.PHONE_DID_NOT_ANSWER -> R.string.carlink_home_error_no_answer_title to R.string.carlink_home_error_no_answer_body
    LinkProblem.BLUETOOTH -> R.string.carlink_home_error_bluetooth_title to R.string.carlink_home_error_bluetooth_body
    LinkProblem.USB -> R.string.carlink_home_error_usb_title to R.string.carlink_home_error_usb_body
    LinkProblem.EXTERNAL_BLUETOOTH -> R.string.carlink_home_error_external_bt_title to R.string.carlink_home_error_external_bt_body
    LinkProblem.EXTERNAL_MODULE -> R.string.carlink_home_error_external_module_title to R.string.carlink_home_error_external_module_body
    LinkProblem.ENGINE, null -> R.string.carlink_home_error_engine_title to R.string.carlink_home_error_engine_body
}

@Composable
internal fun failureCopy(link: LinkState): Pair<String, String> {
    val (title, body) = failureCopyRes(link.problem)
    // Only the pairing-lost title takes an argument (the phone); the others ignore it.
    val phone = link.phoneName ?: stringResource(R.string.carlink_home_error_pairing_lost_phone)
    return stringResource(title, phone) to stringResource(body, link.engine?.let { engineName(it) } ?: "")
}

@Composable
private fun FailureActions(link: LinkState, network: NetworkPreview?, actions: HomeActions) {
    val problem = link.problem
    val fix = oneTapFix(problem)
    if (problem != null && fix != null) {
        IonButton(stringResource(fix.first), { actions.fix(problem) }, icon = fix.second)
        // Or leave Same Wi-Fi for the default.
        if (problem == LinkProblem.SAME_WIFI_NOT_JOINED || problem == LinkProblem.CAR_HOTSPOT_OFF) {
            GlassButton(stringResource(R.string.carlink_home_fix_use_direct), { actions.setConnectionMethod(ConnectionMethod.WIFI_DIRECT) },
                icon = R.drawable.carlink_ic_p2p)
        }
        return
    }
    when (problem) {
        LinkProblem.CARPLAY_AUTH_MISSING -> IonButton(stringResource(R.string.carlink_home_diagnostics),
            { actions.navigate(Route.DIAGNOSTICS) }, icon = R.drawable.carlink_ic_monitor_heart)
        LinkProblem.NO_IPHONE_SELECTED -> IonButton(stringResource(R.string.carlink_home_pair_action), actions::pairPhone,
            icon = R.drawable.carlink_ic_bluetooth)
        // Never given: ask for it right here; saving connects again (no dead end, no trip to Settings).
        LinkProblem.CAR_HOTSPOT_DETAILS_MISSING, LinkProblem.SAME_WIFI_DETAILS_MISSING -> {
            val hotspot = problem == LinkProblem.CAR_HOTSPOT_DETAILS_MISSING
            var asking by rememberSaveable { mutableStateOf(false) }
            IonButton(stringResource(R.string.carlink_home_same_wifi_enter), { asking = true }, icon = R.drawable.carlink_ic_lock)
            GlassButton(stringResource(R.string.carlink_home_fix_use_direct), { actions.setConnectionMethod(ConnectionMethod.WIFI_DIRECT) },
                icon = R.drawable.carlink_ic_p2p)
            if (asking) {
                val known = (if (hotspot) network?.savedHotspot else network?.joinedSsid ?: network?.savedSameWifi)?.ifBlank { null }
                WifiPasswordDialog(hotspot, known, onSave = { ssid, password ->
                    if (hotspot) actions.saveCarHotspot(ssid, password) else actions.saveSameWifi(ssid, password)
                    asking = false
                }, onDismiss = { asking = false })
            }
        }
        LinkProblem.PHONE_NOT_PAIRED -> IonButton(stringResource(R.string.carlink_home_pair_action), actions::pairPhone,
            icon = R.drawable.carlink_ic_bluetooth)
        LinkProblem.PAIRING_LOST -> IonButton(stringResource(R.string.carlink_home_open_bluetooth_settings),
            actions::openBluetoothSettings, icon = R.drawable.carlink_ic_bluetooth)
        // No Try again: nothing on this stereo changes between attempts. The cable is the way.
        LinkProblem.EXTERNAL_BLUETOOTH, LinkProblem.EXTERNAL_MODULE -> {
            var cableHelp by rememberSaveable { mutableStateOf(false) }
            IonButton(stringResource(R.string.carlink_home_how_to_connect), { cableHelp = true }, icon = R.drawable.carlink_ic_usb)
            GlassButton(stringResource(R.string.carlink_home_diagnostics), { actions.navigate(Route.DIAGNOSTICS) },
                icon = R.drawable.carlink_ic_monitor_heart)
            if (cableHelp) CableHelpDialog { cableHelp = false }
        }
        // Already on Wi-Fi Direct (tablet 2026-10-20): "Use Wi-Fi Direct" would change nothing.
        LinkProblem.PHONE_REFUSED_WIFI, LinkProblem.PHONE_NEVER_REACHED_CAR -> if (network?.path == WirelessPath.WIFI_DIRECT) {
            IonButton(stringResource(R.string.carlink_home_try_again), actions::retry, icon = R.drawable.carlink_ic_refresh)
            GlassButton(stringResource(R.string.carlink_home_diagnostics), { actions.navigate(Route.DIAGNOSTICS) },
                icon = R.drawable.carlink_ic_monitor_heart)
        } else {
            IonButton(stringResource(R.string.carlink_home_fix_use_direct), { actions.fix(problem) }, icon = R.drawable.carlink_ic_p2p)
            GlassButton(stringResource(R.string.carlink_home_try_again), actions::retry, icon = R.drawable.carlink_ic_refresh)
        }
        else -> {
            IonButton(stringResource(R.string.carlink_home_try_again), actions::retry, icon = R.drawable.carlink_ic_refresh)
            GlassButton(stringResource(R.string.carlink_home_diagnostics), { actions.navigate(Route.DIAGNOSTICS) },
                icon = R.drawable.carlink_ic_monitor_heart)
        }
    }
}

@Composable
private fun pathName(path: WirelessPath?, transport: Transport?): String = stringResource(when {
    transport == Transport.USB -> R.string.carlink_home_path_usb
    path == WirelessPath.SAME_WIFI -> R.string.carlink_home_path_same_wifi
    path == WirelessPath.CAR_HOTSPOT -> R.string.carlink_home_path_hotspot
    else -> R.string.carlink_home_path_direct
})

/**
 * Home's stage. The status bar is docked to the bottom edge; the copy and the dial share the room
 * above it, side by side in [landscape] and stacked (dial first) otherwise.
 *
 * Nothing is ever clipped or cut short: the richest arrangement that fits wins. Status: [panel],
 * else the one-line [strip], else none; copy: regular, else dense (`copy(true, _)`: tighter
 * rhythm, no pill, no card body). The dial takes the room left, up to [dialMax], and steps aside
 * under [dialMin]; without it the copy shows the attempt's steps itself (`copy(_, true)`). The
 * stage only scrolls when even the dense copy is taller than the window (very large font scale).
 */
@Composable
private fun HomeStage(
    modifier: Modifier,
    landscape: Boolean,
    statusGap: Dp,
    columnGap: Dp,
    dialMin: Dp,
    dialMax: Dp,
    copy: @Composable (dense: Boolean, steps: Boolean) -> Unit,
    panel: @Composable () -> Unit,
    strip: @Composable () -> Unit,
    dial: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val viewport = constraints.maxHeight
        Layout(
            contents = listOf({ Box { copy(false, false) } }, { Box { copy(true, false) } },
                { Box { copy(false, true) } }, { Box { copy(true, true) } },
                { Box { panel() } }, { Box { strip() } }, { Box { dial() } }),
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        ) { slots, constraints ->
            val width = constraints.maxWidth
            val gap = statusGap.roundToPx()
            val colGap = columnGap.roundToPx()
            // Landscape: the copy keeps a little over half the width, the dial is centred in the rest.
            val copyWidth = if (landscape) (width * 0.55f).toInt() else width
            val loose = Constraints(maxWidth = copyWidth)
            val regular = slots[0].first().measure(loose)
            val dense = slots[1].first().measure(loose)
            val panelP = slots[4].first().measure(Constraints(maxWidth = width))
            val stripP = slots[5].first().measure(Constraints(maxWidth = width))
            fun room(status: Placeable?) = viewport - (status?.let { it.height + gap } ?: 0)
            val (firstCopy, status) = listOf(regular to panelP, regular to stripP, dense to panelP, dense to stripP,
                regular to null, dense to null).firstOrNull { (c, s) -> c.height <= room(s) } ?: (dense to stripP)
            var copyP: Placeable = firstCopy
            val stage = room(status)

            val dialGap = (columnGap * 0.75f).roundToPx()
            val side = if (landscape) minOf(width - copyWidth - colGap, stage, dialMax.roundToPx()) * 92 / 100
            else minOf(stage - copyP.height - dialGap, (width * 0.72f).toInt(), (viewport * 0.46f).toInt(), dialMax.roundToPx())
            val dialP = if (side >= dialMin.roundToPx()) slots[6].first().measure(Constraints.fixed(side, side)) else null
            if (dialP == null) {
                // No dial to show the attempt's progress: the copy names the steps, when that still fits.
                val regularSteps = if (copyP === regular) slots[2].first().measure(loose) else null
                copyP = when {
                    regularSteps != null && regularSteps.height <= stage -> regularSteps
                    else -> slots[3].first().measure(loose).takeIf { it.height <= stage } ?: copyP
                }
            }

            val stageHeight = maxOf(stage, copyP.height)
            val total = stageHeight + (status?.let { gap + it.height } ?: 0)
            val height = maxOf(total, viewport)
            layout(width, height) {
                if (landscape) {
                    copyP.place(0, (stageHeight - copyP.height) / 2)
                    dialP?.place(copyWidth + colGap + (width - copyWidth - colGap - dialP.width) / 2, (stageHeight - dialP.height) / 2)
                } else {
                    val group = copyP.height + (dialP?.let { it.height + dialGap } ?: 0)
                    var y = maxOf(0, (stageHeight - group) / 2)
                    dialP?.let { it.place((width - it.width) / 2, y); y += it.height + dialGap }
                    copyP.place((width - copyP.width) / 2, y)
                }
                // Docked: the status bar always sits on the bottom edge.
                status?.place(0, height - status.height)
            }
        }
    }
}

/**
 * The password (and the network's name, when CarLink does not know it) a wireless connection
 * needs, asked right on Home: [hotspot] for the car hotspot, else Same Wi-Fi. Saving it makes the
 * blocked start check again, so the connection goes ahead without another tap.
 */
@Composable
internal fun WifiPasswordDialog(hotspot: Boolean, knownSsid: String?, onSave: (ssid: String, password: String) -> Unit,
                               onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var ssid by rememberSaveable { mutableStateOf(knownSsid.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    var reveal by rememberSaveable { mutableStateOf(false) }
    val valid = password.isNotEmpty() && WifiDetails(ssid, password).isValid()
    val showError = password.isNotEmpty() && password.length !in 8..63
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(when {
                hotspot -> stringResource(R.string.carlink_home_error_hotspot_details_title)
                knownSsid != null -> stringResource(R.string.carlink_home_same_wifi_title, isolate(knownSsid))
                else -> stringResource(R.string.carlink_home_wifi_dialog_title)
            })
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(CarLinkTokens.dimens.targetGap)) {
                Text(stringResource(R.string.carlink_home_wifi_dialog_body), color = scheme.onSurfaceVariant)
                if (knownSsid == null) {
                    OutlinedTextField(value = ssid, onValueChange = { ssid = it }, singleLine = true,
                        label = { Text(stringResource(R.string.carlink_settings_wifi_name)) },
                        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text(stringResource(R.string.carlink_home_same_wifi_password)) },
                    isError = showError,
                    supportingText = if (showError) {{ Text(stringResource(R.string.carlink_home_same_wifi_invalid)) }} else null,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { reveal = !reveal }) {
                            Text(stringResource(if (reveal) R.string.carlink_settings_wifi_hide else R.string.carlink_settings_wifi_show))
                        }
                    },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            IonButton(stringResource(R.string.carlink_home_same_wifi_save), { if (valid) onSave(ssid, password) },
                icon = R.drawable.carlink_ic_check)
        },
        dismissButton = { GlassButton(stringResource(R.string.carlink_home_cancel), onDismiss) },
        containerColor = scheme.surfaceContainerHigh,
    )
}

/** Wireless Android Auto waiting for the phone to start it (it only does when its Bluetooth connects). */
private fun androidWaiting(ui: HomeUi): Boolean {
    val link = ui.home.link
    return link.engine == Engine.ANDROID_AUTO && link.transport == Transport.WIRELESS && link.step <= 1
}

/** Whether the phone of the current attempt has a Bluetooth link to this head unit right now. */
private fun sessionPhoneConnected(ui: HomeUi): Boolean =
    ui.home.phones.firstOrNull { it.name == ui.home.link.phoneName }?.connected ?: false
