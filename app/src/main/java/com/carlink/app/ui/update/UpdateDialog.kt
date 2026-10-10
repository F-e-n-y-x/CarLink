package com.carlink.app.ui.update

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.ui.premium.Eyebrow
import com.carlink.app.ui.premium.GlassButton
import com.carlink.app.ui.premium.GlyphDisc
import com.carlink.app.ui.premium.IonButton
import com.carlink.app.ui.premium.glassBorder
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.UiProfile
import com.carlink.app.update.ReleaseNotes
import com.carlink.app.update.UpdateError
import com.carlink.app.update.UpdateState

/** What the update dialog's buttons do (UpdateController in the app, no-ops in the gallery). */
interface UpdateDialogActions {
    fun ignore() {}
    fun later() {}
    fun download() {}
    fun cancel() {}
    fun openSettings() {}
    fun dismiss() {}
}

/**
 * "Update available — CarLink X.Y.Z", then the download, the check and the install, in one
 * card over a dim scrim (like the switch prompt). It never opens during a projection session
 * (the caller gates it). Tapping the scrim does nothing; Back means "Later" or "Close".
 */
@Composable
fun UpdateDialog(state: UpdateState, installedVersion: String, actions: UpdateDialogActions, modifier: Modifier = Modifier) {
    val dimens = CarLinkTokens.dimens
    val scheme = MaterialTheme.colorScheme
    val car = CarLinkTokens.profile == UiProfile.CAR
    val shape = MaterialTheme.shapes.extraLarge
    BackHandler {
        when (state) {
            is UpdateState.Available -> actions.later()
            is UpdateState.Failed, is UpdateState.Installing -> actions.dismiss()
            is UpdateState.NeedsPermission -> actions.cancel()
            else -> Unit // Downloading/Verifying: Cancel is a button, not a stray Back.
        }
    }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures {} }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(dimens.gutter),
        contentAlignment = Alignment.Center,
    ) {
        val cardWidth = (if (car) 960.dp else 600.dp).coerceAtMost(maxWidth)
        val windowHeight = maxHeight
        Column(
            Modifier
                .width(cardWidth)
                .heightIn(max = maxHeight)
                .background(scheme.surfaceContainer, shape)
                .border(glassBorder(), shape)
                .verticalScroll(rememberScrollState())
                .padding(dimens.cardPadding),
        ) {
            val inner = cardWidth - dimens.cardPadding * 2
            when (state) {
                is UpdateState.Available -> Available(state, installedVersion, inner, windowHeight, actions)
                is UpdateState.Downloading -> Progress(
                    stringResource(R.string.carlink_update_downloading_title, state.release.version.toString()),
                    progressText(state.bytes, state.total),
                    stringResource(R.string.carlink_update_download_body),
                    fraction = state.total?.takeIf { it > 0 }?.let { (state.bytes.toFloat() / it).coerceIn(0f, 1f) },
                    icon = R.drawable.carlink_ic_download,
                ) { GlassButton(stringResource(R.string.carlink_update_cancel), actions::cancel) }
                is UpdateState.Verifying -> Progress(
                    stringResource(R.string.carlink_update_verifying_title), null,
                    stringResource(R.string.carlink_update_verifying_body), fraction = null,
                    icon = R.drawable.carlink_ic_lock,
                ) { GlassButton(stringResource(R.string.carlink_update_cancel), actions::cancel) }
                is UpdateState.Installing -> Progress(
                    stringResource(R.string.carlink_update_installing_title, state.release.version.toString()), null,
                    stringResource(R.string.carlink_update_installing_body), fraction = null,
                    icon = R.drawable.carlink_ic_system_update,
                ) { GlassButton(stringResource(R.string.carlink_update_close), actions::dismiss) }
                is UpdateState.NeedsPermission -> {
                    Header(R.drawable.carlink_ic_lock, scheme.primary, stringResource(R.string.carlink_update_permission_title),
                        stringResource(R.string.carlink_update_version, state.release.version.toString()), null)
                    Spacer(Modifier.height(dimens.gutter))
                    Body(stringResource(R.string.carlink_update_permission_body))
                    Buttons(inner, primary = stringResource(R.string.carlink_update_open_settings) to actions::openSettings,
                        secondary = listOf(stringResource(R.string.carlink_update_cancel) to actions::cancel))
                }
                is UpdateState.Failed -> {
                    Header(R.drawable.carlink_ic_error, scheme.error, stringResource(R.string.carlink_update_failed_title),
                        stringResource(R.string.carlink_update_version, state.release.version.toString()), null)
                    Spacer(Modifier.height(dimens.gutter))
                    Body(errorText(state.error, state.detail))
                    val close = stringResource(R.string.carlink_update_close) to actions::dismiss
                    if (state.error.canRetry) {
                        Buttons(inner, primary = stringResource(R.string.carlink_update_try_again) to actions::download,
                            secondary = listOf(close))
                    } else {
                        Buttons(inner, primary = null, secondary = listOf(close))
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun Available(state: UpdateState.Available, installedVersion: String, inner: Dp, windowHeight: Dp, actions: UpdateDialogActions) {
    val dimens = CarLinkTokens.dimens
    val scheme = MaterialTheme.colorScheme
    val car = CarLinkTokens.profile == UiProfile.CAR
    val release = state.release
    Header(R.drawable.carlink_ic_system_update, scheme.primary, stringResource(R.string.carlink_update_available_title),
        stringResource(R.string.carlink_update_version, release.version.toString()),
        stringResource(R.string.carlink_update_installed, installedVersion))
    Spacer(Modifier.height(dimens.gutter))
    Eyebrow(stringResource(R.string.carlink_update_whats_new))
    Spacer(Modifier.height(8.dp))
    val notes = remember(release.notes) { ReleaseNotes.plain(release.notes) }
    val notesShape = RoundedCornerShape(if (car) 24.dp else 16.dp)
    Box(
        Modifier.fillMaxWidth()
            // Short windows (a ⅓ split, a 600 px head unit) keep room for all three buttons.
            .heightIn(max = (if (car) 260.dp else 200.dp).coerceAtMost(windowHeight * 0.22f).coerceAtLeast(96.dp))
            .clip(notesShape)
            .background(CarLinkTokens.colors.glassFill)
            .border(1.dp, CarLinkTokens.colors.hairline, notesShape)
            .verticalScroll(rememberScrollState())
            .padding(if (car) 24.dp else 16.dp),
    ) {
        Text(notes.ifBlank { stringResource(R.string.carlink_update_no_notes) },
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
    }
    Buttons(inner,
        primary = stringResource(R.string.carlink_update_download) to actions::download,
        secondary = listOf(stringResource(R.string.carlink_update_later) to actions::later,
            stringResource(R.string.carlink_update_ignore) to actions::ignore))
}

/** Glyph, title, the version in Ion and an optional third line. */
@Composable
private fun Header(@DrawableRes icon: Int, tint: Color, title: String, version: String, detail: String?) {
    val scheme = MaterialTheme.colorScheme
    val car = CarLinkTokens.profile == UiProfile.CAR
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphDisc(icon, tint, if (car) 88.dp else 56.dp)
        Spacer(Modifier.width(if (car) 24.dp else 16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
            Spacer(Modifier.height(2.dp))
            Text(version, style = MaterialTheme.typography.titleMedium, color = scheme.primary)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Download, check or install under way: a progress line, what it means, and one way out. */
@Composable
private fun Progress(
    title: String, amount: String?, body: String, fraction: Float?, @DrawableRes icon: Int,
    button: @Composable () -> Unit,
) {
    val dimens = CarLinkTokens.dimens
    val scheme = MaterialTheme.colorScheme
    val car = CarLinkTokens.profile == UiProfile.CAR
    Header(icon, scheme.primary, title, amount ?: "", null)
    Spacer(Modifier.height(dimens.gutter))
    val barModifier = Modifier.fillMaxWidth().height(if (car) 10.dp else 6.dp).clip(RoundedCornerShape(50))
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = barModifier, color = scheme.primary,
            trackColor = scheme.onSurface.copy(alpha = 0.10f), strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
    } else {
        LinearProgressIndicator(modifier = barModifier, color = scheme.primary, trackColor = scheme.onSurface.copy(alpha = 0.10f),
            strokeCap = StrokeCap.Round, gapSize = 0.dp)
    }
    Spacer(Modifier.height(dimens.gutter))
    Body(body)
    Spacer(Modifier.height(dimens.gutter * 1.5f))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { button() }
}

/**
 * The actions: one row (secondary start, primary end) when the card is wide enough, otherwise
 * stacked full width with the primary first, so long labels never clip in a narrow car window.
 */
@Composable
private fun Buttons(inner: Dp, primary: Pair<String, () -> Unit>?, secondary: List<Pair<String, () -> Unit>>) {
    val dimens = CarLinkTokens.dimens
    val car = CarLinkTokens.profile == UiProfile.CAR
    Spacer(Modifier.height(dimens.gutter * 1.5f))
    val wide = inner >= if (car) 860.dp else 520.dp
    if (wide || (primary == null && secondary.size == 1)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (car) 16.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (primary == null) Spacer(Modifier.weight(1f))
            secondary.forEach { (label, onClick) -> GlassButton(label, onClick) }
            if (primary != null) Spacer(Modifier.weight(1f))
            primary?.let { (label, onClick) -> IonButton(label, onClick) }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (car) 16.dp else 12.dp)) {
            primary?.let { (label, onClick) -> IonButton(label, onClick, Modifier.fillMaxWidth()) }
            secondary.forEach { (label, onClick) -> GlassButton(label, onClick, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun progressText(bytes: Long, total: Long?): String {
    val context = LocalContext.current
    val done = Formatter.formatShortFileSize(context, bytes)
    return if (total != null && total > 0) {
        stringResource(R.string.carlink_update_progress, done, Formatter.formatShortFileSize(context, total))
    } else {
        stringResource(R.string.carlink_update_progress_unknown, done)
    }
}

@Composable
private fun errorText(error: UpdateError, detail: String?): String = when (error) {
    UpdateError.DOWNLOAD -> stringResource(R.string.carlink_update_error_download)
    UpdateError.CHECKSUM_MISSING -> stringResource(R.string.carlink_update_error_checksum_missing)
    UpdateError.CHECKSUM_MISMATCH -> stringResource(R.string.carlink_update_error_checksum)
    UpdateError.UNREADABLE -> stringResource(R.string.carlink_update_error_unreadable)
    UpdateError.WRONG_PACKAGE -> stringResource(R.string.carlink_update_error_package)
    UpdateError.NOT_NEWER -> stringResource(R.string.carlink_update_error_not_newer)
    UpdateError.WRONG_SIGNER -> stringResource(R.string.carlink_update_error_signer)
    UpdateError.INSTALL -> if (detail.isNullOrBlank()) stringResource(R.string.carlink_update_error_install)
        else stringResource(R.string.carlink_update_error_install_detail, detail)
    UpdateError.INSTALL_CANCELLED -> stringResource(R.string.carlink_update_error_install_cancelled)
}
