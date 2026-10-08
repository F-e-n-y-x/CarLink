package com.carlink.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.app.R
import com.carlink.app.core.LinkStage
import com.carlink.app.core.PhoneKind
import com.carlink.app.detect.BluetoothAvailability
import com.carlink.app.detect.PairedPhone
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.ConnectionPrefs
import com.carlink.app.ui.HomeState
import com.carlink.app.ui.Route
import com.carlink.app.ui.components.CarTopBar
import com.carlink.app.ui.components.IconAction
import com.carlink.app.ui.components.StatusChip
import com.carlink.app.ui.components.focusRing
import com.carlink.app.ui.components.transportIcon
import com.carlink.app.ui.components.transportName
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.HeightClass
import com.carlink.app.ui.theme.UiProfile
import com.carlink.app.ui.theme.WidthClass

/**
 * Home (DESIGN_SPEC §6.1): answers "is my phone connected, and if not, what do I tap?" in one
 * glance, and switches phones in one tap.
 */
@Composable
fun HomeScreen(vm: CarLinkViewModel, navigate: (Route) -> Unit) {
    val home by vm.home.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val actions = remember(vm, navigate) {
        HomeActions(
            connect = vm::connect,
            switchTo = { phone ->
                // End the running session first so the new phone starts cleanly.
                vm.disconnect()
                vm.connect(phone)
            },
            setPhoneKind = vm::setPhoneKind,
            returnToProjection = vm::returnToProjection,
            disconnect = vm::disconnect,
            retry = vm::retry,
            refreshPhones = vm::refreshPhones,
            resumeAutoConnect = vm::resumeAutoConnect,
            openBluetoothSettings = vm::openBluetoothSettings,
            openAppSettings = vm::openAppSettings,
            navigate = navigate,
        )
    }
    HomeContent(home, connection, actions)
}

/** Which arrangement the window gets (§6.1.1–6.1.3, plus phones and tablets). */
private enum class HomeLayout { TWO_COLUMN, STACKED, COMPACT }

/** Stateless Home, so previews and screenshot tests can render every state. */
@Composable
internal fun HomeContent(home: HomeState, connection: ConnectionPrefs, actions: HomeActions) {
    val layout = CarLinkTokens.layout
    val dimens = CarLinkTokens.dimens
    val homeLayout = when {
        layout.isLandscape && layout.width >= WidthClass.MEDIUM -> HomeLayout.TWO_COLUMN
        layout.width == WidthClass.COMPACT -> HomeLayout.COMPACT
        else -> HomeLayout.STACKED
    }
    val shortHeight = layout.height == HeightClass.COMPACT
    val margin = if (homeLayout == HomeLayout.COMPACT) dimens.compactMargin else dimens.screenMargin

    // Phone taps: unknown phones ask their type first; a live session asks before switching.
    var askKindFor by remember { mutableStateOf<PairedPhone?>(null) }
    var confirmSwitchTo by remember { mutableStateOf<PairedPhone?>(null) }
    val link = home.link
    val request: (PairedPhone) -> Unit = { phone ->
        when {
            link.stage == LinkStage.CONNECTED && link.isSessionPhone(phone) -> actions.returnToProjection()
            link.isActive && !link.isSessionPhone(phone) -> confirmSwitchTo = phone
            link.isActive -> actions.navigate(Route.CONNECTING)
            else -> actions.connect(phone)
        }
    }
    val onPhoneClick: (PairedPhone) -> Unit = { phone ->
        if (phone.kind == PhoneKind.UNKNOWN) askKindFor = phone else request(phone)
    }

    // Chips: inline when there is room, a wrapped row on portrait, hidden on Compact (§6.1.3).
    // The top bar only has room for them at full landscape width (or with handheld sizes).
    val chipsInline = homeLayout == HomeLayout.TWO_COLUMN &&
        (layout.width == WidthClass.LARGE || CarLinkTokens.profile == UiProfile.HANDHELD)
    val chipsBelow = homeLayout != HomeLayout.COMPACT && !chipsInline
    val hero = heroModel(home)

    // The route host draws no Surface, so set the content color for every Text and Icon here.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.fillMaxSize()) {
        CarTopBar(
            title = stringResource(R.string.app_name),
            showBrand = true,
            chips = { if (chipsInline) StatusChips(home, connection) },
            actions = {
                IconAction(R.drawable.carlink_ic_settings, stringResource(R.string.carlink_home_settings),
                    { actions.navigate(Route.SETTINGS) }, Modifier.focusRing(CircleShape))
            },
        )
        if (chipsBelow) {
            ChipsRow(home, connection, Modifier.padding(horizontal = margin).padding(bottom = dimens.targetGap))
        }
        when (homeLayout) {
            HomeLayout.TWO_COLUMN -> TwoColumn(hero, home, connection, actions, onPhoneClick, margin, shortHeight)
            HomeLayout.STACKED -> Stacked(hero, home, connection, actions, onPhoneClick, margin)
            HomeLayout.COMPACT -> CompactColumn(hero, home, connection, actions, onPhoneClick, margin)
        }
    }
    }

    askKindFor?.let { phone ->
        PhoneKindDialog(
            phone,
            onChoose = { kind ->
                askKindFor = null
                actions.setPhoneKind(phone, kind)
                request(phone.copy(kind = kind))
            },
            onDismiss = { askKindFor = null },
        )
    }
    confirmSwitchTo?.let { phone ->
        SwitchPhoneDialog(
            phone, link.engine,
            onConfirm = {
                confirmSwitchTo = null
                actions.switchTo(phone)
            },
            onDismiss = { confirmSwitchTo = null },
        )
    }
}

/** Landscape: hero 7 columns, phones 5 (§5.3); each column scrolls on its own. */
@Composable
private fun TwoColumn(
    hero: HeroModel,
    home: HomeState,
    connection: ConnectionPrefs,
    actions: HomeActions,
    onPhoneClick: (PairedPhone) -> Unit,
    margin: Dp,
    shortHeight: Boolean,
) {
    val dimens = CarLinkTokens.dimens
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Row(
            // Ultrawide (≈2160dp) keeps a readable 1600dp content width, centered (§5.0).
            Modifier.widthIn(max = 1600.dp).fillMaxSize().padding(horizontal = margin),
            horizontalArrangement = Arrangement.spacedBy(dimens.gutter),
        ) {
            BoxWithConstraints(Modifier.weight(7f).fillMaxSize()) {
                val minHeight = maxHeight - margin
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    HeroCard(
                        hero, connection,
                        if (shortHeight) HeroVariant.LANDSCAPE_SHORT else HeroVariant.LANDSCAPE,
                        actions,
                        Modifier.heightIn(min = if (shortHeight) 0.dp else minHeight),
                    )
                    Spacer(Modifier.padding(bottom = margin))
                }
            }
            LazyColumn(
                Modifier.weight(5f).fillMaxSize(),
                contentPadding = PaddingValues(bottom = margin),
                verticalArrangement = Arrangement.spacedBy(dimens.targetGap),
            ) {
                item(key = "header") { PhonesHeader(stringResource(R.string.carlink_home_phones_title)) }
                phoneItems(home, home.phones, onPhoneClick, actions)
                if (home.phones.isNotEmpty()) item(key = "hint") { PhonesHint() }
            }
        }
    }
}

/** Portrait tablet / head unit: hero on top, then all phones, centered to a readable width. */
@Composable
private fun Stacked(
    hero: HeroModel,
    home: HomeState,
    connection: ConnectionPrefs,
    actions: HomeActions,
    onPhoneClick: (PairedPhone) -> Unit,
    margin: Dp,
) {
    val dimens = CarLinkTokens.dimens
    val itemWidth = Modifier.widthIn(max = dimens.contentMaxWidth).fillMaxWidth()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = margin, end = margin, bottom = margin),
        verticalArrangement = Arrangement.spacedBy(dimens.targetGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "hero") { HeroCard(hero, connection, HeroVariant.PORTRAIT, actions, itemWidth) }
        item(key = "header") { PhonesHeader(stringResource(R.string.carlink_home_phones_title), itemWidth.padding(top = dimens.targetGap)) }
        phoneItems(home, home.phones, onPhoneClick, actions, itemWidth)
        if (home.phones.isNotEmpty()) item(key = "hint") { PhonesHint(itemWidth) }
    }
}

/** Compact (⅓ split, phone portrait): condensed hero, two other phones, then "All phones (n)". */
@Composable
private fun CompactColumn(
    hero: HeroModel,
    home: HomeState,
    connection: ConnectionPrefs,
    actions: HomeActions,
    onPhoneClick: (PairedPhone) -> Unit,
    margin: Dp,
) {
    val dimens = CarLinkTokens.dimens
    var expanded by rememberSaveable { mutableStateOf(false) }
    // The hero already shows the phone it is about; the list shows the others.
    val heroPhone = home.phones.firstOrNull { phone ->
        if (home.link.stage == LinkStage.IDLE) phone == home.defaultPhone else phone.name == home.link.phoneName
    }
    val others = home.phones.filter { it != heroPhone }
    val visible = if (expanded || others.size <= COMPACT_PHONES) others else others.take(COMPACT_PHONES)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = margin, end = margin, bottom = margin),
        verticalArrangement = Arrangement.spacedBy(dimens.targetGap),
    ) {
        item(key = "hero") { HeroCard(hero, connection, HeroVariant.COMPACT, actions) }
        if (home.phones.isEmpty()) {
            item(key = "empty") { PhonesEmpty(home.bluetooth, actions.openBluetoothSettings) }
        } else if (others.isNotEmpty()) {
            item(key = "header") {
                PhonesHeader(stringResource(
                    if (heroPhone != null) R.string.carlink_home_other_phones_title else R.string.carlink_home_phones_title),
                    Modifier.padding(top = 8.dp))
            }
            items(visible, key = { "phone-" + it.address }) { phone ->
                PhoneRow(phone, home.link, { onPhoneClick(phone) }, Modifier.animateItem())
            }
            if (others.size > COMPACT_PHONES) {
                item(key = "toggle") { AllPhonesToggle(others.size, expanded, { expanded = !expanded }) }
            }
        }
    }
}

private const val COMPACT_PHONES = 2

private fun LazyListScope.phoneItems(
    home: HomeState,
    phones: List<PairedPhone>,
    onPhoneClick: (PairedPhone) -> Unit,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    if (phones.isEmpty()) {
        item(key = "empty") { PhonesEmpty(home.bluetooth, actions.openBluetoothSettings, modifier) }
        return
    }
    items(phones, key = { "phone-" + it.address }) { phone ->
        PhoneRow(phone, home.link, { onPhoneClick(phone) }, modifier.animateItem())
    }
}

// ---- Status chips -----------------------------------------------------------------------

@Composable
private fun RowScope.StatusChips(home: HomeState, connection: ConnectionPrefs) {
    StatusChipSet(home, connection)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipsRow(home: HomeState, connection: ConnectionPrefs, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChipSet(home, connection)
    }
}

/** Only the radios that matter; a blocking one turns warning with its own glyph (§6.1.1). */
@Composable
private fun StatusChipSet(home: HomeState, connection: ConnectionPrefs) {
    val colors = CarLinkTokens.colors
    when (home.bluetooth) {
        BluetoothAvailability.READY ->
            StatusChip(stringResource(R.string.carlink_home_chip_bluetooth_on), R.drawable.carlink_ic_bluetooth)
        BluetoothAvailability.OFF ->
            StatusChip(stringResource(R.string.carlink_home_chip_bluetooth_off), R.drawable.carlink_ic_bluetooth_disabled, tint = colors.warning)
        BluetoothAvailability.NO_PERMISSION ->
            StatusChip(stringResource(R.string.carlink_home_chip_bluetooth_no_access), R.drawable.carlink_ic_bluetooth_disabled, tint = colors.warning)
        BluetoothAvailability.UNSUPPORTED -> Unit
    }
    val transport = home.link.transport
    if (home.link.stage != LinkStage.IDLE && home.link.stage != LinkStage.FAILED && transport != null) {
        StatusChip(transportName(transport), transportIcon(transport))
    } else if (connection.autoConnectUsb) {
        StatusChip(stringResource(R.string.carlink_home_chip_usb_auto), R.drawable.carlink_ic_usb)
    }
}
