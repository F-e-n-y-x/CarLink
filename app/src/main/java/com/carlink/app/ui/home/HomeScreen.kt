package com.carlink.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    val layout = CarLinkTokens.layout
    val dimens = CarLinkTokens.dimens

    Atmosphere(Modifier.fillMaxSize(), glow = glow, glowCenter = if (layout.isLandscape) androidx.compose.ui.geometry.Offset(0.74f, 0.6f)
        else androidx.compose.ui.geometry.Offset(0.5f, 0.3f)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopBar(onSettings = { actions.navigate(Route.SETTINGS) })
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val areaHeight = maxHeight
                val landscape = maxWidth > maxHeight && layout.width >= WidthClass.MEDIUM
                if (landscape) {
                    // Two columns on one grid: copy and chips on the start side, the phone centred on
                    // the end side, fully inside its column with room for its shadow.
                    Row(Modifier.fillMaxSize().padding(start = dimens.screenMargin, end = dimens.screenMargin,
                        bottom = dimens.screenMargin * 0.75f)) {
                        // Copy, action and chips read as one group, centred against the phone.
                        Column(Modifier.weight(0.56f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center) {
                            HeroText(mode, ui, actions)
                            Spacer(Modifier.height(dimens.gutter * 2.5f))
                            StatusChips(mode, ui, onPhones = { phonesOpen = true })
                        }
                        Spacer(Modifier.width(dimens.gutter * 2))
                        Box(Modifier.weight(0.44f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Hero(mode, ui, Modifier.fillMaxHeight(0.86f))
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = if (layout.width == WidthClass.COMPACT) dimens.compactMargin else dimens.screenMargin)
                            .padding(bottom = dimens.gutter),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        val showPhone = areaHeight > 560.dp
                        if (showPhone) {
                            Box(Modifier.fillMaxWidth().height(areaHeight * 0.4f), contentAlignment = Alignment.Center) {
                                Hero(mode, ui, Modifier.fillMaxHeight(0.92f))
                            }
                            // Room for the phone's contact shadow.
                            Spacer(Modifier.height(dimens.gutter * 2.5f))
                        }
                        HeroText(mode, ui, actions)
                        Spacer(Modifier.height(dimens.gutter * 1.5f))
                        StatusChips(mode, ui, onPhones = { phonesOpen = true }, stacked = layout.width == WidthClass.COMPACT)
                    }
                }
            }
        }
    }
    if (phonesOpen) {
        PhonesSheet(ui, actions, onDismiss = { phonesOpen = false })
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
private fun HeroText(mode: HomeMode, ui: HomeUi, actions: HomeActions) {
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(mode, transitionSpec = {
        val duration = if (reduceMotion) 0 else CarMotion.MEDIUM
        (fadeIn(tween(duration)) + slideInVertically(tween(duration, easing = CarMotion.EmphasizedDecelerate)) { it / 12 }) togetherWith
            fadeOut(tween(duration / 2))
    }, label = "hero") { state ->
        Column(Modifier.widthIn(max = 900.dp)) { HeroCopy(state, ui, actions) }
    }
}

@Composable
private fun ColumnScope.HeroCopy(mode: HomeMode, ui: HomeUi, actions: HomeActions) {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    val dimens = CarLinkTokens.dimens
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
            stringResource(R.string.carlink_home_connecting_body, phoneName)
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
        Spacer(Modifier.height(dimens.gutter * 1.5f))
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

    Spacer(Modifier.height(dimens.gutter * 1.5f))
    Row(horizontalArrangement = Arrangement.spacedBy(dimens.targetGap), verticalAlignment = Alignment.CenterVertically) {
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
            HomeMode.CONNECTING -> GlassButton(stringResource(R.string.carlink_home_cancel), actions::disconnect,
                icon = R.drawable.carlink_ic_close)
            HomeMode.CONNECTED -> {
                IonButton(stringResource(R.string.carlink_home_return, engine), actions::returnToProjection,
                    icon = R.drawable.carlink_ic_play_arrow_filled)
                GlassButton(stringResource(R.string.carlink_home_disconnect), actions::disconnect, icon = R.drawable.carlink_ic_link_off)
            }
            HomeMode.FAILED -> FailureActions(link, actions)
        }
    }
    val offerSameWifi = ui.network?.canOfferSameWifi == true && ui.home.phones.any { it.kind == PhoneKind.IPHONE } &&
        (mode == HomeMode.READY_PHONE || mode == HomeMode.READY_EMPTY ||
            (mode == HomeMode.FAILED && link.problem == LinkProblem.SAME_WIFI_DETAILS_MISSING))
    if (offerSameWifi) {
        Spacer(Modifier.height(dimens.gutter * 1.5f))
        SameWifiCard(ui.network!!.joinedSsid!!, actions)
    }
    if (ui.connection.autoConnectPaused && (mode == HomeMode.READY_PHONE || mode == HomeMode.READY_EMPTY)) {
        Spacer(Modifier.height(dimens.targetGap))
        Row(verticalAlignment = Alignment.CenterVertically) {
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
        else -> {
            IonButton(stringResource(R.string.carlink_home_try_again), actions::retry, icon = R.drawable.carlink_ic_refresh)
            GlassButton(stringResource(R.string.carlink_home_diagnostics), { actions.navigate(Route.DIAGNOSTICS) })
        }
    }
}

/** Up to three glass chips: Connection, Network, Phone. */
@Composable
private fun StatusChips(mode: HomeMode, ui: HomeUi, onPhones: () -> Unit, stacked: Boolean = false) {
    val colors = CarLinkTokens.colors
    val scheme = MaterialTheme.colorScheme
    val bt = ui.home.bluetooth
    val connection = @Composable { modifier: Modifier ->
        GlassChip(stringResource(R.string.carlink_home_chip_connection), stringResource(when (bt) {
            BluetoothAvailability.READY -> R.string.carlink_home_bt_on
            BluetoothAvailability.OFF -> R.string.carlink_home_bt_off
            BluetoothAvailability.NO_PERMISSION -> R.string.carlink_home_bt_no_permission
            BluetoothAvailability.UNSUPPORTED -> R.string.carlink_home_bt_unsupported
        }), if (bt == BluetoothAvailability.READY) R.drawable.carlink_ic_bluetooth else R.drawable.carlink_ic_bluetooth_disabled, modifier,
            status = if (bt == BluetoothAvailability.READY) colors.success else colors.warning)
    }
    val network = @Composable { modifier: Modifier ->
        val path = if (mode == HomeMode.CONNECTED && ui.home.link.transport == Transport.USB) null else ui.network?.path
        GlassChip(stringResource(R.string.carlink_home_chip_network), when (path) {
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
        }, modifier, status = if (path == WirelessPath.SAME_WIFI) colors.success else null)
    }
    val phone = @Composable { modifier: Modifier ->
        val phones = ui.home.phones
        GlassChip(stringResource(R.string.carlink_home_chip_phone),
            ui.home.defaultPhone?.name?.let(::isolate)
                ?: if (phones.isEmpty()) stringResource(R.string.carlink_home_no_phones)
                else pluralStringResource(R.plurals.carlink_home_phone_count, phones.size, phones.size),
            com.carlink.app.ui.components.phoneIcon(ui.home.defaultPhone?.kind ?: PhoneKind.UNKNOWN), modifier,
            status = if (ui.home.defaultPhone?.connected == true) colors.success else null, onClick = onPhones)
    }
    val gap = CarLinkTokens.dimens.targetGap
    if (stacked) {
        StatusList(ui, onPhones)
    } else {
        // Equal-height chips, as tall as the tallest value.
        Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
            connection(Modifier.weight(1f).fillMaxHeight()); network(Modifier.weight(1f).fillMaxHeight()); phone(Modifier.weight(1f).fillMaxHeight())
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

/** Narrow windows: the three statuses as one glass card with aligned rows instead of three stacked chips. */
@Composable
private fun StatusList(ui: HomeUi, onPhones: () -> Unit) {
    val colors = CarLinkTokens.colors
    val dimens = CarLinkTokens.dimens
    val bt = ui.home.bluetooth
    @Composable
    fun row(eyebrow: String, value: String, icon: Int, status: Color?, onClick: (() -> Unit)? = null) {
        Row(Modifier.fillMaxWidth().heightIn(min = dimens.rowOneLine)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, Modifier.size(dimens.iconSecondary), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(dimens.gutter))
            Eyebrow(eyebrow, Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
            // The dot's space is kept on every row so values stay aligned.
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(status ?: Color.Transparent))
        }
    }
    GlassCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
        Column {
            row(stringResource(R.string.carlink_home_chip_connection), stringResource(when (bt) {
                BluetoothAvailability.READY -> R.string.carlink_home_bt_on
                BluetoothAvailability.OFF -> R.string.carlink_home_bt_off
                BluetoothAvailability.NO_PERMISSION -> R.string.carlink_home_bt_no_permission
                BluetoothAvailability.UNSUPPORTED -> R.string.carlink_home_bt_unsupported
            }), if (bt == BluetoothAvailability.READY) R.drawable.carlink_ic_bluetooth else R.drawable.carlink_ic_bluetooth_disabled,
                if (bt == BluetoothAvailability.READY) colors.success else colors.warning)
            androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = dimens.gutter), color = colors.glassStrokeBottom)
            row(stringResource(R.string.carlink_home_chip_network), networkLabel(ui), networkIcon(ui.network?.path),
                if (ui.network?.path == WirelessPath.SAME_WIFI) colors.success else null)
            androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = dimens.gutter), color = colors.glassStrokeBottom)
            val phones = ui.home.phones
            row(stringResource(R.string.carlink_home_chip_phone), ui.home.defaultPhone?.name?.let(::isolate)
                ?: if (phones.isEmpty()) stringResource(R.string.carlink_home_no_phones)
                else pluralStringResource(R.plurals.carlink_home_phone_count, phones.size, phones.size),
                com.carlink.app.ui.components.phoneIcon(ui.home.defaultPhone?.kind ?: PhoneKind.UNKNOWN),
                if (ui.home.defaultPhone?.connected == true) colors.success else null, onPhones)
        }
    }
}

@Composable
private fun networkLabel(ui: HomeUi): String = when (ui.network?.path) {
    WirelessPath.SAME_WIFI -> ui.network.ssid?.let { stringResource(R.string.carlink_home_net_same_wifi_named, isolate(it)) }
        ?: stringResource(R.string.carlink_home_net_same_wifi)
    WirelessPath.CAR_HOTSPOT -> stringResource(R.string.carlink_home_net_hotspot)
    WirelessPath.WIFI_DIRECT -> stringResource(R.string.carlink_home_net_direct)
    null -> stringResource(R.string.carlink_home_path_usb)
}

private fun networkIcon(path: WirelessPath?): Int = when (path) {
    WirelessPath.SAME_WIFI -> R.drawable.carlink_ic_wifi
    WirelessPath.CAR_HOTSPOT -> R.drawable.carlink_ic_wifi_tethering
    WirelessPath.WIFI_DIRECT -> R.drawable.carlink_ic_p2p
    null -> R.drawable.carlink_ic_usb
}

/** One-time offer to use the car's current Wi-Fi for wireless CarPlay (the fastest path). */
@Composable
private fun SameWifiCard(ssid: String, actions: HomeActions) {
    val dimens = CarLinkTokens.dimens
    val scheme = MaterialTheme.colorScheme
    var password by rememberSaveable { mutableStateOf("") }
    var reveal by rememberSaveable { mutableStateOf(false) }
    val valid = password.isEmpty() || password.length in 8..63
    GlassCard(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.carlink_ic_wifi), null, Modifier.size(dimens.iconSecondary), tint = CarLinkTokens.colors.success)
                Spacer(Modifier.width(dimens.targetGap))
                Text(stringResource(R.string.carlink_home_same_wifi_title, isolate(ssid)), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.carlink_home_same_wifi_body), style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant)
            androidx.compose.material3.OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true,
                label = { Text(stringResource(R.string.carlink_home_same_wifi_password)) },
                isError = !valid,
                supportingText = if (!valid) {{ Text(stringResource(R.string.carlink_home_same_wifi_invalid)) }} else null,
                visualTransformation = if (reveal) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                trailingIcon = {
                    androidx.compose.material3.IconButton(onClick = { reveal = !reveal }) {
                        Icon(painterResource(R.drawable.carlink_ic_lock), stringResource(R.string.carlink_home_same_wifi_show))
                    }
                },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            IonButton(stringResource(R.string.carlink_home_same_wifi_save), {
                if (valid) actions.saveSameWifi(ssid, password)
            }, icon = R.drawable.carlink_ic_check)
        }
    }
}
