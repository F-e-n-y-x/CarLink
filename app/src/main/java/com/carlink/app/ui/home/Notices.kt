package com.carlink.app.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkNoticeKind
import com.carlink.app.core.LinkProblem
import com.carlink.app.core.LinkRecovery
import com.carlink.app.core.LinkState
import com.carlink.app.core.Transport
import com.carlink.app.settings.WirelessPath
import com.carlink.app.ui.premium.IconTile
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.UiProfile
import java.util.Locale

/**
 * Why an attempt is waiting, in the driver's words: calm, specific and saying what happens next
 * ("Taking longer than usual: reconnecting Wi-Fi…", "Trying the car hotspot instead"). Null
 * while the attempt is on time. Never shows the session layer's own (log) wording.
 */
@Composable
internal fun noticeText(link: LinkState): String? {
    val notice = link.notice ?: return null
    val phone = stringResource(if (link.engine == Engine.CARPLAY) R.string.carlink_notice_phone_iphone
        else R.string.carlink_notice_phone_other)
    return when (notice.kind) {
        LinkNoticeKind.NOT_ANSWERING -> stringResource(R.string.carlink_notice_not_answering, phone)
        LinkNoticeKind.SLOW -> when {
            link.step <= 1 -> stringResource(R.string.carlink_notice_slow_reaching, phone)
            link.step == 2 -> stringResource(R.string.carlink_notice_slow_handshake, phone)
            link.step == 3 && link.transport != Transport.USB -> stringResource(R.string.carlink_notice_slow_linking)
            else -> stringResource(R.string.carlink_notice_slow_picture)
        }
        LinkNoticeKind.RECOVERING -> when (notice.recovery) {
            LinkRecovery.WAKE_PHONE -> stringResource(R.string.carlink_notice_wake, phone)
            LinkRecovery.RESTART_USB -> stringResource(R.string.carlink_notice_restart_usb)
            LinkRecovery.RETRY, null -> stringResource(R.string.carlink_notice_retry)
            LinkRecovery.FRESH_WIFI_DIRECT -> stringResource(R.string.carlink_notice_fresh_wifi)
            LinkRecovery.TRY_WIFI_DIRECT -> stringResource(R.string.carlink_notice_try_direct)
            LinkRecovery.TRY_CAR_HOTSPOT -> stringResource(R.string.carlink_notice_try_hotspot)
            LinkRecovery.TRY_SAME_WIFI -> stringResource(R.string.carlink_notice_try_same_wifi)
            LinkRecovery.TRY_OTHER_PATH -> stringResource(R.string.carlink_notice_try_other)
            LinkRecovery.REPLUG -> stringResource(R.string.carlink_notice_replug, phone)
        }
        LinkNoticeKind.RETRYING -> when (notice.reason) {
            LinkProblem.BLUETOOTH, LinkProblem.PHONE_DID_NOT_ANSWER -> stringResource(R.string.carlink_notice_retry_bluetooth, phone)
            LinkProblem.PHONE_REFUSED_WIFI -> stringResource(R.string.carlink_notice_retry_refused, phone)
            LinkProblem.PHONE_NEVER_REACHED_CAR -> stringResource(R.string.carlink_notice_retry_unreached, phone)
            LinkProblem.LINK_TOO_WEAK -> stringResource(R.string.carlink_notice_retry_dropped)
            LinkProblem.USB -> stringResource(R.string.carlink_notice_retry_usb)
            else -> stringResource(R.string.carlink_notice_retry_plain)
        }
    }
}

/** The glyph that goes with [noticeText]: a clock while slow, the new path's glyph when switching. */
@DrawableRes
internal fun noticeIcon(link: LinkState): Int {
    val notice = link.notice ?: return R.drawable.carlink_ic_schedule
    return when (notice.recovery) {
        LinkRecovery.TRY_WIFI_DIRECT -> R.drawable.carlink_ic_p2p
        LinkRecovery.TRY_CAR_HOTSPOT -> R.drawable.carlink_ic_wifi_tethering
        LinkRecovery.TRY_SAME_WIFI -> R.drawable.carlink_ic_wifi
        LinkRecovery.FRESH_WIFI_DIRECT -> R.drawable.carlink_ic_wifi_find
        LinkRecovery.RESTART_USB, LinkRecovery.REPLUG -> R.drawable.carlink_ic_usb
        LinkRecovery.WAKE_PHONE -> R.drawable.carlink_ic_bluetooth_searching
        LinkRecovery.RETRY, LinkRecovery.TRY_OTHER_PATH -> R.drawable.carlink_ic_refresh
        null -> if (notice.kind == LinkNoticeKind.RETRYING) R.drawable.carlink_ic_refresh else R.drawable.carlink_ic_schedule
    }
}

/**
 * The notice as one quiet line on Home's stage: a Caution glyph tile and the sentence on a faint
 * tinted capsule. It announces itself to TalkBack when it changes.
 */
@Composable
internal fun NoticeLine(text: String, @DrawableRes icon: Int, modifier: Modifier = Modifier) {
    val car = CarLinkTokens.profile == UiProfile.CAR
    val warning = CarLinkTokens.colors.warning
    val shape = RoundedCornerShape(if (car) 26.dp else 20.dp)
    Row(
        modifier.widthIn(max = if (car) 760.dp else 520.dp).clip(shape).background(warning.copy(alpha = 0.08f))
            .border(1.dp, warning.copy(alpha = 0.26f), shape)
            .padding(start = if (car) 12.dp else 8.dp, end = if (car) 22.dp else 14.dp, top = 6.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint = warning, size = if (car) 44.dp else 32.dp)
        Spacer(Modifier.width(if (car) 14.dp else 10.dp))
        Text(text, style = MaterialTheme.typography.titleSmall.copy(lineBreak = LineBreak.Paragraph),
            color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A known phone's usual connection: its median time to picture on its best path
 * ([com.carlink.app.connection.PhoneHealth]); [path] is null for a cable.
 */
data class PhoneUsual(val millis: Long, val path: WirelessPath?)

/** "5 s", "4.2 s" (one decimal under 10 s). */
@Composable
internal fun secondsText(millis: Long): String = stringResource(R.string.carlink_seconds_short,
    if (millis < 10_000) String.format(Locale.ROOT, "%.1f", millis / 1000.0).removeSuffix(".0") else (millis / 1000).toString())

/** "Usually connects in 5 s by Wi-Fi Direct". */
@Composable
internal fun usualText(usual: PhoneUsual): String = stringResource(R.string.carlink_home_usual, secondsText(usual.millis),
    usual.path?.let { path ->
        stringResource(R.string.carlink_home_usual_by_path, stringResource(when (path) {
            WirelessPath.WIFI_DIRECT -> R.string.carlink_home_path_direct
            WirelessPath.CAR_HOTSPOT -> R.string.carlink_home_path_hotspot
            WirelessPath.SAME_WIFI -> R.string.carlink_home_path_same_wifi
        }))
    }
        ?: stringResource(R.string.carlink_home_usual_by_cable))

/**
 * [phone]'s usual connection from its record, or null until its best path has connected at least
 * [MIN_USUAL_SUCCESSES] times (one fast or slow start says little).
 */
internal fun usualFor(phone: com.carlink.app.detect.PairedPhone?, health: List<com.carlink.app.connection.PhoneHealth>): PhoneUsual? {
    val engine = phone?.kind?.engine ?: return null
    val key = com.carlink.app.connection.ConnectionProfiles.keyOf(
        com.carlink.app.connection.SessionRequest(engine, Transport.WIRELESS, phone.address, phone.name))
    val record = health.firstOrNull { it.key == key } ?: return null
    val best = record.paths.firstOrNull { it.path == record.bestPath } ?: return null
    val millis = best.medianTimeToPictureMillis ?: return null
    if (best.successes < MIN_USUAL_SUCCESSES) return null
    val path = if (best.path == com.carlink.app.connection.ConnectionProfiles.USB) null
        else runCatching { WirelessPath.valueOf(best.path) }.getOrNull() ?: return null
    return PhoneUsual(millis, path)
}

internal const val MIN_USUAL_SUCCESSES = 2
