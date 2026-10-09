package com.carlink.app.ui.premium

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.UiProfile
import com.carlink.core.core.PhoneKind
import com.carlink.core.core.Transport
import com.carlink.core.detect.PhoneClassifier
import com.carlink.core.entry.UsbRouterActivity
import com.carlink.core.settings.WirelessPath

/*
 * Cable or wireless, said the same way everywhere (Home, the Phones sheet, the switcher): a
 * cable glyph with "Cable"/"USB", or the wireless path's glyph with "Wireless · <path>".
 */

/** The glyph of how a phone reaches the screen: a USB plug, or the wireless path's glyph. */
@DrawableRes
fun transportIcon(transport: Transport?, path: WirelessPath? = null): Int = when {
    transport == Transport.USB -> R.drawable.carlink_ic_usb
    path == WirelessPath.CAR_HOTSPOT -> R.drawable.carlink_ic_wifi_tethering
    path == WirelessPath.WIFI_DIRECT -> R.drawable.carlink_ic_p2p
    else -> R.drawable.carlink_ic_wifi
}

/** "Same Wi-Fi", "Car hotspot" or "Wi-Fi Direct". */
@Composable
fun wirelessPathName(path: WirelessPath): String = stringResource(when (path) {
    WirelessPath.SAME_WIFI -> R.string.carlink_settings_method_same_wifi
    WirelessPath.CAR_HOTSPOT -> R.string.carlink_settings_method_hotspot
    WirelessPath.WIFI_DIRECT -> R.string.carlink_settings_method_direct
})

/** "Cable", or "Wireless · Wi-Fi Direct" when the path is known. */
@Composable
fun transportLabel(transport: Transport, path: WirelessPath?): String = when {
    transport == Transport.USB -> stringResource(R.string.carlink_transport_cable)
    path != null -> stringResource(R.string.carlink_transport_with_path, stringResource(R.string.carlink_transport_wireless),
        wirelessPathName(path))
    else -> stringResource(R.string.carlink_transport_wireless)
}

/**
 * A transport badge: the glyph and the words on a faint glass capsule, e.g. a USB plug with
 * "Connected by cable". [tint] colours the glyph (Live when connected).
 */
@Composable
fun TransportBadge(@DrawableRes icon: Int, text: String, modifier: Modifier = Modifier, tint: Color? = null,
                   style: TextStyle = MaterialTheme.typography.titleMedium) {
    val car = CarLinkTokens.profile == UiProfile.CAR
    val colors = CarLinkTokens.colors
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.clip(PillShape).background(colors.glassFill).border(1.dp, colors.hairline, PillShape)
            .padding(start = if (car) 12.dp else 8.dp, end = if (car) 22.dp else 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint = tint ?: scheme.primary, size = if (car) 44.dp else 32.dp)
        Spacer(Modifier.width(if (car) 14.dp else 10.dp))
        Text(text, style = style, color = scheme.onSurface)
    }
}

/** A small inline glyph + text pair for list subtitles ("Plugged in (USB)", "Wireless"). */
@Composable
fun TransportInline(@DrawableRes icon: Int, text: String, color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(if (CarLinkTokens.profile == UiProfile.CAR) size * 1.4f else size), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 2)
    }
}

/** The kinds of phone plugged in by USB right now (an iPhone, an Android phone), for "Plugged in" labels. */
fun usbPhoneKinds(context: Context): Set<PhoneKind> = runCatching {
    context.getSystemService(UsbManager::class.java)?.deviceList?.values.orEmpty()
        .map { PhoneClassifier.classifyUsb(UsbRouterActivity.describe(it)) }
        .filter { it != PhoneKind.UNKNOWN }.toSet()
}.getOrDefault(emptySet())
