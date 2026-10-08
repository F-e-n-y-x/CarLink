package com.carlink.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.CarMotion
import com.carlink.app.ui.theme.LocalReduceMotion

/*
 * Building blocks of the setup wizard (DESIGN_SPEC §6.4). Every size comes from
 * CarLinkTokens.dimens, so the same components serve a head unit, a tablet and a phone.
 */

/** Where a wizard step stands, as the step list and the progress bar show it. */
enum class OnboardingStepState { DONE, CURRENT, PENDING }

/**
 * The numbered step list on the start side of the landscape wizard: ✓ for done steps, the
 * current one in primaryContainer. Only done steps can be tapped (to go back and review them).
 */
@Composable
fun OnboardingStepList(
    labels: List<String>,
    current: Int,
    furthest: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(CarLinkTokens.dimens.targetGap / 2)) {
        labels.forEachIndexed { index, label ->
            val state = when {
                index == current -> OnboardingStepState.CURRENT
                index < furthest || index < current -> OnboardingStepState.DONE
                else -> OnboardingStepState.PENDING
            }
            OnboardingStepItem(index + 1, label, state, onClick = { onSelect(index) })
        }
    }
}

@Composable
private fun OnboardingStepItem(number: Int, label: String, state: OnboardingStepState, onClick: () -> Unit) {
    val dimens = CarLinkTokens.dimens
    val colors = MaterialTheme.colorScheme
    val reduceMotion = LocalReduceMotion.current
    val background by animateColorAsState(
        if (state == OnboardingStepState.CURRENT) colors.primaryContainer else Color.Transparent,
        tween(if (reduceMotion) 0 else CarMotion.SHORT), label = "stepBackground",
    )
    val content = when (state) {
        OnboardingStepState.CURRENT -> colors.onPrimaryContainer
        OnboardingStepState.DONE -> colors.onSurface
        OnboardingStepState.PENDING -> colors.onSurfaceVariant
    }
    val stateText = stringResource(
        when (state) {
            OnboardingStepState.DONE -> R.string.carlink_onboarding_step_state_done
            OnboardingStepState.CURRENT -> R.string.carlink_onboarding_step_state_current
            OnboardingStepState.PENDING -> R.string.carlink_onboarding_step_state_pending
        },
    )
    val description = stringResource(R.string.carlink_onboarding_step_label, number, label)
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = dimens.railItem)
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .then(
                if (state == OnboardingStepState.DONE) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
            )
            .padding(horizontal = dimens.targetGap, vertical = 8.dp)
            .clearAndSetSemantics {
                contentDescription = description
                stateDescription = stateText
                if (state == OnboardingStepState.DONE) role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepMarker(number, state, content)
        Spacer(Modifier.width(dimens.targetGap))
        Text(label, style = MaterialTheme.typography.titleMedium, color = content)
    }
}

/** ✓ for a done step, otherwise the step number in a ring (filled for the current step). */
@Composable
private fun StepMarker(number: Int, state: OnboardingStepState, content: Color) {
    val dimens = CarLinkTokens.dimens
    val size = dimens.iconSecondary + 8.dp
    if (state == OnboardingStepState.DONE) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            CarIcon(R.drawable.carlink_ic_check_circle_filled, null, size = size, tint = CarLinkTokens.colors.success)
        }
        return
    }
    val current = state == OnboardingStepState.CURRENT
    Box(
        Modifier.size(size).clip(CircleShape)
            .then(
                if (current) Modifier.background(MaterialTheme.colorScheme.onPrimaryContainer)
                else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.carlink_onboarding_number, number),
            style = MaterialTheme.typography.titleSmall,
            color = if (current) MaterialTheme.colorScheme.primaryContainer else content,
        )
    }
}

/**
 * The portrait/compact progress: one segment per step plus "Step 2 of 5". Exposed to
 * accessibility services as a progress bar.
 */
@Composable
fun OnboardingProgress(current: Int, count: Int, modifier: Modifier = Modifier) {
    val text = stringResource(R.string.carlink_onboarding_step_of, current + 1, count)
    val reduceMotion = LocalReduceMotion.current
    val segmentHeight = CarLinkTokens.dimens.focusRing * 2
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            progressBarRangeInfo = ProgressBarRangeInfo((current + 1).toFloat(), 1f..count.toFloat(), count - 2)
            stateDescription = text
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(count) { index ->
                val color by animateColorAsState(
                    if (index <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    tween(if (reduceMotion) 0 else CarMotion.MEDIUM), label = "segment",
                )
                Box(Modifier.weight(1f).height(segmentHeight).clip(CircleShape).background(color))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A row whose trailing control moves below the text when the row is narrow (phones, the
 * DiLink ⅓ split), so long titles and translations never squeeze the button.
 */
@Composable
fun OnboardingAdaptiveRow(
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    text: @Composable () -> Unit,
) {
    val dimens = CarLinkTokens.dimens
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = maxWidth < dimens.rowTwoLine * 5
        Column(Modifier.fillMaxWidth().padding(horizontal = dimens.gutter, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = dimens.rowTwoLine - 24.dp),
                // With the control below, the glyph heads the text block instead of floating mid-row.
                verticalAlignment = if (trailing != null && stacked) Alignment.Top else Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(dimens.targetGap))
                }
                Box(Modifier.weight(1f)) { text() }
                if (trailing != null && !stacked) {
                    Spacer(Modifier.width(dimens.targetGap))
                    trailing()
                }
            }
            if (trailing != null && stacked) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { trailing() }
            }
        }
    }
}

/** An icon on a tonal circle (56dp car / 44dp handheld), never interactive. */
@Composable
fun OnboardingAvatar(@DrawableRes icon: Int, modifier: Modifier = Modifier, size: Dp = CarLinkTokens.dimens.avatarRow,
                     container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
                     tint: Color = MaterialTheme.colorScheme.onSurface) {
    Box(modifier.size(size).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        CarIcon(icon, null, size = size * 0.5f, tint = tint)
    }
}

/** ✓ (success) or ⚠ (warning) with a word, never color alone (DESIGN_SPEC §7.1). */
@Composable
fun OnboardingStatusLabel(text: String, ok: Boolean, modifier: Modifier = Modifier, live: Boolean = false) {
    val colors = CarLinkTokens.colors
    val tint = if (ok) colors.success else colors.warning
    Row(
        modifier.semantics(mergeDescendants = true) { if (live) liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CarIcon(
            if (ok) R.drawable.carlink_ic_check_circle_filled else R.drawable.carlink_ic_warning_filled,
            null, size = CarLinkTokens.dimens.iconTertiary, tint = tint,
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

/**
 * An automatic check (Bluetooth, USB host, Wi-Fi Direct): what was checked, its result with
 * ✓/⚠, and an optional fix.
 */
@Composable
fun OnboardingCheckRow(
    title: String,
    result: String,
    ok: Boolean,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    OnboardingAdaptiveRow(
        modifier,
        leading = { OnboardingAvatar(icon) },
        trailing = action,
    ) {
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            OnboardingStatusLabel(result, ok)
        }
    }
}

/** What a permission row's trailing control offers. */
enum class PermissionRowStatus { GRANTED, ASK, OPEN_SETTINGS }

/**
 * One permission in the checklist: avatar, title, its need ("Required", "Recommended"), a
 * one-line why, and [Allow] (tonal), which becomes "✓ Allowed" once granted, or
 * [Open settings] once Android stops showing its dialog.
 */
@Composable
fun OnboardingPermissionRow(
    @DrawableRes icon: Int,
    title: String,
    need: String,
    why: String,
    status: PermissionRowStatus,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = CarLinkTokens.dimens
    val colors = CarLinkTokens.colors
    val granted = status == PermissionRowStatus.GRANTED
    OnboardingAdaptiveRow(
        modifier,
        leading = {
            OnboardingAvatar(
                icon,
                container = if (granted) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        },
        trailing = {
            when (status) {
                PermissionRowStatus.GRANTED -> Row(
                    Modifier.defaultMinSize(minHeight = dimens.secondaryButton)
                        .padding(horizontal = 8.dp)
                        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CarIcon(R.drawable.carlink_ic_check_circle_filled, null, size = dimens.iconSecondary, tint = colors.success)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.carlink_onboarding_permission_allowed),
                        style = MaterialTheme.typography.labelLarge, color = colors.success)
                }
                PermissionRowStatus.ASK -> {
                    val description = stringResource(R.string.carlink_onboarding_permission_allow_cd, title)
                    SecondaryButton(stringResource(R.string.carlink_onboarding_permission_allow), onAllow,
                        Modifier.semantics { contentDescription = description })
                }
                PermissionRowStatus.OPEN_SETTINGS -> {
                    val description = stringResource(R.string.carlink_onboarding_permission_open_settings_cd, title)
                    SecondaryButton(stringResource(R.string.carlink_onboarding_permission_open_settings), onOpenSettings,
                        Modifier.semantics { contentDescription = description }, icon = R.drawable.carlink_ic_open_in_new)
                }
            }
        },
    ) {
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(need, style = MaterialTheme.typography.labelMedium, color = CarLinkTokens.colors.onSurfaceTertiary)
            Spacer(Modifier.height(4.dp))
            Text(why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status == PermissionRowStatus.OPEN_SETTINGS) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.carlink_onboarding_permission_blocked),
                    style = MaterialTheme.typography.bodyMedium, color = colors.warning)
            }
        }
    }
}

/**
 * A large single-choice card (iPhone / Android phone). [horizontal] lays the glyph beside the
 * text, for narrow or short windows where two tall cards would not fit.
 */
@Composable
fun OnboardingChoiceCard(
    @DrawableRes icon: Int,
    title: String,
    supporting: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
) {
    val dimens = CarLinkTokens.dimens
    val colors = MaterialTheme.colorScheme
    val reduceMotion = LocalReduceMotion.current
    val container by animateColorAsState(
        if (selected) colors.primaryContainer else colors.surfaceContainer,
        tween(if (reduceMotion) 0 else CarMotion.SHORT), label = "choice",
    )
    val content = if (selected) colors.onPrimaryContainer else colors.onSurface
    val lightTheme = colors.background.run { 0.2126f * red + 0.7152f * green + 0.0722f * blue } > 0.5f
    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
        border = when {
            selected -> BorderStroke(2.dp, colors.primary)
            lightTheme -> BorderStroke(1.dp, colors.outlineVariant)
            else -> null
        },
    ) {
        val avatar: @Composable () -> Unit = {
            OnboardingAvatar(
                icon, size = dimens.avatarHero,
                container = if (selected) colors.surface else colors.surfaceContainerHighest,
                tint = content,
            )
        }
        val radio: @Composable () -> Unit = {
            CarIcon(
                if (selected) R.drawable.carlink_ic_radio_button_checked else R.drawable.carlink_ic_radio_button_unchecked,
                null, tint = if (selected) colors.primary else colors.onSurfaceVariant,
            )
        }
        val texts: @Composable (TextAlign) -> Unit = { align ->
            Column {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = content, textAlign = align,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(supporting, style = MaterialTheme.typography.bodyMedium, textAlign = align,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth())
            }
        }
        if (horizontal) {
            Row(Modifier.padding(dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
                avatar()
                Spacer(Modifier.width(dimens.targetGap))
                Box(Modifier.weight(1f)) { texts(TextAlign.Start) }
                Spacer(Modifier.width(dimens.targetGap))
                radio()
            }
        } else {
            Box(Modifier.padding(dimens.gutter)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    avatar()
                    Spacer(Modifier.height(dimens.targetGap))
                    texts(TextAlign.Center)
                }
                Box(Modifier.align(Alignment.TopEnd)) { radio() }
            }
        }
    }
}

/** Numbered phone-side instructions ("1 Pair…, 2 On iPhone…, 3 Tap…"). */
@Composable
fun OnboardingNumberedSteps(steps: List<String>, modifier: Modifier = Modifier) {
    val dimens = CarLinkTokens.dimens
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
        steps.forEachIndexed { index, text ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(dimens.iconSecondary + 8.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.carlink_onboarding_number, index + 1),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.width(dimens.targetGap))
                Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(top = 4.dp))
            }
        }
    }
}
