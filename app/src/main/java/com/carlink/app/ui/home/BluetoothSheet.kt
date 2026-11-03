package com.carlink.app.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.ui.premium.GlassCard
import com.carlink.app.ui.premium.GlassSheet
import com.carlink.app.ui.premium.IconTile
import com.carlink.app.ui.premium.SheetHeader
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.UiProfile

/**
 * The Bluetooth chip on a stereo with its own Bluetooth module: which Bluetooth phones use. Same
 * anatomy as [PhonesSheet] (one glass group, rows split by hairlines).
 */
@Composable
internal fun BluetoothSheet(useAndroid: Boolean, onPick: (Boolean) -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val dimens = CarLinkTokens.dimens
    val colors = CarLinkTokens.colors
    val scheme = MaterialTheme.colorScheme
    val car = CarLinkTokens.profile == UiProfile.CAR

    @Composable
    fun Divider() = Box(Modifier.fillMaxWidth().padding(start = dimens.gutter * 2 + dimens.avatarRow, end = dimens.gutter)
        .height(1.dp).background(colors.hairline))

    @Composable
    fun Choice(android: Boolean, @DrawableRes icon: Int, title: Int, body: Int) {
        val selected = android == useAndroid
        Row(
            Modifier.fillMaxWidth().heightIn(min = dimens.rowTwoLine)
                .selectable(selected, role = Role.RadioButton) { onPick(android); onDismiss() }
                .padding(horizontal = dimens.gutter, vertical = if (car) 16.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon, tint = if (selected) colors.success else null, size = dimens.avatarRow)
            Spacer(Modifier.width(dimens.gutter))
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 2)
            }
            Spacer(Modifier.width(12.dp))
            Icon(painterResource(if (selected) R.drawable.carlink_ic_radio_button_checked else R.drawable.carlink_ic_radio_button_unchecked),
                null, Modifier.size(dimens.iconTertiary), tint = if (selected) scheme.primary else scheme.onSurfaceVariant)
        }
    }

    GlassSheet(onDismiss) {
        SheetHeader(stringResource(R.string.carlink_choose_bt_title), stringResource(R.string.carlink_choose_bt_body))
        Spacer(Modifier.height(dimens.gutter))
        GlassCard(Modifier.fillMaxWidth(), shape = statusPanelShape(), contentPadding = PaddingValues(0.dp)) {
            Column {
                Choice(false, R.drawable.carlink_ic_bluetooth, R.string.carlink_choose_bt_module, R.string.carlink_choose_bt_module_body)
                Divider()
                Choice(true, R.drawable.carlink_ic_tablet_android, R.string.carlink_choose_bt_android, R.string.carlink_choose_bt_android_body)
                // Android's own pairing only matters when its Bluetooth is the one in use.
                if (useAndroid) {
                    Divider()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = dimens.rowTwoLine)
                            .selectable(false, role = Role.Button) { onSettings(); onDismiss() }
                            .padding(horizontal = dimens.gutter, vertical = if (car) 16.dp else 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTile(R.drawable.carlink_ic_settings, tint = scheme.primary, size = dimens.avatarRow)
                        Spacer(Modifier.width(dimens.gutter))
                        Text(stringResource(R.string.carlink_home_bluetooth_settings), style = MaterialTheme.typography.titleMedium,
                            color = scheme.primary, modifier = Modifier.weight(1f))
                        Icon(painterResource(R.drawable.carlink_ic_open_in_new), null, Modifier.size(dimens.iconTertiary),
                            tint = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
