package com.carlink.app.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.core.Transport
import com.carlink.app.detect.BluetoothAvailability
import com.carlink.app.settings.WirelessPath
import com.carlink.app.ui.components.isolate
import com.carlink.app.ui.components.phoneIcon
import com.carlink.app.ui.premium.GlassCard
import com.carlink.app.ui.premium.IconTile
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.UiProfile
import java.util.Locale

/** One cell of Home's status bar: Bluetooth, the wireless path, the phone. */
internal class StatusItem(val label: String, val value: String, @DrawableRes val icon: Int, val status: Color?,
                          val onClick: (() -> Unit)? = null)

/** What tapping each status opens: Bluetooth settings, the connection-type picker, the phone list. */
internal class StatusTaps(val onBluetooth: () -> Unit, val onNetwork: () -> Unit, val onPhones: () -> Unit)

@Composable
internal fun statusItems(mode: HomeMode, ui: HomeUi, taps: StatusTaps): List<StatusItem> {
    val colors = CarLinkTokens.colors
    val bt = ui.home.bluetooth
    val link = ui.home.link
    // A session in progress or running: its own transport and phone, not the idle defaults.
    val inSession = mode == HomeMode.CONNECTING || mode == HomeMode.NOT_ANSWERING || mode == HomeMode.CONNECTED
    val path = if (inSession && link.transport == Transport.USB) null else ui.network?.path
    val phones = ui.home.phones
    val phone = ui.home.defaultPhone
    // USB sessions have no Bluetooth address, so the paired list may not hold the phone; its name still does.
    val sessionPhone = link.phoneName?.takeIf { inSession }
    val sessionKind = when (link.engine) {
        com.carlink.app.core.Engine.CARPLAY -> com.carlink.app.core.PhoneKind.IPHONE
        com.carlink.app.core.Engine.ANDROID_AUTO -> com.carlink.app.core.PhoneKind.ANDROID
        null -> com.carlink.app.core.PhoneKind.UNKNOWN
    }
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
            null -> stringResource(R.string.carlink_transport_usb_cable)
        }, when (path) {
            WirelessPath.SAME_WIFI -> R.drawable.carlink_ic_wifi
            WirelessPath.CAR_HOTSPOT -> R.drawable.carlink_ic_wifi_tethering
            WirelessPath.WIFI_DIRECT -> R.drawable.carlink_ic_p2p
            null -> R.drawable.carlink_ic_usb
        }, if (path == WirelessPath.SAME_WIFI) colors.success else null, taps.onNetwork),
        if (sessionPhone != null) {
            StatusItem(stringResource(R.string.carlink_home_chip_phone), isolate(sessionPhone), phoneIcon(sessionKind),
                if (mode == HomeMode.CONNECTED) colors.success else null, taps.onPhones)
        } else {
            StatusItem(stringResource(R.string.carlink_home_chip_phone),
                phone?.name?.let(::isolate)
                    ?: if (phones.isEmpty()) stringResource(R.string.carlink_home_no_phones)
                    else pluralStringResource(R.plurals.carlink_home_phone_count, phones.size, phones.size),
                phone?.let { phoneIcon(it.kind) } ?: R.drawable.carlink_ic_smartphone,
                if (phone?.connected == true) colors.success else null, taps.onPhones)
        },
    )
}

private val car: Boolean @Composable get() = CarLinkTokens.profile == UiProfile.CAR

/** Panel corner radius: a large automotive radius that still reads as a bar, not a pill. */
@Composable
internal fun statusPanelShape() = RoundedCornerShape(if (car) 32.dp else 24.dp)

/**
 * One status cell: an icon tile, a tracked label with the status dot, the value, and a chevron
 * that says the cell opens something. Values wrap to a second line rather than being cut.
 */
@Composable
private fun StatusCell(item: StatusItem, modifier: Modifier = Modifier) {
    val dimens = CarLinkTokens.dimens
    val colors = CarLinkTokens.colors
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .then(item.onClick?.let { Modifier.clickable(role = Role.Button, onClick = it) } ?: Modifier)
            .padding(horizontal = dimens.gutter, vertical = if (car) 20.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(item.icon, tint = item.status, size = cellTile())
        Spacer(Modifier.width(cellGap()))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (car) 6.dp else 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.label.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceTertiary, maxLines = 1)
                if (item.status != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(if (car) 8.dp else 6.dp).clip(CircleShape).background(item.status))
                }
            }
            Text(item.value, style = cellValueStyle(), color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (item.onClick != null) {
            Spacer(Modifier.width(12.dp))
            Icon(painterResource(R.drawable.carlink_ic_chevron_right), null, Modifier.size(dimens.iconTertiary),
                tint = colors.onSurfaceTertiary)
        }
    }
}

@Composable private fun cellTile(): Dp = if (car) 56.dp else 40.dp
@Composable private fun cellGap(): Dp = if (car) 20.dp else 14.dp
@Composable private fun cellValueStyle() = if (car) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall

/**
 * The status bar docked at the bottom of Home (RESEARCH_HOME_V2 §4): one glass panel split by
 * hairlines into three cells. Each cell gets the width its content needs when they all fit, and
 * a fair share otherwise, so a short value ("On") never takes room a phone name needs.
 */
@Composable
internal fun StatusPanel(items: List<StatusItem>, modifier: Modifier = Modifier) {
    val dimens = CarLinkTokens.dimens
    val colors = CarLinkTokens.colors
    val measurer = rememberTextMeasurer()
    val value = cellValueStyle()
    val label = MaterialTheme.typography.labelSmall
    val tile = cellTile()
    val gap = cellGap()
    val natural = with(LocalDensity.current) {
        items.map { item ->
            val valueW = measurer.measure(item.value, value, maxLines = 1).size.width
            val labelW = measurer.measure(item.label.uppercase(Locale.ROOT), label, maxLines = 1).size.width + 16.dp.roundToPx()
            maxOf(valueW, labelW) + (tile + gap + dimens.gutter * 2 + 12.dp + dimens.iconTertiary).roundToPx()
        }
    }
    GlassCard(modifier.fillMaxWidth(), shape = statusPanelShape(), contentPadding = PaddingValues(0.dp)) {
        Layout(
            content = {
                items.forEach { StatusCell(it) }
                repeat(items.size - 1) { Box(Modifier.background(colors.hairline)) }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { measurables, constraints ->
            val n = items.size
            val divider = 1.dp.roundToPx()
            val widths = chipWidths(natural, constraints.maxWidth - divider * (n - 1))
            val cells = measurables.take(n)
            val height = cells.indices.maxOf { cells[it].maxIntrinsicHeight(widths[it]) }
            val placeables = cells.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i], height)) }
            val lineH = (height * 0.46f).toInt()
            val lines = measurables.drop(n).map { it.measure(Constraints.fixed(divider, lineH)) }
            layout(constraints.maxWidth, height) {
                var x = 0
                placeables.forEachIndexed { i, p ->
                    p.place(x, 0)
                    x += p.width
                    if (i < lines.size) { lines[i].place(x, (height - lineH) / 2); x += divider }
                }
            }
        }
    }
}

/** Narrow windows: the same cells stacked in one glass panel, split by hairlines. */
@Composable
internal fun StatusRows(items: List<StatusItem>, modifier: Modifier = Modifier) {
    val dimens = CarLinkTokens.dimens
    val colors = CarLinkTokens.colors
    GlassCard(modifier.fillMaxWidth(), shape = statusPanelShape(), contentPadding = PaddingValues(0.dp)) {
        Column {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    Box(Modifier.fillMaxWidth().padding(start = dimens.gutter + cellTile() + cellGap(), end = dimens.gutter)
                        .height(1.dp).background(colors.hairline))
                }
                StatusCell(item, Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Short windows: the three statuses on one glass pill. Values are shown when they all fit on the
 * line; otherwise Bluetooth and Network shrink to their icons (named for TalkBack) and only the
 * phone keeps its name. Each part opens what its cell opens.
 */
@Composable
internal fun StatusStrip(items: List<StatusItem>, modifier: Modifier = Modifier) {
    val dimens = CarLinkTokens.dimens
    val colors = CarLinkTokens.colors
    val style = MaterialTheme.typography.titleSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val icon = dimens.iconTertiary + 4.dp
    val iconGap = 10.dp
    val gap = dimens.gutter
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val text = with(density) { items.sumOf { measurer.measure(it.value, style, maxLines = 1).size.width }.toDp() }
        val fixed = gap * 2 + (icon + iconGap) * items.size + (gap * 2 + 1.dp) * (items.size - 1)
        val labels = text + fixed <= maxWidth
        GlassCard(Modifier.heightIn(min = dimens.minTouch), shape = CircleShape,
            contentPadding = PaddingValues(horizontal = gap, vertical = 8.dp)) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gap)) {
                items.forEachIndexed { index, item ->
                    if (index > 0) Box(Modifier.width(1.dp).height(icon).background(colors.hairline))
                    val showValue = labels || index == items.lastIndex
                    Row((if (showValue && !labels) Modifier.weight(1f, fill = false) else Modifier)
                        .heightIn(min = dimens.minTouch).clip(CircleShape)
                        .then(item.onClick?.let { Modifier.clickable(role = Role.Button, onClick = it) } ?: Modifier),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(icon)) {
                            Icon(painterResource(item.icon), if (showValue) null else "${item.label}: ${item.value}",
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
 * Shares [available] px between cells that each want [natural] px: cells that fit their fair
 * share get what they need, the rest split what is left; leftover space is spread evenly.
 */
internal fun chipWidths(natural: List<Int>, available: Int): List<Int> {
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
    if (remaining > 0) {
        val extra = remaining / natural.size
        natural.indices.forEach { widths[it] += extra }
        widths[natural.lastIndex] += remaining - extra * natural.size
    }
    return widths.toList()
}
