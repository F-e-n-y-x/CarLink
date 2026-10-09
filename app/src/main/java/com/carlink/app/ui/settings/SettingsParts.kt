package com.carlink.app.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.ui.components.engineName
import com.carlink.app.ui.components.focusRing
import com.carlink.app.ui.premium.Eyebrow
import com.carlink.app.ui.premium.GlassCard
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.UiProfile

/*
 * Settings building blocks on the Graphite and Ion materials (design/RESEARCH_PREMIUM_UX.md
 * §6.3, §8.3): glass group cards with full-width rows (≥ the profile's two-line row height),
 * glass tiles for categories and presets, and small status tags. Accent (Ion) marks only what
 * is selected or switched on.
 */

internal val isCar: Boolean @Composable get() = CarLinkTokens.profile == UiProfile.CAR

/** Horizontal padding inside group cards. */
internal val rowPadding: Dp @Composable get() = if (isCar) 32.dp else 20.dp

/** A glass card holding rows. [frame] draws a colored edge (amber for Lab). */
@Composable
internal fun GlassGroup(
    modifier: Modifier = Modifier,
    frame: Color? = null,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    GlassCard(
        modifier.fillMaxWidth().then(if (frame != null) Modifier.border(2.dp, frame.copy(alpha = 0.7f), shape) else Modifier),
        shape = shape,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (header != null) {
                Box(Modifier.padding(start = rowPadding, end = rowPadding, top = if (isCar) 24.dp else 16.dp, bottom = 4.dp)) { header() }
            }
            content()
        }
    }
}

/** Hairline between rows of a group. */
@Composable
internal fun GroupDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = rowPadding).height(1.dp)
        .background(CarLinkTokens.colors.hairline))
}

/** Eyebrow header of a group, with an optional leading glyph. */
@Composable
internal fun GroupHeader(text: String, color: Color = CarLinkTokens.colors.onSurfaceTertiary, @DrawableRes icon: Int? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { heading() }) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(if (isCar) 24.dp else 16.dp), tint = color)
            Spacer(Modifier.width(10.dp))
        }
        Eyebrow(text, color = color)
    }
}

/** "CARPLAY" / "ANDROID AUTO" header with the engine's glyph. */
@Composable
internal fun EngineHeader(engine: Engine, color: Color? = null) {
    val colors = CarLinkTokens.colors
    val tint = color ?: when (engine) {
        Engine.CARPLAY -> colors.engineCarPlay
        Engine.ANDROID_AUTO -> colors.engineAndroidAuto
    }
    GroupHeader(engineName(engine), color = tint,
        icon = if (engine == Engine.CARPLAY) R.drawable.carlink_ic_phone_iphone else R.drawable.carlink_ic_android)
}

/** A rounded glass tile with a glyph: the leading mark of a row. */
@Composable
internal fun RowGlyph(@DrawableRes icon: Int, tint: Color = MaterialTheme.colorScheme.onSurface) {
    // The same rounded tile as Home's status cells, so every list in CarLink reads alike.
    val accent = tint.takeIf { it != MaterialTheme.colorScheme.onSurface }
    com.carlink.app.ui.premium.IconTile(icon, tint = accent, size = if (isCar) 56.dp else 40.dp)
}

/** A small status pill under a row's description ("Applies at next connection"). */
@Composable
internal fun Tag(text: String, color: Color, @DrawableRes icon: Int? = null) {
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).border(1.dp, color.copy(alpha = 0.30f), CircleShape)
            .padding(horizontal = if (isCar) 14.dp else 10.dp, vertical = if (isCar) 4.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(if (isCar) 22.dp else 14.dp), tint = color)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Composable
internal fun NextConnectionTag() =
    Tag(stringResource(R.string.carlink_settings_applies_next), CarLinkTokens.colors.warning, R.drawable.carlink_ic_schedule)

/**
 * The anatomy of every Settings row (§8.3): glyph, title, one-line description, tags under
 * it, and a trailing value or switch. The whole row is the touch target.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    role: Role? = null,
    /** Shown on its own line when the row is narrow, otherwise trailing. */
    value: String? = null,
    tags: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val dimens = CarLinkTokens.dimens
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val narrow = maxWidth < 560.dp
        val valueMaxWidth = maxWidth * 0.36f
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = if (description == null && value == null) dimens.rowOneLine else dimens.rowTwoLine)
                .focusRing(RoundedCornerShape(24.dp))
                .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = role, onClick = onClick) else Modifier)
                .padding(horizontal = rowPadding, vertical = if (isCar) 18.dp else 12.dp)
                .semantics(mergeDescendants = true) {}
                .alpha(if (enabled) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                RowGlyph(icon)
                Spacer(Modifier.width(if (isCar) 24.dp else 16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                if (value != null && narrow) {
                    Spacer(Modifier.height(2.dp))
                    Text(value, style = MaterialTheme.typography.titleSmall, color = scheme.primary, maxLines = 2)
                }
                if (description != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                if (tags != null) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { tags() }
                }
            }
            if (value != null && !narrow) {
                Spacer(Modifier.width(dimens.gutter))
                Text(value, style = MaterialTheme.typography.titleSmall, color = scheme.primary, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = valueMaxWidth))
            }
            if (trailing != null) {
                Spacer(Modifier.width(if (isCar) 16.dp else 12.dp))
                trailing()
            }
        }
    }
}

/** A row that opens a sheet; its current value is shown in Ion (it is the tappable state). */
@Composable
internal fun ChoiceRow(
    title: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    description: String? = null, @DrawableRes icon: Int? = null, enabled: Boolean = true,
    tags: (@Composable () -> Unit)? = null,
) {
    SettingsRow(title, modifier, description, icon, enabled, onClick, Role.Button, value = value, tags = tags) {
        Icon(painterResource(R.drawable.carlink_ic_chevron_right), null, Modifier.size(CarLinkTokens.dimens.iconTertiary + 4.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A link to another screen. */
@Composable
internal fun LinkRow(title: String, onClick: () -> Unit, description: String? = null, @DrawableRes icon: Int? = null,
                     external: Boolean = false, value: String? = null) {
    SettingsRow(title, description = description, icon = icon, onClick = onClick, role = Role.Button, value = value) {
        Icon(painterResource(if (external) R.drawable.carlink_ic_open_in_new else R.drawable.carlink_ic_chevron_right), null,
            Modifier.size(CarLinkTokens.dimens.iconTertiary + 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A switch row; the whole row toggles. */
@Composable
internal fun SwitchRow(
    title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    description: String? = null, @DrawableRes icon: Int? = null, enabled: Boolean = true,
    tags: (@Composable () -> Unit)? = null,
) {
    val on = stringResource(R.string.carlink_state_on)
    val off = stringResource(R.string.carlink_state_off)
    SettingsRow(title, modifier.semantics { stateDescription = if (checked) on else off }, description, icon, enabled,
        onClick = { onCheckedChange(!checked) }, role = Role.Switch, tags = tags) {
        IonSwitch(checked, enabled)
    }
}

/** Material switch in Graphite and Ion colors, 1.25× in the car. */
@Composable
internal fun IonSwitch(checked: Boolean, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Switch(
        checked = checked, onCheckedChange = null, enabled = enabled,
        modifier = if (isCar) Modifier.padding(horizontal = 8.dp).scale(1.25f) else Modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = scheme.onPrimary, checkedTrackColor = scheme.primary, checkedBorderColor = scheme.primary,
            uncheckedThumbColor = scheme.onSurfaceVariant, uncheckedTrackColor = scheme.onSurface.copy(alpha = 0.08f),
            uncheckedBorderColor = scheme.onSurfaceVariant.copy(alpha = 0.6f),
        ),
    )
}

/**
 * A glass surface that can be "selected": Ion-tinted fill and edge. Used for category tiles
 * and preset tiles.
 */
@Composable
internal fun SelectableGlass(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    role: Role = Role.Button,
    /** Selected shows as a lit Ion bar on the leading edge (navigation), not an Ion outline. */
    accentEdge: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = CarLinkTokens.colors
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val selectedText = stringResource(R.string.carlink_state_selected)
    val fill = when {
        selected && accentEdge -> colors.glassFillPressed
        selected -> scheme.primary.copy(alpha = if (pressed) 0.18f else 0.10f)
        pressed -> colors.glassFillPressed
        else -> colors.glassFill
    }
    val border = if (selected && !accentEdge) BorderStroke(1.5.dp, scheme.primary.copy(alpha = 0.7f))
    else com.carlink.app.ui.premium.glassBorder()
    Box(
        modifier
            .focusRing(shape)
            .clip(shape)
            .background(fill)
            .border(border, shape)
            .clickable(interaction, indication = ripple(), role = role, onClick = onClick)
            .then(if (selected && role != Role.Switch) Modifier.semantics { stateDescription = selectedText } else Modifier),
    ) {
        content()
        if (selected && accentEdge) {
            // The lit edge: a short Ion bar, like a selector lamp.
            val bar = if (isCar) 5.dp else 4.dp
            Box(Modifier.align(Alignment.CenterStart).padding(start = bar).size(width = bar, height = if (isCar) 32.dp else 22.dp)
                .clip(CircleShape).background(scheme.primary))
        }
    }
}

/** A category tile of the landscape rail. */
@Composable
internal fun RailTile(title: String, @DrawableRes icon: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
                      @DrawableRes trailingIcon: Int? = null) {
    val scheme = MaterialTheme.colorScheme
    val dimens = CarLinkTokens.dimens
    SelectableGlass(selected, onClick, modifier.fillMaxWidth().heightIn(min = if (isCar) 84.dp else 56.dp),
        shape = RoundedCornerShape(if (isCar) 28.dp else 20.dp), role = Role.Tab, accentEdge = true) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (isCar) 84.dp else 56.dp).padding(horizontal = if (isCar) 24.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, Modifier.size(dimens.iconSecondary), tint = if (selected) scheme.primary else scheme.onSurfaceVariant)
            Spacer(Modifier.width(if (isCar) 20.dp else 14.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (trailingIcon != null) {
                Icon(painterResource(trailingIcon), null, Modifier.size(dimens.iconTertiary), tint = scheme.onSurfaceVariant)
            }
        }
    }
}

/** A category tile of the portrait grid: glyph, name and what is inside. */
@Composable
internal fun GridTile(title: String, summary: String, @DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    SelectableGlass(false, onClick, modifier, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(if (isCar) 28.dp else 18.dp)) {
            RowGlyph(icon, tint = scheme.primary)
            Spacer(Modifier.height(if (isCar) 20.dp else 14.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(summary, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2,
                overflow = TextOverflow.Ellipsis, minLines = 2)
        }
    }
}

/** A round glass icon button (back, close). */
@Composable
internal fun RoundGlassButton(@DrawableRes icon: Int, contentDescription: String, onClick: () -> Unit) {
    com.carlink.app.ui.premium.RoundGlassIconButton(icon, contentDescription, onClick)
}

/** The "Show advanced" switch as a glass pill (it sits in the header on wide layouts). */
@Composable
internal fun AdvancedPill(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val on = stringResource(R.string.carlink_state_on)
    val off = stringResource(R.string.carlink_state_off)
    val dimens = CarLinkTokens.dimens
    SelectableGlass(checked, { onCheckedChange(!checked) },
        Modifier.heightIn(min = dimens.minTouch).semantics { stateDescription = if (checked) on else off },
        shape = CircleShape, role = Role.Switch) {
        Row(Modifier.heightIn(min = dimens.minTouch).padding(start = if (isCar) 28.dp else 18.dp, end = if (isCar) 12.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.carlink_ic_tune), null, Modifier.size(dimens.iconTertiary + 4.dp),
                tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.carlink_settings_show_advanced), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            IonSwitch(checked)
        }
    }
}
