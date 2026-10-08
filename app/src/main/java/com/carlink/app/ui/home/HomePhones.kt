package com.carlink.app.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.PhoneKind
import com.carlink.app.detect.BluetoothAvailability
import com.carlink.app.detect.PairedPhone
import com.carlink.app.ui.components.CarIcon
import com.carlink.app.ui.components.PhoneAvatar
import com.carlink.app.ui.components.PresenceDot
import com.carlink.app.ui.components.PrimaryButton
import com.carlink.app.ui.components.SecondaryButton
import com.carlink.app.ui.components.TertiaryButton
import com.carlink.app.ui.components.engineName
import com.carlink.app.ui.components.focusRing
import com.carlink.app.ui.components.isolate
import com.carlink.app.ui.components.phoneTypeLabel
import com.carlink.app.ui.theme.CarLinkTokens

/** Whether [phone] is the one the current session belongs to (matched by name; USB has none). */
internal fun LinkState.isSessionPhone(phone: PairedPhone): Boolean =
    stage != LinkStage.IDLE && stage != LinkStage.FAILED && phoneName != null && phoneName == phone.name

/** "Phones" / "Other phones" section title. */
@Composable
internal fun PhonesHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
    )
}

/**
 * One paired phone (DESIGN_SPEC §5.6 phone row): avatar, name, engine and presence in words.
 * The whole row is one target that connects (or returns to) the phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneRow(phone: PairedPhone, link: LinkState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = CarLinkTokens.dimens
    val colors = CarLinkTokens.colors
    val inUse = link.stage == LinkStage.CONNECTED && link.isSessionPhone(phone)
    val shape = MaterialTheme.shapes.medium
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val actionLabel = stringResource(if (inUse) R.string.carlink_home_row_action_return else R.string.carlink_home_row_action_connect)
    Surface(
        modifier = modifier.fillMaxWidth().focusRing(shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (light) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Row(
            Modifier
                .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
                .heightIn(min = dimens.phoneRow)
                .padding(horizontal = dimens.gutter, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhoneAvatar(phone.kind, dimens.avatarRow, active = inUse)
            Spacer(Modifier.width(dimens.gutter))
            Column(Modifier.weight(1f)) {
                Text(phone.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(dimens.targetGap), verticalArrangement = Arrangement.Center) {
                    Text(phoneTypeLabel(phone.kind), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                    if (inUse || phone.connected) {
                        Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                            PresenceDot(colors.success)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(if (inUse) R.string.carlink_home_presence_in_use else R.string.carlink_home_presence_connected),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(dimens.targetGap))
            @DrawableRes val trailing = when {
                inUse -> R.drawable.carlink_ic_play_arrow_filled
                phone.kind == PhoneKind.UNKNOWN -> R.drawable.carlink_ic_help
                else -> R.drawable.carlink_ic_link
            }
            CarIcon(trailing, null, tint = if (inUse) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "All phones (n)" / "Show fewer" on Compact (DESIGN_SPEC §6.1.3). */
@Composable
internal fun AllPhonesToggle(count: Int, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = CarLinkTokens.dimens
    val shape = MaterialTheme.shapes.medium
    Surface(modifier.fillMaxWidth().focusRing(shape), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.clickable(role = Role.Button, onClick = onToggle)
                .heightIn(min = dimens.rowOneLine)
                .padding(horizontal = dimens.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) stringResource(R.string.carlink_home_fewer_phones) else stringResource(R.string.carlink_home_all_phones, count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            CarIcon(if (expanded) R.drawable.carlink_ic_expand_less else R.drawable.carlink_ic_expand_more, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** No phones to list: explain why, with at most one (tonal) action. */
@Composable
internal fun PhonesEmpty(bluetooth: BluetoothAvailability, onOpenBluetoothSettings: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = CarLinkTokens.dimens
    Column(modifier.fillMaxWidth().padding(vertical = dimens.targetGap)) {
        Text(stringResource(R.string.carlink_home_empty_title), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                when (bluetooth) {
                    BluetoothAvailability.READY -> R.string.carlink_home_empty_body
                    BluetoothAvailability.OFF -> R.string.carlink_home_empty_body_bt_off
                    BluetoothAvailability.NO_PERMISSION -> R.string.carlink_home_empty_body_no_access
                    BluetoothAvailability.UNSUPPORTED -> R.string.carlink_home_empty_body_no_bluetooth
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Only when Bluetooth works; otherwise the hero already carries the one recovery action.
        if (bluetooth == BluetoothAvailability.READY) {
            Spacer(Modifier.height(dimens.targetGap))
            SecondaryButton(stringResource(R.string.carlink_home_open_bluetooth_settings), onOpenBluetoothSettings,
                Modifier.focusRing(RoundedCornerShape(dimens.buttonRadius)), icon = R.drawable.carlink_ic_bluetooth)
        }
    }
}

@Composable
internal fun PhonesHint(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.carlink_home_phones_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = CarLinkTokens.colors.onSurfaceTertiary,
        modifier = modifier.padding(top = 8.dp),
    )
}

// ---- Dialogs ----------------------------------------------------------------------------

/** "Switch to {phone}? {engine} will stop." The only confirmation on Home (§6.1.4). */
@Composable
internal fun SwitchPhoneDialog(phone: PairedPhone, currentEngine: Engine?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val engine = currentEngine?.let { engineName(it) }
    CarDialog(
        title = stringResource(R.string.carlink_home_switch_title, isolate(phone.name)),
        body = engine?.let { stringResource(R.string.carlink_home_switch_body, it) },
        onDismiss = onDismiss,
        confirm = {
            PrimaryButton(stringResource(R.string.carlink_home_switch_confirm), onConfirm,
                Modifier.focusRing(RoundedCornerShape(CarLinkTokens.dimens.buttonRadius)), icon = R.drawable.carlink_ic_sync)
        },
    )
}

/** For a phone CarLink couldn't classify: pick iPhone or Android, then connect. */
@Composable
internal fun PhoneKindDialog(phone: PairedPhone, onChoose: (PhoneKind) -> Unit, onDismiss: () -> Unit) {
    val dimens = CarLinkTokens.dimens
    CarDialog(
        title = stringResource(R.string.carlink_home_kind_title),
        body = stringResource(R.string.carlink_home_kind_body, isolate(phone.name)),
        onDismiss = onDismiss,
        options = {
            Column(verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
                KindOption(PhoneKind.IPHONE, R.string.carlink_home_kind_iphone, onChoose)
                KindOption(PhoneKind.ANDROID, R.string.carlink_home_kind_android, onChoose)
            }
        },
    )
}

@Composable
private fun KindOption(kind: PhoneKind, label: Int, onChoose: (PhoneKind) -> Unit) {
    val dimens = CarLinkTokens.dimens
    val shape = MaterialTheme.shapes.medium
    Surface(Modifier.fillMaxWidth().focusRing(shape), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(
            Modifier.clickable(role = Role.Button) { onChoose(kind) }
                .heightIn(min = dimens.rowTwoLine)
                .padding(horizontal = dimens.gutter, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhoneAvatar(kind, dimens.avatarRow)
            Spacer(Modifier.width(dimens.gutter))
            Column(Modifier.weight(1f)) {
                Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
                Text(phoneTypeLabel(kind), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * A tonal dialog sized from the profile tokens (no shadow: tonal surface + scrim, §5.5).
 * Content scrolls so a short landscape window or 1.3× fonts never clip it.
 */
@Composable
private fun CarDialog(
    title: String,
    body: String?,
    onDismiss: () -> Unit,
    confirm: (@Composable () -> Unit)? = null,
    options: (@Composable () -> Unit)? = null,
) {
    val dimens = CarLinkTokens.dimens
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shape = MaterialTheme.shapes.large,
        title = { Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (body != null) {
                    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (options != null) {
                    if (body != null) Spacer(Modifier.height(dimens.gutter))
                    options()
                }
            }
        },
        confirmButton = { confirm?.invoke() },
        dismissButton = {
            TertiaryButton(stringResource(R.string.carlink_home_cancel), onDismiss,
                Modifier.focusRing(RoundedCornerShape(dimens.buttonRadius)))
        },
    )
}
