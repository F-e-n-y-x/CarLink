package com.carlink.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.core.LinkStage
import com.carlink.app.core.PhoneKind
import com.carlink.app.detect.PairedPhone
import com.carlink.app.ui.components.engineName
import com.carlink.app.ui.components.isolate
import com.carlink.app.ui.components.phoneIcon
import com.carlink.app.ui.premium.GlassButton
import com.carlink.app.ui.premium.GlassCard
import com.carlink.app.ui.premium.GlassSheet
import com.carlink.app.ui.premium.IconTile
import com.carlink.app.ui.premium.IonButton
import com.carlink.app.ui.premium.SheetHeader
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.UiProfile

/** Paired phones: tap to connect (or switch, with a confirmation while another phone projects). */
@Composable
internal fun PhonesSheet(ui: HomeUi, actions: HomeActions, onDismiss: () -> Unit) {
    val dimens = CarLinkTokens.dimens
    val colors = CarLinkTokens.colors
    val scheme = MaterialTheme.colorScheme
    val car = CarLinkTokens.profile == UiProfile.CAR
    var askKind by remember { mutableStateOf<PairedPhone?>(null) }
    var confirmSwitch by remember { mutableStateOf<PairedPhone?>(null) }
    val live = ui.home.link.stage == LinkStage.CONNECTED

    fun choose(phone: PairedPhone) {
        when {
            phone.kind == PhoneKind.UNKNOWN -> askKind = phone
            live && phone.name != ui.home.link.phoneName -> confirmSwitch = phone
            live -> { actions.returnToProjection(); onDismiss() }
            else -> { actions.connect(phone); onDismiss() }
        }
    }

    GlassSheet(onDismiss) {
        SheetHeader(stringResource(R.string.carlink_home_phones_title), stringResource(R.string.carlink_home_phones_hint))
        Spacer(Modifier.height(dimens.gutter))
        // One glass group, rows split by hairlines: the same anatomy as Home's status bar and Settings.
        GlassCard(Modifier.fillMaxWidth(), shape = statusPanelShape(), contentPadding = PaddingValues(0.dp)) {
            Column {
                ui.home.phones.forEachIndexed { index, phone ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().padding(start = dimens.gutter * 2 + dimens.avatarRow, end = dimens.gutter)
                            .height(1.dp).background(colors.hairline))
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = dimens.rowTwoLine)
                            .clickable(role = Role.Button) { choose(phone) }
                            .padding(horizontal = dimens.gutter, vertical = if (car) 16.dp else 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTile(phoneIcon(phone.kind), tint = if (phone.connected) colors.success else null, size = dimens.avatarRow)
                        Spacer(Modifier.width(dimens.gutter))
                        Column(Modifier.weight(1f)) {
                            Text(isolate(phone.name), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val type = phone.kind.engine?.let { engineName(it) } ?: stringResource(R.string.carlink_home_kind_title)
                            val presence = stringResource(if (phone.connected) R.string.carlink_home_phone_connected
                                else R.string.carlink_home_phone_not_nearby)
                            Text("$type · $presence", style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant, maxLines = 2)
                        }
                        Spacer(Modifier.width(12.dp))
                        Icon(painterResource(R.drawable.carlink_ic_chevron_right), null, Modifier.size(dimens.iconTertiary),
                            tint = colors.onSurfaceTertiary)
                    }
                }
            }
        }
        Spacer(Modifier.height(dimens.gutter))
        GlassButton(stringResource(R.string.carlink_home_pair_action), { actions.openBluetoothSettings(); onDismiss() },
            icon = R.drawable.carlink_ic_bluetooth)
    }

    askKind?.let { phone ->
        AlertDialog(
            onDismissRequest = { askKind = null },
            title = { Text(stringResource(R.string.carlink_home_kind_title)) },
            text = { Text(isolate(phone.name)) },
            confirmButton = {
                IonButton(stringResource(R.string.carlink_home_kind_iphone), {
                    actions.setPhoneKind(phone, PhoneKind.IPHONE); askKind = null
                    actions.connect(phone.copy(kind = PhoneKind.IPHONE)); onDismiss()
                })
            },
            dismissButton = {
                GlassButton(stringResource(R.string.carlink_home_kind_android), {
                    actions.setPhoneKind(phone, PhoneKind.ANDROID); askKind = null
                    actions.connect(phone.copy(kind = PhoneKind.ANDROID)); onDismiss()
                })
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    confirmSwitch?.let { phone ->
        AlertDialog(
            onDismissRequest = { confirmSwitch = null },
            title = { Text(stringResource(R.string.carlink_home_switch_title, isolate(phone.name))) },
            text = { Text(stringResource(R.string.carlink_home_switch_body, ui.home.link.engine?.let { engineName(it) } ?: "")) },
            confirmButton = {
                IonButton(stringResource(R.string.carlink_home_switch_action), {
                    confirmSwitch = null; actions.switchTo(phone); onDismiss()
                })
            },
            dismissButton = { GlassButton(stringResource(R.string.carlink_home_cancel), { confirmSwitch = null }) },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}
