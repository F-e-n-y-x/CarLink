package com.carlink.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.carlink.app.R
import com.carlink.app.ui.settings.ChoiceSheet
import com.carlink.app.ui.settings.Preset
import com.carlink.app.ui.settings.connectionMethodLabel
import com.carlink.app.ui.settings.methodIcon
import com.carlink.core.settings.ConnectionMethod
import com.carlink.core.settings.WirelessPath

/**
 * Opened from Home's Network status: the same connection types as Settings › Connection, so the
 * driver can switch without leaving Home. The choice applies from the next connection.
 */
@Composable
internal fun NetworkSheet(ui: HomeUi, actions: HomeActions, onDismiss: () -> Unit) {
    val now = when (ui.network?.path) {
        WirelessPath.SAME_WIFI -> stringResource(R.string.carlink_home_net_same_wifi)
        WirelessPath.CAR_HOTSPOT -> stringResource(R.string.carlink_home_net_hotspot)
        WirelessPath.WIFI_DIRECT, null -> stringResource(R.string.carlink_home_net_direct)
    }
    ChoiceSheet(
        stringResource(R.string.carlink_home_network_sheet_title),
        stringResource(R.string.carlink_home_network_sheet_now, now),
        nextConnection = true,
        options = ConnectionMethod.entries.map { m ->
            Preset(m, connectionMethodLabel(m), stringResource(when (m) {
                ConnectionMethod.AUTOMATIC -> R.string.carlink_settings_method_auto_detail
                ConnectionMethod.SAME_WIFI -> R.string.carlink_settings_method_same_wifi_detail
                ConnectionMethod.CAR_HOTSPOT -> R.string.carlink_settings_method_hotspot_detail
                ConnectionMethod.WIFI_DIRECT -> R.string.carlink_settings_method_direct_detail
            }), methodIcon(m))
        },
        selected = ui.network?.method ?: ConnectionMethod.AUTOMATIC,
        onSelect = { m -> actions.setConnectionMethod(m); onDismiss() },
        onDismiss = onDismiss,
    )
}
