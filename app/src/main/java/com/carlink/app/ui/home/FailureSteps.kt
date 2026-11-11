package com.carlink.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.ui.components.isolate
import com.carlink.app.ui.premium.GlassButton
import com.carlink.app.ui.premium.IonButton
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.core.core.LinkProblem
import com.carlink.core.core.LinkStage
import com.carlink.core.core.LinkState
import com.carlink.core.core.Transport
import com.carlink.core.detect.PairedPhone

/**
 * Which of the four steps (0 Bluetooth or cable, 1 handshake, 2 Wi-Fi, 3 picture) a failure
 * stopped on: the step its problem belongs to, else the step the attempt was on
 * ([LinkState.step], kept by the session layer). Null when no step fits (a permission, the
 * engine on this screen), the attempt had no transport, or a check before the start blocked a
 * later step (Wi-Fi off, CarPlay files missing): the timeline would show steps as done that
 * never ran.
 */
internal fun failedStep(link: LinkState): Int? {
    if (link.stage != LinkStage.FAILED || link.transport == null) return null
    val byProblem = when (link.problem) {
        LinkProblem.BLUETOOTH_OFF, LinkProblem.PHONE_NOT_PAIRED, LinkProblem.PAIRING_LOST, LinkProblem.NO_IPHONE_SELECTED,
        LinkProblem.BLUETOOTH, LinkProblem.PHONE_DID_NOT_ANSWER, LinkProblem.EXTERNAL_BLUETOOTH, LinkProblem.EXTERNAL_MODULE,
        LinkProblem.USB -> 0
        LinkProblem.PHONE_REFUSED_WIFI, LinkProblem.PHONE_NEVER_REACHED_CAR, LinkProblem.PHONE_WIFI_OFF -> 2
        LinkProblem.LINK_TOO_WEAK -> 3
        LinkProblem.CARPLAY_AUTH_MISSING, LinkProblem.WIFI_OFF, LinkProblem.LOCATION_OFF, LinkProblem.SAME_WIFI_DETAILS_MISSING,
        LinkProblem.SAME_WIFI_NOT_JOINED, LinkProblem.CAR_HOTSPOT_DETAILS_MISSING, LinkProblem.CAR_HOTSPOT_OFF,
        LinkProblem.PERMISSIONS, LinkProblem.OVERLAY, LinkProblem.VPN_CONSENT, LinkProblem.HEAD_UNIT_SERVER_OFF -> return null
        LinkProblem.ENGINE, null -> null
    }
    return byProblem ?: link.step.takeIf { it in 1..4 }?.minus(1)
}

/** A wireless failure on the Bluetooth or handshake step: forgetting the phone and pairing again may clear it. */
internal fun offersRepair(link: LinkState): Boolean =
    link.transport == Transport.WIRELESS && (failedStep(link) ?: -1) in 0..1 &&
        link.problem != LinkProblem.BLUETOOTH_OFF && link.problem != LinkProblem.EXTERNAL_BLUETOOTH &&
        link.problem != LinkProblem.EXTERNAL_MODULE

/** The failure timeline's step names: what a cable or a wireless start goes through. */
@Composable
internal fun failureStepNames(transport: Transport?): List<String> = listOf(
    stringResource(if (transport == Transport.USB) R.string.carlink_home_fail_step_cable else R.string.carlink_home_fail_step_bluetooth),
    stringResource(R.string.carlink_home_fail_step_handshake),
    stringResource(if (transport == Transport.USB) R.string.carlink_home_fail_step_link else R.string.carlink_home_fail_step_wifi),
    stringResource(R.string.carlink_home_fail_step_picture),
)

/**
 * "Forget and re-pair": asks first, names what goes, and lets the driver pick the phone when
 * there is more than one. [initial] is preselected (the phone that just failed).
 */
@Composable
internal fun ForgetRepairDialog(phones: List<PairedPhone>, initial: PairedPhone?, onConfirm: (PairedPhone) -> Unit,
                                onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dimens = CarLinkTokens.dimens
    var chosen by rememberSaveable { mutableStateOf((initial ?: phones.firstOrNull())?.address) }
    val phone = phones.firstOrNull { it.address == chosen } ?: initial
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(phone?.let { stringResource(R.string.carlink_repair_title, isolate(it.name)) }
            ?: stringResource(R.string.carlink_repair_action)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
                Text(stringResource(R.string.carlink_repair_body), color = scheme.onSurfaceVariant)
                if (phones.size > 1) {
                    Column(Modifier.selectableGroup()) {
                        phones.forEach { p ->
                            val selected = p.address == chosen
                            Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) { chosen = p.address }
                                .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(if (selected) R.drawable.carlink_ic_radio_button_checked
                                    else R.drawable.carlink_ic_radio_button_unchecked), null, Modifier.size(dimens.iconSecondary),
                                    tint = if (selected) scheme.primary else scheme.onSurfaceVariant)
                                Spacer(Modifier.width(dimens.targetGap))
                                Text(isolate(p.name), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            IonButton(stringResource(R.string.carlink_repair_confirm), { phone?.let(onConfirm) }, icon = R.drawable.carlink_ic_bluetooth)
        },
        dismissButton = { GlassButton(stringResource(R.string.carlink_home_cancel), onDismiss) },
        containerColor = scheme.surfaceContainerHigh,
    )
}

/** The phone a failure was about: the paired phone of that name, else Home's default one. */
internal fun failedPhone(ui: HomeUi): PairedPhone? =
    ui.home.phones.firstOrNull { it.name == ui.home.link.phoneName } ?: ui.home.defaultPhone

/** A "Forget and re-pair" glass button that asks first. */
@Composable
internal fun RepairButton(phone: PairedPhone, onRepair: (PairedPhone) -> Unit) {
    var asking by rememberSaveable { mutableStateOf(false) }
    GlassButton(stringResource(R.string.carlink_repair_action), { asking = true }, icon = R.drawable.carlink_ic_bluetooth_searching)
    if (asking) ForgetRepairDialog(listOf(phone), phone, { asking = false; onRepair(it) }, { asking = false })
}
