package com.carlink.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.core.LinkStage
import com.carlink.app.core.PhoneKind
import com.carlink.app.detect.PairedPhone
import com.carlink.app.ui.components.PhoneAvatar
import com.carlink.app.ui.components.engineName
import com.carlink.app.ui.components.isolate
import com.carlink.app.ui.premium.GlassButton
import com.carlink.app.ui.premium.GlassCard
import com.carlink.app.ui.premium.IonButton
import com.carlink.app.ui.theme.CarLinkTokens

/** Paired phones: tap to connect (or switch, with a confirmation while another phone projects). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhonesSheet(ui: HomeUi, actions: HomeActions, onDismiss: () -> Unit) {
    val dimens = CarLinkTokens.dimens
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

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = dimens.screenMargin).navigationBarsPadding()) {
            Text(stringResource(R.string.carlink_home_phones_title), style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.carlink_home_phones_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(dimens.gutter))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
                items(ui.home.phones, key = { it.address }) { phone ->
                    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), onClick = { choose(phone) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PhoneAvatar(phone.kind, dimens.avatarRow, active = phone.connected)
                            Spacer(Modifier.width(dimens.gutter))
                            Column(Modifier.weight(1f)) {
                                Text(isolate(phone.name), style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val type = phone.kind.engine?.let { engineName(it) } ?: stringResource(R.string.carlink_home_kind_title)
                                val presence = stringResource(if (phone.connected) R.string.carlink_home_phone_connected
                                    else R.string.carlink_home_phone_not_nearby)
                                Text("$type · $presence", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(dimens.gutter))
            GlassButton(stringResource(R.string.carlink_home_pair_action), { actions.openBluetoothSettings(); onDismiss() },
                icon = R.drawable.carlink_ic_bluetooth)
            Spacer(Modifier.height(dimens.gutter))
        }
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
