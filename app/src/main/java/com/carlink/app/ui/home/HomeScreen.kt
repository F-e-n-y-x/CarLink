package com.carlink.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkProblem
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.PhoneKind
import com.carlink.app.core.Transport
import com.carlink.app.detect.BluetoothAvailability
import com.carlink.app.detect.PairedPhone
import com.carlink.app.settings.ConnectionMethod
import com.carlink.app.settings.WirelessPath
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.ConnectionPrefs
import com.carlink.app.ui.HomeState
import com.carlink.app.ui.NetworkPreview
import com.carlink.app.ui.Route
import com.carlink.app.ui.components.CarLinkMark
import com.carlink.app.ui.components.engineName
import com.carlink.app.ui.components.formatElapsed
import com.carlink.app.ui.components.isolate
import com.carlink.app.ui.components.rememberElapsedMillis
import com.carlink.app.ui.premium.Atmosphere
import com.carlink.app.ui.premium.Eyebrow
import com.carlink.app.ui.premium.GlassButton
import com.carlink.app.ui.premium.GlassCard
import com.carlink.app.ui.premium.GlassChip
import com.carlink.app.ui.premium.GlyphDisc
import com.carlink.app.ui.premium.HeroPhone
import com.carlink.app.ui.premium.IonButton
import com.carlink.app.ui.premium.ProgressLine
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.CarMotion
import com.carlink.app.ui.theme.LocalReduceMotion
import com.carlink.app.ui.theme.WidthClass

/** The modes of Home's hero (design/RESEARCH_PREMIUM_UX.md §7.3). */
internal enum class HomeMode { PERMISSION, BLUETOOTH_OFF, READY_EMPTY, READY_PHONE, CONNECTING, CONNECTED, FAILED }

internal fun homeMode(home: HomeState, permissionsNeeded: Boolean): HomeMode = when {
    home.link.stage == LinkStage.CONNECTED -> HomeMode.CONNECTED
    home.link.stage == LinkStage.WAITING || home.link.stage == LinkStage.CONNECTING -> HomeMode.CONNECTING
    home.link.stage == LinkStage.FAILED -> HomeMode.FAILED
    permissionsNeeded -> HomeMode.PERMISSION
    home.bluetooth == BluetoothAvailability.OFF -> HomeMode.BLUETOOTH_OFF
    home.defaultPhone != null -> HomeMode.READY_PHONE
    else -> HomeMode.READY_EMPTY
}

/** Everything Home needs, so previews and the screenshot gallery can render it without a ViewModel. */
internal data class HomeUi(
    val home: HomeState,
    val connection: ConnectionPrefs,
    val network: NetworkPreview?,
    val permissionsNeeded: Boolean,
)

internal interface HomeActions {
    fun requestPermissions() {}
    fun openBluetoothSettings() {}
    fun connect(phone: PairedPhone) {}
    fun switchTo(phone: PairedPhone) {}
    fun setPhoneKind(phone: PairedPhone, kind: PhoneKind) {}
    fun retry() {}
    fun disconnect() {}
    fun returnToProjection() {}
    fun resumeAutoConnect() {}
    fun saveSameWifi(ssid: String, password: String) {}
    fun setConnectionMethod(method: ConnectionMethod) {}
    /** The one-tap fix for [problem]: turn a radio on, open a permission screen, switch path. */
    fun fix(problem: LinkProblem) {}
    fun navigate(route: Route) {}
}

@Composable
fun HomeScreen(vm: CarLinkViewModel, navigate: (Route) -> Unit) {
    val home by vm.home.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    val permissionsNeeded by vm.permissionsNeeded.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshPhones() }
    val actions = remember(vm) {
        object : HomeActions {
            override fun requestPermissions() = launcher.launch(vm.missingPermissions().toTypedArray())
            override fun openBluetoothSettings() = vm.openBluetoothSettings()
            override fun connect(phone: PairedPhone) = vm.connect(phone)
            override fun switchTo(phone: PairedPhone) = vm.switchTo(phone)
            override fun setPhoneKind(phone: PairedPhone, kind: PhoneKind) = vm.setPhoneKind(phone, kind)
            override fun retry() = vm.retry()
            override fun disconnect() = vm.disconnect()
            override fun returnToProjection() = vm.returnToProjection()
            override fun resumeAutoConnect() = vm.resumeAutoConnect()
            override fun saveSameWifi(ssid: String, password: String) = vm.saveSameWifi(ssid, password)
            override fun setConnectionMethod(method: ConnectionMethod) = vm.updateSettings { it.copy(connectionMethod = method) }
            override fun fix(problem: LinkProblem) =
                if (problem == LinkProblem.PERMISSIONS) requestPermissions() else vm.fix(problem)
            override fun navigate(route: Route) = navigate(route)
        }
    }
    HomeContent(HomeUi(home, connection, network, permissionsNeeded), actions)
}

@Composable
internal fun HomeContent(ui: HomeUi, actions: HomeActions) {
    val mode = homeMode(ui.home, ui.permissionsNeeded)
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    val glow = when (mode) {
        HomeMode.CONNECTED -> colors.success
        HomeMode.CONNECTING, HomeMode.READY_PHONE -> scheme.primary
        HomeMode.FAILED -> scheme.error.copy(alpha = 0.6f)
        else -> scheme.primary.copy(alpha = 0.35f)
    }
    var phonesOpen by rememberSaveable { mutableStateOf(false) }
    var networkOpen by rememberSaveable { mutableStateOf(false) }
    val layout = CarLinkTokens.layout
    val dimens = CarLinkTokens.dimens

    Atmosphere(Modifier.fillMaxSize(), glow = glow, glowCenter = if (layout.isLandscape) androidx.compose.ui.geometry.Offset(0.74f, 0.6f)
        else androidx.compose.ui.geometry.Offset(0.5f, 0.3f)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopBar(onSettings = { actions.navigate(Route.SETTINGS) })
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val landscape = maxWidth > maxHeight && layout.width >= WidthClass.MEDIUM
                val taps = StatusTaps(onBluetooth = actions::openBluetoothSettings,
                    onNetwork = { networkOpen = true }, onPhones = { phonesOpen = true })
                if (landscape) {
                    // Two columns on one grid: copy and status on the start side, the phone centred on
                    // the end side, fully inside its column with room for its shadow.
                    Row(Modifier.fillMaxSize().padding(start = dimens.screenMargin, end = dimens.screenMargin,
                        bottom = dimens.screenMargin * 0.75f)) {
                        // Copy, action and status read as one group, centred against the phone.
                        FitColumn(
                            Modifier.weight(0.6f).fillMaxHeight(),
                            statusGap = dimens.gutter * 2,
                            main = { dense -> HeroText(mode, ui, actions, dense) },
                            status = { StatusChips(mode, ui, taps) },
                            strip = { StatusStrip(mode, ui, taps) },
                        )
                        Spacer(Modifier.width(dimens.gutter * 2))
                        Box(Modifier.weight(0.4f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Hero(mode, ui, Modifier.fillMaxHeight(0.86f))
                        }
                    }
                } else {
                    val compact = layout.width == WidthClass.COMPACT
                    FitColumn(
                        Modifier.fillMaxSize()
                            .padding(horizontal = if (compact) dimens.compactMargin else dimens.screenMargin)
                            .padding(bottom = dimens.gutter),
                        statusGap = dimens.gutter * 1.5f,
                        main = { dense -> HeroText(mode, ui, actions, dense) },
                        status = { if (compact) StatusList(mode, ui, taps) else StatusChips(mode, ui, taps) },
                        strip = { StatusStrip(mode, ui, taps) },
                        // The phone takes what the copy and status leave, and steps aside when that is too little.
                        hero = {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Hero(mode, ui, Modifier.fillMaxHeight(0.92f))
                            }
                        },
                        // Room for the phone's contact shadow.
                        heroGap = dimens.gutter * 2.5f,
                        heroMin = dimens.avatarHero * 3,
                    )
                }
            }
        }
    }
    if (phonesOpen) {
        PhonesSheet(ui, actions, onDismiss = { phonesOpen = false })
    }
    if (networkOpen) {
        NetworkSheet(ui, actions, onDismiss = { networkOpen = false })
    }
}

@Composable
private fun TopBar(onSettings: () -> Unit) {
    val dimens = CarLinkTokens.dimens
    Row(
        Modifier.fillMaxWidth().height(dimens.topBar).padding(horizontal = dimens.screenMargin - 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(8.dp))
        CarLinkMark(Modifier.size(dimens.iconSecondary))
        Spacer(Modifier.width(14.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.weight(1f))
        GlassCard(shape = androidx.compose.foundation.shape.CircleShape, onClick = onSettings,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            modifier = Modifier.size(dimens.minTouch)) {
            Icon(painterResource(R.drawable.carlink_ic_settings), stringResource(R.string.carlink_home_settings),
                Modifier.align(Alignment.Center).size(dimens.iconSecondary), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** The phone render with live status on its screen. */
@Composable
private fun Hero(mode: HomeMode, ui: HomeUi, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    val link = ui.home.link
    val kind = when (link.engine) {
        Engine.CARPLAY -> PhoneKind.IPHONE
        Engine.ANDROID_AUTO -> PhoneKind.ANDROID
        null -> ui.home.defaultPhone?.kind ?: PhoneKind.UNKNOWN
    }
    val glow = when (mode) {
        HomeMode.CONNECTED -> colors.success
        HomeMode.CONNECTING, HomeMode.READY_PHONE -> scheme.primary
        HomeMode.FAILED -> scheme.error
        else -> Color.Transparent
    }
    HeroPhone(kind, modifier, glow = glow) {
        AnimatedContent(mode, transitionSpec = {
            fadeIn(tween(CarMotion.MEDIUM)) togetherWith fadeOut(tween(CarMotion.MEDIUM))
        }, label = "screen") { state ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val size = CarLinkTokens.dimens.avatarHero
                when (state) {
                    HomeMode.CONNECTING -> {
                        if (LocalReduceMotion.current) GlyphDisc(R.drawable.carlink_ic_sync, scheme.primary, size)
                        else CircularProgressIndicator(Modifier.size(size), color = scheme.primary, strokeWidth = 5.dp)
                    }
                    HomeMode.CONNECTED -> GlyphDisc(R.drawable.carlink_ic_check_circle_filled, colors.success, size)
                    HomeMode.FAILED -> GlyphDisc(R.drawable.carlink_ic_error_filled, scheme.error, size)
                    // The phone's screen is always dark, so the mark uses fixed light colors.
                    HomeMode.READY_PHONE -> com.carlink.app.ui.components.CarLinkMark(Modifier.size(size),
                        primaryTint = Color(0xFFEEF2F6), accentTint = Color(0xFF5BD6F0))
                    else -> com.carlink.app.ui.components.CarLinkMark(Modifier.size(size),
                        primaryTint = Color(0xFFEEF2F6).copy(alpha = 0.55f), accentTint = Color(0xFF5BD6F0).copy(alpha = 0.7f))
                }
                val label = link.engine?.let { engineName(it) } ?: ui.home.defaultPhone?.kind?.engine?.let { engineName(it) }
                if (label != null) {
                    Text(label, style = MaterialTheme.typography.titleSmall, color = Color(0xFFEEF2F6), maxLines = 1)
                }
            }
        }
    }
}

/** Eyebrow, headline, body and actions for the current mode. */
@Composable
private fun HeroText(mode: HomeMode, ui: HomeUi, actions: HomeActions, dense: Boolean) {
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(mode, transitionSpec = {
        val duration = if (reduceMotion) 0 else CarMotion.MEDIUM
        (fadeIn(tween(duration)) + slideInVertically(tween(duration, easing = CarMotion.EmphasizedDecelerate)) { it / 12 }) togetherWith
            fadeOut(tween(duration / 2))
    }, label = "hero") { state ->
        Column(Modifier.widthIn(max = 900.dp)) { HeroCopy(state, ui, actions, dense) }
    }
}

@Composable
private fun ColumnScope.HeroCopy(mode: HomeMode, ui: HomeUi, actions: HomeActions, dense: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    val dimens = CarLinkTokens.dimens
    // Dense: the variant Home falls back to when the window is short (tighter rhythm, no card body).
    val sectionGap = if (dense) dimens.gutter else dimens.gutter * 1.5f
    val link = ui.home.link
    val engine = link.engine?.let { engineName(it) } ?: ""
    val phone = ui.home.defaultPhone
    val phoneName = isolate(link.phoneName ?: phone?.name ?: stringResource(R.string.carlink_home_kind_iphone))

    val eyebrow = when (mode) {
        HomeMode.PERMISSION -> R.string.carlink_home_eyebrow_setup
        HomeMode.CONNECTING -> R.string.carlink_home_eyebrow_connecting
        HomeMode.CONNECTED -> R.string.carlink_home_eyebrow_connected
        HomeMode.FAILED, HomeMode.BLUETOOTH_OFF -> R.string.carlink_home_eyebrow_problem
        else -> R.string.carlink_home_eyebrow_ready
    }
    Eyebrow(stringResource(eyebrow), color = when (mode) {
        HomeMode.CONNECTED -> colors.success
        HomeMode.FAILED -> scheme.error
        HomeMode.CONNECTING -> scheme.primary
        else -> colors.onSurfaceTertiary
    })
    Spacer(Modifier.height(12.dp))

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
        HomeMode.CONNECTED -> {
            val elapsed by rememberElapsedMillis(ui.home.connectedSince)
            stringResource(R.string.carlink_home_connected_title, engine) to
                stringResource(R.string.carlink_home_connected_body, phoneName, pathName(ui.network?.path, link.transport),
                    formatElapsed(elapsed))
        }
        HomeMode.FAILED -> failureCopy(link)
    }
    Text(title, style = if (title.length <= 14) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displayMedium,
        color = scheme.onSurface, modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
    Spacer(Modifier.height(16.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)

    if (mode == HomeMode.CONNECTING) {
        Spacer(Modifier.height(sectionGap))
        val active = when {
            link.step <= 1 -> 0
            link.step == 2 -> 1
            link.step == 3 -> 2
            else -> 3
        }
        ProgressLine(listOf(
            stringResource(R.string.carlink_home_step_found), stringResource(R.string.carlink_home_step_identified),
            stringResource(R.string.carlink_home_step_linked), stringResource(R.string.carlink_home_step_picture),
        ), active, failed = false, modifier = Modifier.widthIn(max = 720.dp))
    }

    Spacer(Modifier.height(sectionGap))
    // Actions wrap to a second line on narrow windows instead of running off the edge.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(dimens.targetGap),
        verticalArrangement = Arrangement.spacedBy(dimens.targetGap), itemVerticalAlignment = Alignment.CenterVertically) {
        when (mode) {
            HomeMode.PERMISSION -> IonButton(stringResource(R.string.carlink_home_permission_action), actions::requestPermissions,
                icon = R.drawable.carlink_ic_check)
            HomeMode.BLUETOOTH_OFF -> IonButton(stringResource(R.string.carlink_home_bt_off_action), actions::openBluetoothSettings,
                icon = R.drawable.carlink_ic_bluetooth)
            HomeMode.READY_EMPTY -> GlassButton(stringResource(R.string.carlink_home_pair_action), actions::openBluetoothSettings,
                icon = R.drawable.carlink_ic_bluetooth)
            HomeMode.READY_PHONE -> phone?.let {
                IonButton(stringResource(R.string.carlink_home_connect), { actions.connect(it) }, icon = R.drawable.carlink_ic_link)
            }
            HomeMode.CONNECTING -> {
                if (androidWaiting(ui) && !sessionPhoneConnected(ui)) {
                    IonButton(stringResource(R.string.carlink_home_bluetooth_settings), actions::openBluetoothSettings,
                        icon = R.drawable.carlink_ic_bluetooth)
                }
                GlassButton(stringResource(R.string.carlink_home_cancel), actions::disconnect, icon = R.drawable.carlink_ic_close)
            }
            HomeMode.CONNECTED -> {
                IonButton(stringResource(R.string.carlink_home_return, engine), actions::returnToProjection,
                    icon = R.drawable.carlink_ic_play_arrow_filled)
                GlassButton(stringResource(R.string.carlink_home_disconnect), actions::disconnect, icon = R.drawable.carlink_ic_link_off)
            }
            HomeMode.FAILED -> FailureActions(link, actions)
        }
    }
    val offerSameWifi = ui.network?.canOfferSameWifi == true && ui.home.phones.any { it.kind != PhoneKind.UNKNOWN } &&
        (mode == HomeMode.READY_PHONE || mode == HomeMode.READY_EMPTY ||
            (mode == HomeMode.FAILED && link.problem == LinkProblem.SAME_WIFI_DETAILS_MISSING))
    if (offerSameWifi) {
        Spacer(Modifier.height(sectionGap))
        SameWifiCard(ui.network!!.joinedSsid!!, actions, dense)
    }
    if (ui.connection.autoConnectPaused && (mode == HomeMode.READY_PHONE || mode == HomeMode.READY_EMPTY)) {
        Spacer(Modifier.height(dimens.targetGap))
        FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.carlink_home_paused), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceTertiary)
            com.carlink.app.ui.components.TertiaryButton(stringResource(R.string.carlink_home_resume), actions::resumeAutoConnect)
        }
    }
}

@Composable
private fun failureCopy(link: LinkState): Pair<String, String> = when (link.problem) {
    LinkProblem.CARPLAY_AUTH_MISSING -> stringResource(R.string.carlink_home_error_auth_title) to stringResource(R.string.carlink_home_error_auth_body)
    LinkProblem.NO_IPHONE_SELECTED -> stringResource(R.string.carlink_home_error_iphone_title) to stringResource(R.string.carlink_home_error_iphone_body)
    LinkProblem.CAR_HOTSPOT_DETAILS_MISSING -> stringResource(R.string.carlink_home_error_hotspot_details_title) to
        stringResource(R.string.carlink_home_error_hotspot_details_body)
    LinkProblem.CAR_HOTSPOT_OFF -> stringResource(R.string.carlink_home_error_hotspot_off_title) to
        stringResource(R.string.carlink_home_error_hotspot_off_body)
    LinkProblem.SAME_WIFI_DETAILS_MISSING -> stringResource(R.string.carlink_home_error_wifi_details_title) to
        stringResource(R.string.carlink_home_error_wifi_details_body)
    LinkProblem.BLUETOOTH_OFF -> stringResource(R.string.carlink_home_error_bt_off_title) to stringResource(R.string.carlink_home_error_bt_off_body)
    LinkProblem.PHONE_NOT_PAIRED -> stringResource(R.string.carlink_home_error_not_paired_title) to stringResource(R.string.carlink_home_error_not_paired_body)
    LinkProblem.WIFI_OFF -> stringResource(R.string.carlink_home_error_wifi_off_title) to stringResource(R.string.carlink_home_error_wifi_off_body)
    LinkProblem.LOCATION_OFF -> stringResource(R.string.carlink_home_error_location_off_title) to stringResource(R.string.carlink_home_error_location_off_body)
    LinkProblem.PERMISSIONS -> stringResource(R.string.carlink_home_error_permissions_title) to stringResource(R.string.carlink_home_error_permissions_body)
    LinkProblem.OVERLAY -> stringResource(R.string.carlink_home_error_overlay_title) to stringResource(R.string.carlink_home_error_overlay_body)
    LinkProblem.VPN_CONSENT -> stringResource(R.string.carlink_home_error_vpn_title) to stringResource(R.string.carlink_home_error_vpn_body)
    LinkProblem.PHONE_REFUSED_WIFI -> stringResource(R.string.carlink_home_error_refused_title) to stringResource(R.string.carlink_home_error_refused_body)
    LinkProblem.PHONE_NEVER_REACHED_CAR -> stringResource(R.string.carlink_home_error_unreached_title) to stringResource(R.string.carlink_home_error_unreached_body)
    LinkProblem.LINK_TOO_WEAK -> stringResource(R.string.carlink_home_error_weak_title) to stringResource(R.string.carlink_home_error_weak_body)
    LinkProblem.PHONE_DID_NOT_ANSWER -> stringResource(R.string.carlink_home_error_no_answer_title) to stringResource(R.string.carlink_home_error_no_answer_body)
    LinkProblem.BLUETOOTH -> stringResource(R.string.carlink_home_error_bluetooth_title) to stringResource(R.string.carlink_home_error_bluetooth_body)
    LinkProblem.USB -> stringResource(R.string.carlink_home_error_usb_title) to stringResource(R.string.carlink_home_error_usb_body)
    LinkProblem.ENGINE, null -> stringResource(R.string.carlink_home_error_engine_title) to
        stringResource(R.string.carlink_home_error_engine_body, link.engine?.let { engineName(it) } ?: "")
}

@Composable
private fun FailureActions(link: LinkState, actions: HomeActions) {
    when (link.problem) {
        LinkProblem.CARPLAY_AUTH_MISSING -> IonButton(stringResource(R.string.carlink_home_diagnostics),
            { actions.navigate(Route.DIAGNOSTICS) }, icon = R.drawable.carlink_ic_monitor_heart)
        LinkProblem.NO_IPHONE_SELECTED -> IonButton(stringResource(R.string.carlink_home_pair_action), actions::openBluetoothSettings,
            icon = R.drawable.carlink_ic_bluetooth)
        LinkProblem.CAR_HOTSPOT_DETAILS_MISSING, LinkProblem.SAME_WIFI_DETAILS_MISSING -> IonButton(
            stringResource(R.string.carlink_home_open_settings), { actions.navigate(Route.SETTINGS) }, icon = R.drawable.carlink_ic_settings)
        LinkProblem.BLUETOOTH_OFF, LinkProblem.WIFI_OFF -> IonButton(stringResource(R.string.carlink_home_fix_turn_on),
            { actions.fix(link.problem) }, icon = R.drawable.carlink_ic_check)
        LinkProblem.PERMISSIONS, LinkProblem.OVERLAY, LinkProblem.VPN_CONSENT -> IonButton(stringResource(R.string.carlink_home_fix_allow),
            { actions.fix(link.problem) }, icon = R.drawable.carlink_ic_check)
        LinkProblem.LOCATION_OFF -> IonButton(stringResource(R.string.carlink_home_fix_open_settings),
            { actions.fix(link.problem) }, icon = R.drawable.carlink_ic_settings)
        LinkProblem.PHONE_NOT_PAIRED -> IonButton(stringResource(R.string.carlink_home_pair_action), actions::openBluetoothSettings,
            icon = R.drawable.carlink_ic_bluetooth)
        LinkProblem.PHONE_REFUSED_WIFI, LinkProblem.PHONE_NEVER_REACHED_CAR -> {
            IonButton(stringResource(R.string.carlink_home_fix_use_direct), { actions.fix(link.problem) }, icon = R.drawable.carlink_ic_p2p)
            GlassButton(stringResource(R.string.carlink_home_try_again), actions::retry)
        }
        else -> {
            IonButton(stringResource(R.string.carlink_home_try_again), actions::retry, icon = R.drawable.carlink_ic_refresh)
            GlassButton(stringResource(R.string.carlink_home_diagnostics), { actions.navigate(Route.DIAGNOSTICS) })
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

/** One line of Home's status: Bluetooth, the wireless path, the phone. */
private class StatusItem(val eyebrow: String, val value: String, @DrawableRes val icon: Int, val status: Color?,
    val onClick: (() -> Unit)? = null)

/** What tapping each status opens: Bluetooth settings, the connection-type picker, the phone list. */
private class StatusTaps(val onBluetooth: () -> Unit, val onNetwork: () -> Unit, val onPhones: () -> Unit)

@Composable
private fun statusItems(mode: HomeMode, ui: HomeUi, taps: StatusTaps): List<StatusItem> {
    val colors = CarLinkTokens.colors
    val bt = ui.home.bluetooth
    val path = if (mode == HomeMode.CONNECTED && ui.home.link.transport == Transport.USB) null else ui.network?.path
    val phones = ui.home.phones
    val phone = ui.home.defaultPhone
    return listOf(
        StatusItem(stringResource(R.string.carlink_home_chip_bluetooth), stringResource(when (bt) {
            BluetoothAvailability.READY -> R.string.carlink_home_bt_chip_on
            BluetoothAvailability.OFF -> R.string.carlink_home_bt_chip_off
            BluetoothAvailability.NO_PERMISSION -> R.string.carlink_home_bt_no_permission
            BluetoothAvailability.UNSUPPORTED -> R.string.carlink_home_bt_chip_unsupported
        }), if (bt == BluetoothAvailability.READY) R.drawable.carlink_ic_bluetooth else R.drawable.carlink_ic_bluetooth_disabled,
            if (bt == BluetoothAvailability.READY) colors.success else colors.warning, taps.onBluetooth),
        StatusItem(stringResource(R.string.carlink_home_chip_network), when (path) {
            WirelessPath.SAME_WIFI -> ui.network?.ssid?.let { stringResource(R.string.carlink_home_net_same_wifi_named, isolate(it)) }
                ?: stringResource(R.string.carlink_home_net_same_wifi)
            WirelessPath.CAR_HOTSPOT -> stringResource(R.string.carlink_home_net_hotspot)
            WirelessPath.WIFI_DIRECT -> stringResource(R.string.carlink_home_net_direct)
            null -> stringResource(R.string.carlink_home_path_usb)
        }, when (path) {
            WirelessPath.SAME_WIFI -> R.drawable.carlink_ic_wifi
            WirelessPath.CAR_HOTSPOT -> R.drawable.carlink_ic_wifi_tethering
            WirelessPath.WIFI_DIRECT -> R.drawable.carlink_ic_p2p
            null -> R.drawable.carlink_ic_usb
        }, if (path == WirelessPath.SAME_WIFI) colors.success else null, taps.onNetwork),
        StatusItem(stringResource(R.string.carlink_home_chip_phone),
            phone?.name?.let(::isolate)
                ?: if (phones.isEmpty()) stringResource(R.string.carlink_home_no_phones)
                else pluralStringResource(R.plurals.carlink_home_phone_count, phones.size, phones.size),
            phone?.let { com.carlink.app.ui.components.phoneIcon(it.kind) } ?: R.drawable.carlink_ic_smartphone,
            if (phone?.connected == true) colors.success else null, taps.onPhones),
    )
}

/** Three glass chips of one height: Bluetooth, Network, Phone; each opens what [StatusTaps] names. */
@Composable
private fun StatusChips(mode: HomeMode, ui: HomeUi, taps: StatusTaps) {
    val items = statusItems(mode, ui, taps)
    val dimens = CarLinkTokens.dimens
    val measurer = rememberTextMeasurer()
    val value = MaterialTheme.typography.titleSmall
    val eyebrow = MaterialTheme.typography.labelSmall
    // What each chip needs to keep its value and header on one line.
    val natural = with(LocalDensity.current) {
        items.map { item ->
            val valueLine = measurer.measure(item.value, value, maxLines = 1).size.width + (dimens.iconSecondary + 12.dp).roundToPx()
            // Eyebrow plus the status dot and its gap.
            val header = measurer.measure(item.eyebrow.uppercase(java.util.Locale.ROOT), eyebrow, maxLines = 1).size.width +
                24.dp.roundToPx()
            maxOf(valueLine, header) + (dimens.gutter * 2).roundToPx()
        }
    }
    Layout(
        content = {
            items.forEachIndexed { index, item ->
                GlassChip(item.eyebrow, item.value, item.icon, status = item.status,
                    onClick = item.onClick)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val gap = dimens.targetGap.roundToPx()
        val widths = chipWidths(natural, constraints.maxWidth - gap * (items.size - 1))
        // One height for all: the tallest chip at its width (a value that cannot fit wraps to two lines).
        val height = measurables.indices.maxOf { measurables[it].maxIntrinsicHeight(widths[it]) }
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i], height)) }
        layout(constraints.maxWidth, height) {
            var x = 0
            placeables.forEach { it.place(x, 0); x += it.width + gap }
        }
    }
}

/**
 * Shares [available] px between chips that each want [natural] px: chips that fit their fair
 * share get what they need, the rest split what is left; leftover space is spread evenly, so a
 * short value ("On") never takes room a phone name needs.
 */
private fun chipWidths(natural: List<Int>, available: Int): List<Int> {
    val widths = IntArray(natural.size)
    var remaining = available
    var open = natural.indices.sortedBy { natural[it] }
    while (open.isNotEmpty()) {
        val share = remaining / open.size
        val small = open.filter { natural[it] <= share }
        if (small.isEmpty()) {
            open.forEach { widths[it] = share }
            remaining -= share * open.size
            break
        }
        small.forEach { widths[it] = natural[it]; remaining -= natural[it] }
        open = open - small.toSet()
    }
    // Spread what is left (all chips fit) evenly; rounding remainder goes to the last chip.
    if (remaining > 0) {
        val extra = remaining / natural.size
        natural.indices.forEach { widths[it] += extra }
        widths[natural.lastIndex] += remaining - extra * natural.size
    }
    return widths.toList()
}

/** Narrow windows: the three statuses as one glass card with aligned rows instead of three stacked chips. */
@Composable
private fun StatusList(mode: HomeMode, ui: HomeUi, taps: StatusTaps) {
    val colors = CarLinkTokens.colors
    val dimens = CarLinkTokens.dimens
    val items = statusItems(mode, ui, taps)
    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
        Column {
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = dimens.gutter), color = colors.glassStrokeBottom)
                Row(Modifier.fillMaxWidth().heightIn(min = dimens.rowOneLine)
                    .then(item.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
                    .padding(horizontal = dimens.gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(item.icon), null, Modifier.size(dimens.iconSecondary), tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(dimens.gutter))
                    Eyebrow(item.eyebrow)
                    Spacer(Modifier.width(dimens.gutter))
                    Text(item.value, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End)
                    // The dot's space is kept on every row so values stay aligned.
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(item.status ?: Color.Transparent))
                }
            }
        }
    }
}

/**
 * Short windows: the three statuses on one glass pill. Values are shown when they all fit on the
 * line; otherwise Bluetooth and Network shrink to their icons (named for TalkBack) and only the
 * phone keeps its name. Each part opens what its chip opens.
 */
@Composable
private fun StatusStrip(mode: HomeMode, ui: HomeUi, taps: StatusTaps) {
    val dimens = CarLinkTokens.dimens
    val colors = CarLinkTokens.colors
    val items = statusItems(mode, ui, taps)
    val style = MaterialTheme.typography.titleSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val icon = dimens.iconTertiary + 4.dp
    val iconGap = 10.dp
    val gap = dimens.gutter
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val text = with(density) { items.sumOf { measurer.measure(it.value, style, maxLines = 1).size.width }.toDp() }
        val fixed = gap * 2 + (icon + iconGap) * items.size + (gap * 2 + 1.dp) * (items.size - 1)
        val labels = text + fixed <= maxWidth
        GlassCard(Modifier.heightIn(min = dimens.minTouch), shape = CircleShape,
            contentPadding = PaddingValues(horizontal = gap, vertical = 8.dp)) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gap)) {
                items.forEachIndexed { index, item ->
                    if (index > 0) Box(Modifier.width(1.dp).height(icon).background(colors.glassStrokeTop))
                    val showValue = labels || index == items.lastIndex
                    Row((if (showValue && !labels) Modifier.weight(1f, fill = false) else Modifier)
                        .heightIn(min = dimens.minTouch).clip(CircleShape)
                        .then(item.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(icon)) {
                            Icon(painterResource(item.icon), if (showValue) null else "${item.eyebrow}: ${item.value}",
                                Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.onSurface)
                            if (item.status != null) {
                                Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(9.dp)
                                    .clip(CircleShape).background(item.status))
                            }
                        }
                        if (showValue) {
                            Spacer(Modifier.width(iconGap))
                            Text(item.value, style = style, color = MaterialTheme.colorScheme.onSurface, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Home's column: [main] (copy and actions) always, then the richest status that still fits the
 * window: [status] (chips or the list), else [strip], else none. When the regular copy leaves no
 * room for any status, the dense copy (`main(true)`: tighter rhythm, shorter cards) is tried
 * before the status is dropped. An optional [hero] above takes the height that is left, up to
 * [heroMaxFraction] of the window, and is left out when that is under [heroMin]. The group is
 * centred vertically; it only scrolls when even the dense copy is taller than the window (very
 * large font scale), and then keeps the strip at the end.
 */
@Composable
private fun FitColumn(
    modifier: Modifier,
    statusGap: Dp,
    main: @Composable (dense: Boolean) -> Unit,
    status: @Composable () -> Unit,
    strip: @Composable () -> Unit,
    hero: (@Composable () -> Unit)? = null,
    heroGap: Dp = 0.dp,
    heroMin: Dp = 0.dp,
    heroMaxFraction: Float = 0.5f,
) {
    BoxWithConstraints(modifier) {
        val viewport = constraints.maxHeight
        Layout(
            contents = listOf({ Box { main(false) } }, { Box { main(true) } }, { Box { status() } }, { Box { strip() } },
                { if (hero != null) Box { hero() } }),
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        ) { (regularSlot, denseSlot, statusSlot, stripSlot, heroSlot), constraints ->
            val width = constraints.maxWidth
            val loose = Constraints(maxWidth = width)
            val regular = regularSlot.first().measure(loose)
            val dense = denseSlot.first().measure(loose)
            val statusP = statusSlot.first().measure(loose)
            val stripP = stripSlot.first().measure(loose)
            val gap = statusGap.roundToPx()
            fun fits(copy: Placeable, below: Placeable?) = copy.height + (below?.let { gap + it.height } ?: 0) <= viewport
            // The first arrangement that fits, richest first.
            val (mainP, chosen) = listOf(regular to statusP, regular to stripP, dense to statusP, dense to stripP,
                regular to null, dense to null).firstOrNull { (copy, below) -> fits(copy, below) } ?: (dense to stripP)
            val used = mainP.height + (chosen?.let { gap + it.height } ?: 0)
            val heroP = heroSlot.firstOrNull()?.let { slot ->
                val height = minOf(viewport - used - heroGap.roundToPx(), (viewport * heroMaxFraction).toInt())
                if (height >= heroMin.roundToPx()) slot.measure(Constraints.fixed(width, height)) else null
            }
            val total = used + (heroP?.let { it.height + heroGap.roundToPx() } ?: 0)
            layout(width, maxOf(total, viewport)) {
                var y = maxOf(0, (viewport - total) / 2)
                heroP?.let { it.place(0, y); y += it.height + heroGap.roundToPx() }
                mainP.place(0, y)
                y += mainP.height
                chosen?.place(0, y + gap)
            }
        }
    }
}

/**
 * One-time offer to use the car's current Wi-Fi for wireless CarPlay (the fastest path), as a
 * compact banner; the password is entered in a dialog, so the keyboard never reflows Home.
 * [dense] drops the explanation line.
 */
@Composable
private fun SameWifiCard(ssid: String, actions: HomeActions, dense: Boolean) {
    val dimens = CarLinkTokens.dimens
    val scheme = MaterialTheme.colorScheme
    var asking by rememberSaveable { mutableStateOf(false) }
    val button = @Composable {
        GlassButton(stringResource(R.string.carlink_home_same_wifi_enter), { asking = true }, icon = R.drawable.carlink_ic_lock)
    }
    GlassCard(Modifier.widthIn(max = 760.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(horizontal = dimens.gutter, vertical = dimens.targetGap)) {
        BoxWithConstraints {
            // The button sits beside the text only when the text keeps a comfortable measure.
            val inline = maxWidth >= dimens.contentMaxWidth * 0.45f
            @Composable
            fun copy(modifier: Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.carlink_ic_wifi), null, Modifier.size(dimens.iconSecondary),
                    tint = CarLinkTokens.colors.success)
                Spacer(Modifier.width(dimens.targetGap))
                Column {
                    Text(stringResource(R.string.carlink_home_same_wifi_title, isolate(ssid)),
                        style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                    if (!dense) {
                        Text(stringResource(R.string.carlink_home_same_wifi_body), style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant)
                    }
                }
            }
            if (inline) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    copy(Modifier.weight(1f))
                    Spacer(Modifier.width(dimens.targetGap))
                    button()
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
                    copy(Modifier)
                    button()
                }
            }
        }
    }
    if (asking) SameWifiDialog(ssid, actions, onDismiss = { asking = false })
}

@Composable
private fun SameWifiDialog(ssid: String, actions: HomeActions, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var password by rememberSaveable { mutableStateOf("") }
    var reveal by rememberSaveable { mutableStateOf(false) }
    val valid = password.length in 8..63
    val showError = password.isNotEmpty() && !valid
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.carlink_home_same_wifi_title, isolate(ssid))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(CarLinkTokens.dimens.targetGap)) {
                Text(stringResource(R.string.carlink_home_same_wifi_body), color = scheme.onSurfaceVariant)
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text(stringResource(R.string.carlink_home_same_wifi_password)) },
                    isError = showError,
                    supportingText = if (showError) {{ Text(stringResource(R.string.carlink_home_same_wifi_invalid)) }} else null,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(painterResource(R.drawable.carlink_ic_lock), stringResource(R.string.carlink_home_same_wifi_show))
                        }
                    },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            IonButton(stringResource(R.string.carlink_home_same_wifi_save), {
                if (valid) { actions.saveSameWifi(ssid, password); onDismiss() }
            }, icon = R.drawable.carlink_ic_check)
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
