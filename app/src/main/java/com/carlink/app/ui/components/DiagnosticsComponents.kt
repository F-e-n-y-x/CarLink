package com.carlink.app.ui.components

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.carlink.app.R
import com.carlink.app.diagnostics.CheckStatus
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.CarMotion
import com.carlink.app.ui.theme.LocalReduceMotion
import com.carlink.app.ui.theme.UiProfile
import com.carlink.app.ui.theme.WidthClass
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Components for Diagnostics and About (DESIGN_SPEC §6.5, §6.6, §6.7: StatTile, CheckRow,
 * QrPanel) plus a side sheet for parked reading. Sizes come from CarLinkTokens.dimens.
 */

/** Typography for timers and stat values: tabular figures, so digits don't jump as they change. */
@Composable
fun TextStyle.tabularLtr(): TextStyle = merge(TextStyle(fontFeatureSettings = "tnum", textDirection = TextDirection.Ltr))

@DrawableRes
fun statusIcon(status: CheckStatus): Int = when (status) {
    CheckStatus.PASS -> R.drawable.carlink_ic_check_circle_filled
    CheckStatus.WARN -> R.drawable.carlink_ic_warning_filled
    CheckStatus.FAIL -> R.drawable.carlink_ic_error_filled
    CheckStatus.INFO -> R.drawable.carlink_ic_info
}

@Composable
fun statusTint(status: CheckStatus): Color = when (status) {
    CheckStatus.PASS -> CarLinkTokens.colors.success
    CheckStatus.WARN -> CarLinkTokens.colors.warning
    CheckStatus.FAIL -> MaterialTheme.colorScheme.error
    CheckStatus.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun statusLabel(status: CheckStatus): String = stringResource(when (status) {
    CheckStatus.PASS -> R.string.carlink_diag_status_pass
    CheckStatus.WARN -> R.string.carlink_diag_status_warn
    CheckStatus.FAIL -> R.string.carlink_diag_status_fail
    CheckStatus.INFO -> R.string.carlink_diag_status_info
})

/**
 * One readiness check: status icon (never color alone), title, supporting text, an optional
 * note, and at most one Fix button. The text merges into one screen-reader node ("Problem,
 * Bluetooth is off, …"); the Fix button stays a separate, focusable target. On narrow widths
 * the button moves below the text so neither is squeezed.
 */
@Composable
fun CheckRow(
    status: CheckStatus,
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    note: String? = null,
    fixLabel: String? = null,
    onFix: (() -> Unit)? = null,
) {
    val dimens = CarLinkTokens.dimens
    val fixDescription = stringResource(R.string.carlink_diag_fix_a11y, title)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stackFix = maxWidth < if (CarLinkTokens.profile == UiProfile.CAR) 640.dp else 420.dp
        val fix: (@Composable (Modifier) -> Unit)? = if (fixLabel != null && onFix != null) {
            { m -> SecondaryButton(fixLabel, onFix, m.semantics { contentDescription = fixDescription }) }
        } else null
        Column(
            Modifier.fillMaxWidth()
                .heightIn(min = if (supporting == null && note == null) dimens.rowOneLine else dimens.rowTwoLine)
                .padding(horizontal = dimens.gutter, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                    CarIcon(statusIcon(status), statusLabel(status), Modifier.padding(top = 2.dp), tint = statusTint(status))
                    Spacer(Modifier.width(dimens.gutter))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        if (supporting != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(supporting, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (note != null) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.Top) {
                                CarIcon(R.drawable.carlink_ic_info, null, Modifier.padding(top = 4.dp),
                                    size = dimens.iconTertiary, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text(note, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (fix != null && !stackFix) {
                    Spacer(Modifier.width(dimens.targetGap))
                    fix(Modifier)
                }
            }
            if (fix != null && stackFix) {
                Spacer(Modifier.height(12.dp))
                fix(Modifier.padding(start = dimens.iconSecondary + dimens.gutter))
            }
        }
    }
}

/** How a stat tile's value reads; WARN and ERROR add an icon as well as the tint. */
enum class StatTone { NORMAL, WARN, ERROR }

/**
 * A measured or known value: label (bodyMedium) above value (headlineSmall, tabular figures).
 * Not interactive; merged for screen readers as "label, value".
 */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, tone: StatTone = StatTone.NORMAL,
             technical: Boolean = false, maxValueLines: Int = 2) {
    val dimens = CarLinkTokens.dimens
    val tint = when (tone) {
        StatTone.NORMAL -> MaterialTheme.colorScheme.onSurface
        StatTone.WARN -> CarLinkTokens.colors.warning
        StatTone.ERROR -> MaterialTheme.colorScheme.error
    }
    Column(
        modifier.clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = dimens.gutter, vertical = 16.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Top) {
            if (tone != StatTone.NORMAL) {
                CarIcon(if (tone == StatTone.WARN) R.drawable.carlink_ic_warning_filled else R.drawable.carlink_ic_error_filled,
                    null, Modifier.padding(top = 4.dp), size = dimens.iconTertiary + 4.dp, tint = tint)
                Spacer(Modifier.width(8.dp))
            }
            val style = MaterialTheme.typography.headlineSmall
            Text(value, style = if (technical) style.tabularLtr() else style.merge(TextStyle(fontFeatureSettings = "tnum")),
                color = tint, maxLines = maxValueLines)
        }
    }
}

/**
 * Lays tiles out in rows of equal width, as many per row as fit at [minTileWidth] (at most
 * [maxColumns]). A short last row keeps the same tile width.
 */
@Composable
fun StatGrid(count: Int, modifier: Modifier = Modifier, minTileWidth: Dp = if (CarLinkTokens.profile == UiProfile.CAR) 240.dp else 150.dp,
             maxColumns: Int = 4, tile: @Composable (index: Int, modifier: Modifier) -> Unit) {
    val gap = CarLinkTokens.dimens.targetGap
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = ((maxWidth + gap) / (minTileWidth + gap)).toInt().coerceIn(1, maxColumns)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (start in 0 until count step columns) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (i in start until start + columns) {
                        if (i < count) tile(i, Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Encodes [content] as a QR code, one pixel per module (scaled up without filtering when
 * drawn). Error correction M; the quiet zone is drawn by [QrCode]'s padding.
 */
fun qrBitmap(content: String, dark: Int, light: Int): ImageBitmap? = runCatching {
    val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix[i % matrix.width, i / matrix.width]) dark else light
    }
    Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

/**
 * A scannable QR code. Scanners expect dark modules on a light field, so the code is always
 * drawn that way: on the light theme's lowest surface, or on the dark theme's text color
 * (#E8EDF3, not pure white) at night. Never mirrored in RTL.
 */
@Composable
fun QrCode(content: String, size: Dp, contentDescription: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val darkTheme = scheme.background.luminance() < 0.5f
    val field = if (darkTheme) scheme.onSurface else scheme.surfaceContainerLowest
    val modules = if (darkTheme) scheme.background else scheme.onSurface
    val bitmap = remember(content, field, modules) { qrBitmap(content, modules.toArgb(), field.toArgb()) }
    Box(
        modifier.size(size).clip(MaterialTheme.shapes.small).background(field)
            .padding(size / 10)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
            }
        }
    }
}

/** A QR code with a caption and the address in small left-to-right text below it. */
@Composable
fun QrPanel(title: String, url: String, size: Dp, modifier: Modifier = Modifier) {
    val display = url.removePrefix("https://")
    Column(modifier.width(size), horizontalAlignment = Alignment.CenterHorizontally) {
        QrCode(url, size, stringResource(R.string.carlink_about_qr_a11y, display))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(ltr(display), style = MaterialTheme.typography.bodySmall, color = CarLinkTokens.colors.onSurfaceTertiary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/**
 * Parked reading in a sheet: from the end edge at [com.carlink.app.ui.theme.CarLinkDimens.sideSheetWidth]
 * on wide windows (DESIGN_SPEC §5.5 level 3 + scrim), full screen on compact ones. [onBack]
 * handles Back (e.g. leave a sub-page); tapping the scrim or Close dismisses.
 */
@Composable
fun CarSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = onDismiss,
    showBackArrow: Boolean = false,
    /** False when [content] scrolls itself (e.g. a LazyColumn with weight(1f)). */
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val layout = CarLinkTokens.layout
    val dimens = CarLinkTokens.dimens
    val reduceMotion = LocalReduceMotion.current
    val fullScreen = layout.width == WidthClass.COMPACT
    Dialog(onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The dialog window has its own Compose root, which would reset CarLink's canvas density.
        CompositionLocalProvider(LocalDensity provides density) {
            (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
            val visible = remember { MutableTransitionState(false).apply { targetState = true } }
            val duration = if (reduceMotion) 0 else CarMotion.MEDIUM
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = onDismiss),
                contentAlignment = Alignment.CenterEnd,
            ) {
                AnimatedVisibility(
                    visibleState = visible,
                    enter = if (fullScreen) {
                        slideInVertically(tween(duration, easing = CarMotion.EmphasizedDecelerate)) { it / 8 } + fadeIn(tween(duration))
                    } else {
                        slideInHorizontally(tween(duration, easing = CarMotion.EmphasizedDecelerate)) { it / 4 } + fadeIn(tween(duration))
                    },
                ) {
                    Surface(
                        modifier = modifier
                            .then(if (fullScreen) Modifier.fillMaxSize() else Modifier.fillMaxHeight().width(
                                minOf(dimens.sideSheetWidth + 160.dp, layout.widthDp * 0.9f)))
                            // Swallow taps so they don't reach the scrim.
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                        shape = if (fullScreen) RoundedCornerShape(0.dp)
                        else RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        // Named explicitly: in the dark scheme surfaceContainerHigh equals surfaceVariant,
                        // so contentColorFor() would pick the dimmer onSurfaceVariant.
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = dimens.topBar).padding(horizontal = dimens.gutter - 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (showBackArrow) {
                                    IconAction(R.drawable.carlink_ic_arrow_back, stringResource(R.string.carlink_back), onBack)
                                    Spacer(Modifier.width(8.dp))
                                } else {
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2,
                                    modifier = Modifier.weight(1f).semantics {
                                        heading()
                                        liveRegion = LiveRegionMode.Polite
                                    })
                                IconAction(R.drawable.carlink_ic_close, stringResource(R.string.carlink_about_close), onDismiss)
                            }
                            Column(
                                Modifier.fillMaxWidth().weight(1f)
                                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                                    .padding(horizontal = dimens.gutter).padding(bottom = if (scrollable) dimens.gutter else 0.dp),
                                content = content,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A heading inside a sheet or card (titleMedium in onSurfaceVariant; no ALL CAPS, no eyebrows). */
@Composable
fun SubHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = CarLinkTokens.dimens.gutter, bottom = 8.dp).semantics { heading() })
}

/** Name and description of a component or credit, merged for screen readers. */
@Composable
fun CreditItem(name: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 10.dp).semantics(mergeDescendants = true) {}) {
        Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
