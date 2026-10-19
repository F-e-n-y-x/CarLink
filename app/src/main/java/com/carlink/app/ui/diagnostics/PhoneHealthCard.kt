package com.carlink.app.ui.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.ui.components.StatGrid
import com.carlink.app.ui.components.engineName
import com.carlink.app.ui.components.isolate
import com.carlink.app.ui.home.secondsText
import com.carlink.app.ui.premium.Eyebrow
import com.carlink.app.ui.premium.wirelessPathName
import com.carlink.app.ui.settings.GlassGroup
import com.carlink.app.ui.settings.GroupDivider
import com.carlink.app.ui.settings.GroupHeader
import com.carlink.app.ui.settings.RowGlyph
import com.carlink.app.ui.settings.isCar
import com.carlink.app.ui.settings.rowPadding
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.core.connection.AttemptOutcome
import com.carlink.core.connection.ConnectionProfiles
import com.carlink.core.connection.FailureReason
import com.carlink.core.connection.PhoneHealth
import com.carlink.core.connection.ProblemRecord
import com.carlink.core.connection.ProgressStage
import com.carlink.core.core.Engine
import com.carlink.core.settings.WirelessPath
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** At most this many phones are listed (most recently used first). */
private const val MAX_PHONES = 4

/** At most this many of a phone's last problems are listed. */
private const val MAX_PROBLEMS = 3

/**
 * Diagnostics › Phones: each known phone's record from the session layer
 * ([com.carlink.core.connection.SessionManager.health]) as an instrument reading: a success gauge,
 * the usual time to picture, the path that works best and the last few problems with their time.
 */
@Composable
internal fun PhonesHealthCard(health: List<PhoneHealth>, now: Long = System.currentTimeMillis()) {
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_diag_phones), icon = R.drawable.carlink_ic_smartphone) }) {
        if (health.isEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = rowPadding, end = rowPadding, top = 12.dp, bottom = rowPadding),
                verticalAlignment = Alignment.CenterVertically) {
                RowGlyph(R.drawable.carlink_ic_schedule, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(if (isCar) 24.dp else 16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.carlink_diag_phones_empty_title), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.carlink_diag_phones_empty_body), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@GlassGroup
        }
        health.take(MAX_PHONES).forEachIndexed { index, phone ->
            if (index > 0) GroupDivider()
            PhoneHealthBlock(phone, now)
        }
    }
}

@Composable
private fun PhoneHealthBlock(phone: PhoneHealth, now: Long) {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    val gap = CarLinkTokens.dimens.targetGap
    Column(Modifier.fillMaxWidth().padding(start = rowPadding, end = rowPadding, top = if (isCar) 20.dp else 14.dp,
        bottom = if (isCar) 28.dp else 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RateGauge(phone.successRate, if (isCar) 104.dp else 68.dp)
            Spacer(Modifier.width(if (isCar) 24.dp else 16.dp))
            Column(Modifier.weight(1f)) {
                Text(isolate(phone.phoneName ?: stringResource(R.string.carlink_diag_phone_unnamed)),
                    style = MaterialTheme.typography.titleLarge, color = scheme.onSurface, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.carlink_diag_phone_attempts, engineName(phone.engine), phone.successes, phone.attempts),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(gap * 1.5f))
        val tiles = listOf(
            stringResource(R.string.carlink_diag_phone_typical) to
                (phone.medianTimeToPictureMillis?.let { secondsText(it) } ?: stringResource(R.string.carlink_diag_phone_not_yet)),
            stringResource(R.string.carlink_diag_phone_best) to
                (phone.bestPath?.let { pathLabel(it) } ?: stringResource(R.string.carlink_diag_phone_not_yet)),
        )
        StatGrid(tiles.size, minTileWidth = if (isCar) 220.dp else 140.dp) { i, m -> StatTile(tiles[i].first, tiles[i].second, m, labelLines = 2) }
        Spacer(Modifier.height(gap * 1.5f))
        Eyebrow(stringResource(R.string.carlink_diag_phone_problems))
        Spacer(Modifier.height(if (isCar) 12.dp else 8.dp))
        val problems = phone.lastProblems.take(MAX_PROBLEMS)
        if (problems.isEmpty()) {
            ProblemLine(null, stringResource(R.string.carlink_diag_phone_no_problems), colors.success)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(if (isCar) 10.dp else 6.dp)) {
                problems.forEach { record ->
                    ProblemLine(problemTime(record.atMillis, now),
                        stringResource(R.string.carlink_diag_phone_problem_on, problemName(record), pathLabel(record.path)),
                        if (record.kind.startsWith("STALL_")) colors.warning else scheme.error)
                }
            }
        }
    }
}

/** A time (tabular, fixed column) and what happened, behind a dot in the problem's colour. */
@Composable
private fun ProblemLine(time: String?, text: String, color: Color) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = if (isCar) 12.dp else 7.dp).size(if (isCar) 10.dp else 7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(if (isCar) 16.dp else 12.dp))
        if (time != null) {
            Text(time, Modifier.width(if (isCar) 200.dp else 128.dp),
                style = MaterialTheme.typography.bodyMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
                color = CarLinkTokens.colors.onSurfaceTertiary, maxLines = 1)
        }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
    }
}

/**
 * The success rate as a small cluster gauge: a 270° track open at the bottom, lit up to the rate
 * in Live, Caution or Fault, with the percentage in its core. Strokes only (head-unit GPUs).
 */
@Composable
private fun RateGauge(rate: Float?, size: Dp) {
    val colors = CarLinkTokens.colors
    val scheme = MaterialTheme.colorScheme
    val color = when {
        rate == null -> scheme.onSurfaceVariant
        rate >= 0.8f -> colors.success
        rate >= 0.5f -> colors.warning
        else -> scheme.error
    }
    val percent = rate?.let { Math.round(it * 100) }
    val description = percent?.let { stringResource(R.string.carlink_diag_phone_rate_description, it) }
    val track = scheme.onSurface.copy(alpha = 0.10f)
    Box(Modifier.size(size).semantics { description?.let { contentDescription = it } }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.085f
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            if (rate != null && rate > 0f) {
                drawArc(color, 135f, 270f * rate.coerceIn(0f, 1f), false, Offset(inset, inset), arc,
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Text(percent?.let { stringResource(R.string.carlink_diag_phone_rate, it) } ?: stringResource(R.string.carlink_diag_phone_rate_none),
            // Sized from the gauge, so "100%" always sits inside the ring at any font scale.
            style = MaterialTheme.typography.titleSmall.merge(TextStyle(fontFeatureSettings = "tnum",
                fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { (size * 0.22f).toSp() })),
            color = scheme.onSurface, maxLines = 1)
    }
}

/** "Cable", "Wi-Fi Direct", "Car hotspot" or "Same Wi-Fi" for a record's path. */
@Composable
private fun pathLabel(path: String): String =
    if (path == ConnectionProfiles.USB) stringResource(R.string.carlink_diag_phone_cable)
    else runCatching { WirelessPath.valueOf(path) }.getOrNull()?.let { wirelessPathName(it) } ?: path

/** What went wrong, from the record's kind (a [FailureReason] or a stalled phase), never its log text. */
@Composable
private fun problemName(record: ProblemRecord): String = stringResource(when (record.kind) {
    FailureReason.PHONE_DID_NOT_ANSWER.name -> R.string.carlink_diag_problem_no_answer
    FailureReason.BLUETOOTH.name -> R.string.carlink_diag_problem_bluetooth
    FailureReason.EXTERNAL_BLUETOOTH.name -> R.string.carlink_diag_problem_external_bt
    FailureReason.EXTERNAL_MODULE.name -> R.string.carlink_diag_problem_external_module
    FailureReason.PHONE_REFUSED_WIFI.name -> R.string.carlink_diag_problem_refused_wifi
    FailureReason.PHONE_NEVER_REACHED_CAR.name -> R.string.carlink_diag_problem_unreached
    FailureReason.PHONE_WIFI_OFF.name -> R.string.carlink_diag_problem_wifi_off
    FailureReason.LINK_TOO_WEAK.name -> R.string.carlink_diag_problem_dropped
    FailureReason.WIFI_SETUP_FAILED.name -> R.string.carlink_diag_problem_wifi_setup
    FailureReason.USB.name -> R.string.carlink_diag_problem_usb
    FailureReason.BLOCKED.name -> R.string.carlink_diag_problem_blocked
    FailureReason.AUTHENTICATION.name -> R.string.carlink_diag_problem_auth
    FailureReason.ENGINE.name -> R.string.carlink_diag_problem_engine
    AttemptOutcome.stallKind(ProgressStage.WAITING_FOR_PHONE), AttemptOutcome.stallKind(ProgressStage.PHONE_NOT_ANSWERING) ->
        R.string.carlink_diag_problem_slow_reaching
    AttemptOutcome.stallKind(ProgressStage.HANDSHAKE) -> R.string.carlink_diag_problem_slow_handshake
    AttemptOutcome.stallKind(ProgressStage.JOINING_WIFI) -> R.string.carlink_diag_problem_slow_linking
    AttemptOutcome.stallKind(ProgressStage.STARTING_PROJECTION) -> R.string.carlink_diag_problem_slow_picture
    AttemptOutcome.stallKind(null) -> R.string.carlink_diag_problem_slow_total
    else -> R.string.carlink_diag_problem_other
})

/** "14:02" today, "Yesterday 09:15", else "12 Oct 14:02" (24-hour, as everywhere in the car). */
@Composable
private fun problemTime(at: Long, now: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = now }
    val then = Calendar.getInstance().apply { timeInMillis = at }
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(day, then)) return clock
    day.add(Calendar.DAY_OF_YEAR, -1)
    if (sameDay(day, then)) return stringResource(R.string.carlink_diag_time_yesterday, clock)
    return SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(at))
}

/** Two phones' records for previews and the screenshot gallery. */
internal fun previewHealth(now: Long = System.currentTimeMillis()): List<PhoneHealth> = listOf(
    PhoneHealth(
        key = "CARPLAY:AA:BB:CC:00:00:01", engine = Engine.CARPLAY, phoneName = "Sara’s iPhone",
        attempts = 13, successes = 12, successRate = 12 / 13f, medianTimeToPictureMillis = 17_400,
        bestPath = WirelessPath.WIFI_DIRECT.name,
        paths = listOf(com.carlink.core.connection.PathHealth(WirelessPath.WIFI_DIRECT.name, 11, 11, 17_400, true),
            com.carlink.core.connection.PathHealth(WirelessPath.CAR_HOTSPOT.name, 2, 1, 21_000, false)),
        lastProblems = listOf(
            ProblemRecord(now - 25 * 60_000, WirelessPath.WIFI_DIRECT.name, ProgressStage.JOINING_WIFI,
                AttemptOutcome.stallKind(ProgressStage.JOINING_WIFI), null),
            ProblemRecord(now - 26 * 3_600_000L, WirelessPath.CAR_HOTSPOT.name, ProgressStage.JOINING_WIFI,
                FailureReason.PHONE_NEVER_REACHED_CAR.name, null),
            ProblemRecord(now - 4 * 86_400_000L, WirelessPath.WIFI_DIRECT.name, ProgressStage.WAITING_FOR_PHONE,
                FailureReason.PHONE_DID_NOT_ANSWER.name, null),
        ),
    ),
    PhoneHealth(
        key = "ANDROID_AUTO:usb:pixel 8", engine = Engine.ANDROID_AUTO, phoneName = "Pixel 8",
        attempts = 6, successes = 6, successRate = 1f, medianTimeToPictureMillis = 3_800, bestPath = ConnectionProfiles.USB,
        paths = listOf(com.carlink.core.connection.PathHealth(ConnectionProfiles.USB, 6, 6, 3_800, true)),
        lastProblems = emptyList(),
    ),
)
