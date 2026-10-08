package com.carlink.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.core.PhoneKind
import com.carlink.app.core.Transport
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.UiProfile

/** Shared building blocks (DESIGN_SPEC §6.7). Every size comes from CarLinkTokens.dimens. */

/**
 * The CarLink brand mark, cropped tight to the glyph. Two tinted layers so it follows the active
 * day/night palette: the body in [MaterialTheme.colorScheme.onSurface], the node in the Ion accent.
 * The geometry comes from design/logo/tools/build_logo.py (swap the two drawables to change logo).
 */
@Composable
fun CarLinkMark(modifier: Modifier = Modifier) {
    Box(modifier) {
        Icon(painterResource(R.drawable.carlink_logo_mark_primary), null, Modifier.matchParentSize(),
            tint = MaterialTheme.colorScheme.onSurface)
        Icon(painterResource(R.drawable.carlink_logo_mark_accent), null, Modifier.matchParentSize(),
            tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun CarIcon(@DrawableRes icon: Int, contentDescription: String?, modifier: Modifier = Modifier,
            size: Dp = CarLinkTokens.dimens.iconSecondary, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Icon(painterResource(icon), contentDescription, modifier.size(size), tint = tint)
}

/** The screen's one primary action (96dp car / 56dp handheld). Labels wrap, never ellipsize. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                  @DrawableRes icon: Int? = null, enabled: Boolean = true) {
    val dimens = CarLinkTokens.dimens
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = dimens.primaryButton)
            .widthIn(min = if (CarLinkTokens.profile == UiProfile.CAR) 320.dp else 200.dp),
        shape = RoundedCornerShape(dimens.buttonRadius),
        contentPadding = ButtonDefaults.ContentPadding.let { androidx.compose.foundation.layout.PaddingValues(horizontal = dimens.gutter, vertical = 8.dp) },
    ) {
        if (icon != null) {
            CarIcon(icon, null, size = dimens.iconSecondary, tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 2)
    }
}

/** Secondary, tonal (76dp car / 48dp handheld). */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                    @DrawableRes icon: Int? = null, enabled: Boolean = true) {
    val dimens = CarLinkTokens.dimens
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = dimens.secondaryButton),
        shape = RoundedCornerShape(dimens.buttonRadius),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = dimens.gutter, vertical = 8.dp),
    ) {
        if (icon != null) {
            CarIcon(icon, null, size = dimens.iconTertiary + 4.dp, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 2)
    }
}

/** Low-emphasis action (text button), still at the minimum touch size. */
@Composable
fun TertiaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    val dimens = CarLinkTokens.dimens
    TextButton(onClick = onClick, modifier = modifier.defaultMinSize(minHeight = dimens.minTouch),
        shape = RoundedCornerShape(dimens.buttonRadius)) {
        if (icon != null) {
            CarIcon(icon, null, size = dimens.iconTertiary + 4.dp, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 2)
    }
}

/** Icon-only action with a visible label for screen readers. */
@Composable
fun IconAction(@DrawableRes icon: Int, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = CarLinkTokens.dimens
    IconButton(onClick = onClick, modifier = modifier.size(dimens.minTouch)) {
        CarIcon(icon, contentDescription, size = dimens.iconSecondary)
    }
}

/** Top bar: brand or title, non-interactive status chips, trailing actions (DESIGN_SPEC §6.1.1). */
@Composable
fun CarTopBar(
    title: String,
    modifier: Modifier = Modifier,
    showBrand: Boolean = false,
    onBack: (() -> Unit)? = null,
    chips: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val dimens = CarLinkTokens.dimens
    Row(
        modifier.fillMaxWidth().height(dimens.topBar).padding(horizontal = dimens.screenMargin - 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconAction(R.drawable.carlink_ic_arrow_back, stringResource(R.string.carlink_back), onBack)
        if (showBrand) {
            Spacer(Modifier.width(8.dp))
            CarLinkMark(Modifier.size(dimens.iconSecondary))
        }
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() })
        Spacer(Modifier.width(dimens.gutter))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            chips()
        }
        actions()
    }
}

/** Non-interactive status (no outline, no chevron, so it never looks tappable). */
@Composable
fun StatusChip(text: String, @DrawableRes icon: Int, modifier: Modifier = Modifier,
               tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val dimens = CarLinkTokens.dimens
    Row(
        modifier.height(dimens.statusChip).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CarIcon(icon, null, size = dimens.iconTertiary, tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@DrawableRes
fun phoneIcon(kind: PhoneKind): Int = when (kind) {
    PhoneKind.IPHONE -> R.drawable.carlink_ic_phone_iphone
    PhoneKind.ANDROID -> R.drawable.carlink_ic_phone_android
    PhoneKind.UNKNOWN -> R.drawable.carlink_ic_smartphone
}

@DrawableRes
fun transportIcon(transport: Transport): Int = when (transport) {
    Transport.USB -> R.drawable.carlink_ic_usb
    Transport.WIRELESS -> R.drawable.carlink_ic_wifi
}

@Composable
fun engineName(engine: Engine): String = stringResource(
    if (engine == Engine.CARPLAY) R.string.carlink_engine_carplay else R.string.carlink_engine_android_auto)

@Composable
fun transportName(transport: Transport): String = stringResource(
    if (transport == Transport.USB) R.string.carlink_transport_usb else R.string.carlink_transport_wireless)

/** Phone glyph in a circle (76dp hero / 56dp rows on car). */
@Composable
fun PhoneAvatar(kind: PhoneKind, size: Dp, modifier: Modifier = Modifier, active: Boolean = false) {
    Box(
        modifier.size(size).clip(CircleShape)
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        CarIcon(phoneIcon(kind), null, size = size * 0.5f,
            tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Engine identity by word plus a neutral glyph in the engine tint (no Apple/Google marks,
 * DESIGN_SPEC §5.8). The tint is used nowhere else.
 */
@Composable
fun EngineBadge(engine: Engine, modifier: Modifier = Modifier) {
    val colors = CarLinkTokens.colors
    StatusChip(engineName(engine), R.drawable.carlink_ic_directions_car_filled, modifier,
        tint = if (engine == Engine.CARPLAY) colors.engineCarPlay else colors.engineAndroidAuto)
}

/** Grouped rows on a tonal card (no border in dark, 1dp outlineVariant in light). */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (MaterialTheme.colorScheme.background.luminanceIsLight()) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else null,
    ) { Column { content() } }
}

@Composable
fun RowDivider() {
    HorizontalDivider(Modifier.padding(horizontal = CarLinkTokens.dimens.gutter), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, engine: Engine? = null) {
    val colors = CarLinkTokens.colors
    Column(modifier.fillMaxWidth().padding(top = CarLinkTokens.dimens.gutter, bottom = 12.dp).semantics(mergeDescendants = true) { heading() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (engine != null) {
                Box(Modifier.size(16.dp).clip(CircleShape)
                    .background(if (engine == Engine.CARPLAY) colors.engineCarPlay else colors.engineAndroidAuto))
                Spacer(Modifier.width(12.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A setting row (DESIGN_SPEC §6.3.2): title, optional supporting text on line 2, optional
 * trailing content; the whole row is the target.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    role: Role? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val dimens = CarLinkTokens.dimens
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = if (supporting == null) dimens.rowOneLine else dimens.rowTwoLine)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = role, onClick = onClick) else Modifier)
            .padding(horizontal = dimens.gutter, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alpha = if (enabled) 1f else 0.56f
        if (icon != null) {
            CarIcon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            Spacer(Modifier.width(dimens.gutter))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (supporting != null) {
                Spacer(Modifier.height(4.dp))
                Text(supporting, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(dimens.targetGap))
            trailing()
        }
    }
}

/** Toggle row: the whole row toggles (Role.Switch); the switch is scaled 1.25× on car. */
@Composable
fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
              supporting: String? = null, enabled: Boolean = true, @DrawableRes icon: Int? = null) {
    val on = stringResource(R.string.carlink_state_on)
    val off = stringResource(R.string.carlink_state_off)
    SettingRow(title, modifier.semantics { stateDescription = if (checked) on else off }, supporting, icon, enabled,
        onClick = { onCheckedChange(!checked) }, role = Role.Switch) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled,
            modifier = if (CarLinkTokens.profile == UiProfile.CAR) Modifier.scale(1.25f).padding(horizontal = 8.dp) else Modifier)
    }
}

/** Choice row: title on line 1, current value on line 2, chevron (DiPlay's "Title · Value", split). */
@Composable
fun ChoiceRow(title: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier,
              enabled: Boolean = true, @DrawableRes icon: Int? = null) {
    SettingRow(title, modifier, value, icon, enabled, onClick = onClick, role = Role.Button) {
        CarIcon(R.drawable.carlink_ic_chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A link to another screen, e.g. an engine's own settings. */
@Composable
fun NavigationRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                  supporting: String? = null, @DrawableRes icon: Int? = null, external: Boolean = false) {
    SettingRow(title, modifier, supporting, icon, onClick = onClick, role = Role.Button) {
        CarIcon(if (external) R.drawable.carlink_ic_open_in_new else R.drawable.carlink_ic_chevron_right,
            null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Sentence-case label for a group of radio rows; selection applies immediately. */
@Composable
fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, supporting: String? = null) {
    val selectedText = stringResource(R.string.carlink_state_selected)
    SettingRow(title, modifier.semantics { if (selected) contentDescription = "$title, $selectedText" }, supporting,
        onClick = onClick, role = Role.RadioButton) {
        CarIcon(if (selected) R.drawable.carlink_ic_radio_button_checked else R.drawable.carlink_ic_radio_button_unchecked,
            null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Color.luminanceIsLight(): Boolean = (0.2126f * red + 0.7152f * green + 0.0722f * blue) > 0.5f
