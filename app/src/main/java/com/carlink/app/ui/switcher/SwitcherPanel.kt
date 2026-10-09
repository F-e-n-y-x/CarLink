package com.carlink.app.ui.switcher

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.switcher.Edge
import com.carlink.app.ui.components.CarLinkMark
import com.carlink.app.ui.components.PhoneAvatar
import com.carlink.app.ui.components.engineName
import com.carlink.app.ui.components.formatElapsed
import com.carlink.app.ui.components.isolate
import com.carlink.app.ui.components.rememberElapsedMillis
import com.carlink.app.ui.premium.Eyebrow
import com.carlink.app.ui.premium.GlassButton
import com.carlink.app.ui.premium.GlassCard
import com.carlink.app.ui.premium.GlyphDisc
import com.carlink.app.ui.settings.NextConnectionTag
import com.carlink.app.ui.settings.RoundGlassButton
import com.carlink.app.ui.settings.RowGlyph
import com.carlink.app.ui.settings.SelectableGlass
import com.carlink.app.ui.settings.connectionMethodLabel
import com.carlink.app.ui.settings.methodIcon
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.CarMotion
import com.carlink.app.ui.theme.LocalReduceMotion
import com.carlink.app.ui.theme.UiProfile
import com.carlink.core.core.Engine
import com.carlink.core.core.PhoneKind
import com.carlink.core.core.Transport
import com.carlink.core.detect.PairedPhone
import com.carlink.core.settings.ConnectionMethod
import com.carlink.core.settings.WirelessPath

enum class SwitcherPage { MAIN, CONNECTION_TYPE }

/** Everything the switcher panel shows; built by SwitcherController, faked by the gallery. */
@Immutable
data class SwitcherUi(
    val phoneName: String?,
    val engine: Engine,
    val transport: Transport,
    /** The wireless path of the running session; null for USB. */
    val path: WirelessPath?,
    /** elapsedRealtime when the session connected (as first seen by the switcher). */
    val connectedSince: Long?,
    /** Paired phones of a known kind other than the one projecting. */
    val otherPhones: List<PairedPhone>,
    /** UI only: the kinds of phone plugged in by USB, for "Plugged in (USB)" labels. */
    val usbKinds: Set<PhoneKind> = emptySet(),
    /** Address of the phone being switched to, while the switch starts. */
    val switchingTo: String? = null,
    /** The driver's connection type; used from the next connection. */
    val method: ConnectionMethod = ConnectionMethod.AUTOMATIC,
    val page: SwitcherPage = SwitcherPage.MAIN,
    /** The edge the bubble rests on; the panel opens on the same side. */
    val edge: Edge = Edge.RIGHT,
    /** System bars and cut-outs: the panel window spans the whole screen. */
    val safePadding: PaddingValues = PaddingValues(0.dp),
)

/** What the panel can do; SwitcherController implements it. */
interface SwitcherActions {
    fun switchTo(phone: PairedPhone) {}
    fun openHome() {}
    fun showPage(page: SwitcherPage) {}
    fun setConnectionMethod(method: ConnectionMethod) {}
    fun disconnect() {}
    fun close() {}
}

/** The bubble: a round glass button with the CarLink mark. Fading is done by its window. */
@Composable
fun SwitcherBubble(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    // Over a moving projection the faint Glass fill would vanish: an opaque-ish graphite disc
    // with Glass's top-lit edge keeps the mark readable on any picture.
    Box(
        modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.86f))
            .border(com.carlink.app.ui.premium.glassBorder(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CarLinkMark(Modifier.fillMaxSize(0.5f))
    }
}

/** The open switcher: a scrim (tap to close) and the panel docked on the bubble's side. */
@Composable
fun SwitcherOverlay(ui: SwitcherUi, actions: SwitcherActions, modifier: Modifier = Modifier) {
    val dimens = CarLinkTokens.dimens
    val car = CarLinkTokens.profile == UiProfile.CAR
    val noRipple = remember { MutableInteractionSource() }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(noRipple, indication = null, onClick = actions::close)
            .padding(ui.safePadding)
            .padding(dimens.gutter),
    ) {
        val width = (if (car) 600.dp else 420.dp).coerceAtMost(maxWidth)
        SwitcherCard(ui, actions, Modifier
            .align(if (ui.edge == Edge.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
            .width(width)
            .heightIn(max = maxHeight))
    }
}

@Composable
private fun SwitcherCard(ui: SwitcherUi, actions: SwitcherActions, modifier: Modifier) {
    val colors = CarLinkTokens.colors
    val shape = MaterialTheme.shapes.extraLarge
    val reduceMotion = LocalReduceMotion.current
    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(com.carlink.app.ui.premium.glassBorder(), shape)
            // Touches on the card never reach the scrim behind it.
            .pointerInput(Unit) { detectTapGestures {} },
    ) {
        Crossfade(ui.page, animationSpec = tween(if (reduceMotion) 0 else CarMotion.MEDIUM), label = "page") { page ->
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(CarLinkTokens.dimens.gutter * 1.25f),
            ) {
                when (page) {
                    SwitcherPage.MAIN -> MainPage(ui, actions)
                    SwitcherPage.CONNECTION_TYPE -> ConnectionTypePage(ui, actions)
                }
            }
        }
    }
}

@Composable
private fun MainPage(ui: SwitcherUi, actions: SwitcherActions) {
    val dimens = CarLinkTokens.dimens
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    val switching = ui.switchingTo != null
    // A wired session's own phone is the plugged-in one of its kind; other phones of that kind are not.
    val pluggedOthers = if (ui.transport == Transport.USB) ui.usbKinds - (if (ui.engine == Engine.CARPLAY) PhoneKind.IPHONE
        else PhoneKind.ANDROID) else ui.usbKinds

    // Header: who projects, how, and for how long.
    Row(verticalAlignment = Alignment.CenterVertically) {
        com.carlink.app.ui.premium.IconTile(if (ui.engine == Engine.CARPLAY) R.drawable.carlink_ic_phone_iphone
            else R.drawable.carlink_ic_android, tint = colors.success, size = dimens.avatarRow)
        Spacer(Modifier.width(dimens.gutter))
        Column(Modifier.weight(1f)) {
            Text(isolate(ui.phoneName ?: engineName(ui.engine)), style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() })
            Text(engineName(ui.engine), style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            // Cable or wireless as a badge: a plug with "Cable", or the path's glyph with "Wireless · Wi-Fi Direct".
            com.carlink.app.ui.premium.TransportBadge(com.carlink.app.ui.premium.transportIcon(ui.transport, ui.path),
                com.carlink.app.ui.premium.transportLabel(ui.transport, ui.path), tint = colors.success,
                style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(if (CarLinkTokens.profile == UiProfile.CAR) 12.dp else 8.dp).clip(CircleShape).background(colors.success))
                Spacer(Modifier.width(8.dp))
                val elapsed by rememberElapsedMillis(ui.connectedSince)
                Text(stringResource(R.string.carlink_switcher_connected_for, formatElapsed(elapsed)),
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurface, maxLines = 1)
            }
        }
        Spacer(Modifier.width(12.dp))
        RoundGlassButton(R.drawable.carlink_ic_close, stringResource(R.string.carlink_switcher_close), actions::close)
    }

    Spacer(Modifier.height(dimens.gutter * 1.25f))
    Eyebrow(stringResource(R.string.carlink_switcher_switch_title))
    Spacer(Modifier.height(10.dp))
    if (ui.otherPhones.isEmpty()) {
        GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
            Column {
                Text(stringResource(R.string.carlink_switcher_no_phones), style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface)
                Text(stringResource(R.string.carlink_switcher_no_phones_hint), style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant)
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
            ui.otherPhones.forEach { phone ->
                PhoneRow(phone, switchingThis = ui.switchingTo == phone.address, enabled = !switching,
                    plugged = phone.kind in pluggedOthers, onClick = { actions.switchTo(phone) })
            }
        }
    }

    Spacer(Modifier.height(dimens.gutter))
    // Connection type: the same choices as Home's Network sheet, for the next connection.
    GlassCard(Modifier.fillMaxWidth().alpha(if (switching) 0.5f else 1f), shape = RoundedCornerShape(28.dp),
        onClick = { if (!switching) actions.showPage(SwitcherPage.CONNECTION_TYPE) },
        contentPadding = PaddingValues(horizontal = dimens.gutter, vertical = 12.dp)) {
        Row(Modifier.defaultMinSize(minHeight = dimens.minTouch), verticalAlignment = Alignment.CenterVertically) {
            RowGlyph(methodIcon(ui.method))
            Spacer(Modifier.width(dimens.gutter))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.carlink_home_network_sheet_title), style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface, maxLines = 1)
                Text(connectionMethodLabel(ui.method), style = MaterialTheme.typography.titleSmall, color = scheme.primary, maxLines = 1)
            }
            Icon(painterResource(R.drawable.carlink_ic_chevron_right), null, Modifier.size(dimens.iconTertiary + 4.dp),
                tint = scheme.onSurfaceVariant)
        }
    }

    Spacer(Modifier.height(dimens.gutter))
    val home = @Composable { m: Modifier ->
        GlassButton(stringResource(R.string.carlink_switcher_home), { if (!switching) actions.openHome() }, m,
            icon = R.drawable.carlink_ic_home)
    }
    val disconnect = @Composable { m: Modifier ->
        GlassButton(stringResource(R.string.carlink_home_disconnect), { if (!switching) actions.disconnect() }, m,
            icon = R.drawable.carlink_ic_link_off)
    }
    // Car type is large enough that two pills side by side would wrap "CarLink Home"; stack them.
    if (CarLinkTokens.profile == UiProfile.CAR) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
            home(Modifier.fillMaxWidth())
            disconnect(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
            home(Modifier.weight(1f))
            disconnect(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PhoneRow(phone: PairedPhone, switchingThis: Boolean, enabled: Boolean, plugged: Boolean, onClick: () -> Unit) {
    val dimens = CarLinkTokens.dimens
    val scheme = MaterialTheme.colorScheme
    val switchingText = stringResource(R.string.carlink_switcher_switching)
    GlassCard(
        Modifier.fillMaxWidth().alpha(if (enabled || switchingThis) 1f else 0.5f)
            .then(if (switchingThis) Modifier.semantics { stateDescription = switchingText } else Modifier),
        shape = RoundedCornerShape(28.dp),
        onClick = if (enabled) onClick else null,
        contentPadding = PaddingValues(horizontal = dimens.gutter, vertical = 12.dp),
    ) {
        Row(Modifier.defaultMinSize(minHeight = dimens.minTouch), verticalAlignment = Alignment.CenterVertically) {
            com.carlink.app.ui.premium.IconTile(com.carlink.app.ui.components.phoneIcon(phone.kind),
                tint = if (switchingThis) scheme.primary else null, size = dimens.avatarRow)
            Spacer(Modifier.width(dimens.gutter))
            Column(Modifier.weight(1f)) {
                Text(isolate(phone.name), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (switchingThis) switchingText else phone.kind.engine?.let { engineName(it) }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (switchingThis) scheme.primary else scheme.onSurfaceVariant, maxLines = 1)
                if (!switchingThis) {
                    com.carlink.app.ui.premium.TransportInline(
                        if (plugged) R.drawable.carlink_ic_usb else com.carlink.app.ui.premium.transportIcon(Transport.WIRELESS),
                        stringResource(if (plugged) R.string.carlink_transport_plugged_in else R.string.carlink_transport_wireless),
                        if (plugged) scheme.primary else scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            if (switchingThis && !LocalReduceMotion.current) {
                CircularProgressIndicator(Modifier.size(dimens.iconSecondary), color = scheme.primary, strokeWidth = 3.dp)
            } else if (!switchingThis) {
                Text(stringResource(R.string.carlink_home_switch_action), style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) scheme.primary else scheme.onSurfaceVariant, maxLines = 1)
            } else {
                Icon(painterResource(R.drawable.carlink_ic_sync), null, Modifier.size(dimens.iconSecondary), tint = scheme.primary)
            }
        }
    }
}

@Composable
private fun ConnectionTypePage(ui: SwitcherUi, actions: SwitcherActions) {
    val dimens = CarLinkTokens.dimens
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundGlassButton(R.drawable.carlink_ic_arrow_back, stringResource(R.string.carlink_switcher_back),
            { actions.showPage(SwitcherPage.MAIN) })
        Spacer(Modifier.width(dimens.gutter))
        Text(stringResource(R.string.carlink_home_network_sheet_title), style = MaterialTheme.typography.headlineSmall,
            color = scheme.onSurface, modifier = Modifier.weight(1f).semantics { heading() })
    }
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.carlink_switcher_next_connection), style = MaterialTheme.typography.bodyMedium,
        color = scheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
    NextConnectionTag()
    Spacer(Modifier.height(dimens.gutter))
    Column(verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
        ConnectionMethod.entries.forEach { method ->
            SelectableGlass(method == ui.method, { actions.setConnectionMethod(method) }, Modifier.fillMaxWidth(),
                role = Role.RadioButton) {
                Row(Modifier.defaultMinSize(minHeight = dimens.minTouch).padding(horizontal = dimens.gutter, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RowGlyph(methodIcon(method), tint = if (method == ui.method) scheme.primary else scheme.onSurface)
                    Spacer(Modifier.width(dimens.gutter))
                    Column(Modifier.weight(1f)) {
                        Text(connectionMethodLabel(method), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                        Text(stringResource(methodDetail(method)), style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    Icon(painterResource(if (method == ui.method) R.drawable.carlink_ic_radio_button_checked
                        else R.drawable.carlink_ic_radio_button_unchecked), null, Modifier.size(dimens.iconSecondary),
                        tint = if (method == ui.method) scheme.primary else scheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun methodDetail(method: ConnectionMethod): Int = when (method) {
    ConnectionMethod.AUTOMATIC -> R.string.carlink_settings_method_auto_detail
    ConnectionMethod.SAME_WIFI -> R.string.carlink_settings_method_same_wifi_detail
    ConnectionMethod.CAR_HOTSPOT -> R.string.carlink_settings_method_hotspot_detail
    ConnectionMethod.WIFI_DIRECT -> R.string.carlink_settings_method_direct_detail
}

/** The phones the panel offers: paired, of a known kind, and not the one projecting. */
fun switchCandidates(phones: List<PairedPhone>, currentAddress: String?, currentName: String?): List<PairedPhone> =
    phones.filter { phone ->
        phone.kind != PhoneKind.UNKNOWN &&
            !(currentAddress != null && phone.address.equals(currentAddress, ignoreCase = true)) &&
            // USB sessions have no address; the name is all CarLink knows.
            !(currentAddress == null && currentName != null && phone.name == currentName)
    }
