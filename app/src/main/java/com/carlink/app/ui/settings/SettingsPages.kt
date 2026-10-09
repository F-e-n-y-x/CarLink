package com.carlink.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.settings.AdvancedSetting
import com.carlink.app.settings.Applies
import com.carlink.app.settings.ConnectionMethod
import com.carlink.app.settings.Control
import com.carlink.app.settings.DayNight
import com.carlink.app.settings.SettingsCategory
import com.carlink.app.settings.Tier
import com.carlink.app.ui.Route
import com.carlink.app.ui.premium.GlassCard
import com.carlink.app.ui.premium.IonButton
import com.carlink.app.ui.theme.CarLinkTokens

/** How many taps on Version unlock Lab. */
internal const val LAB_TAPS = 7

/**
 * One category: its Essentials, then (with Show advanced) its engine settings grouped by
 * engine, then (once unlocked) its Lab settings in an amber frame.
 */
@Composable
internal fun CategoryPage(
    category: SettingsCategory,
    ui: SettingsUi,
    actions: SettingsActions,
    openSheet: (String) -> Unit,
    versionTaps: Int,
    onVersionTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CarLinkTokens.dimens.gutter)) {
        when (category) {
            SettingsCategory.CONNECTION -> ConnectionEssentials(ui, actions, openSheet)
            SettingsCategory.DISPLAY -> DisplayEssentials(ui, openSheet)
            SettingsCategory.SOUND -> SoundEssentials(ui, openSheet)
            SettingsCategory.NAVIGATION -> NavigationEssentials(ui, actions)
            SettingsCategory.CAR -> CarEssentials(ui, openSheet)
            SettingsCategory.GENERAL -> GeneralEssentials(ui, actions, openSheet, versionTaps, onVersionTap)
        }
        if (ui.unified.showAdvanced) EngineSection(category, Tier.ADVANCED, ui, actions, openSheet)
        if (ui.unified.labUnlocked) EngineSection(category, Tier.LAB, ui, actions, openSheet)
    }
}

// ---- Essentials -----------------------------------------------------------------------------

@Composable
private fun ConnectionEssentials(ui: SettingsUi, actions: SettingsActions, openSheet: (String) -> Unit) {
    val c = ui.connection
    val method = ui.unified.connectionMethod
    if (c.autoConnectPaused) PausedCard(actions::resumeAutoConnect)
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_settings_group_start)) }) {
        SwitchRow(stringResource(R.string.carlink_settings_auto_bluetooth), c.autoConnectBluetooth, actions::setAutoConnectBluetooth,
            description = stringResource(R.string.carlink_settings_auto_bluetooth_detail), icon = R.drawable.carlink_ic_bluetooth)
        GroupDivider()
        SwitchRow(stringResource(R.string.carlink_settings_auto_usb), c.autoConnectUsb, actions::setAutoConnectUsb,
            description = stringResource(R.string.carlink_settings_auto_usb_detail), icon = R.drawable.carlink_ic_usb)
        GroupDivider()
        SwitchRow(stringResource(R.string.carlink_settings_start_boot), c.startOnBoot, actions::setStartOnBoot,
            description = stringResource(R.string.carlink_settings_start_boot_detail), icon = R.drawable.carlink_ic_power_settings_new)
    }
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_settings_group_wireless)) }) {
        ChoiceRow(stringResource(R.string.carlink_settings_method), connectionMethodLabel(method),
            { openSheet(SheetKey.of(EssentialSheet.CONNECTION_METHOD)) },
            description = stringResource(R.string.carlink_settings_method_detail), icon = methodIcon(method))
        // Network details only where the chosen method can use them.
        if (method == ConnectionMethod.AUTOMATIC || method == ConnectionMethod.CAR_HOTSPOT) {
            GroupDivider()
            ChoiceRow(stringResource(R.string.carlink_settings_hotspot),
                ui.unified.carHotspot.ssid.ifEmpty { stringResource(R.string.carlink_settings_not_set) },
                { openSheet(SheetKey.of(EssentialSheet.CAR_HOTSPOT)) },
                description = stringResource(R.string.carlink_settings_hotspot_detail), icon = R.drawable.carlink_ic_wifi_tethering)
        }
        if (method == ConnectionMethod.AUTOMATIC || method == ConnectionMethod.SAME_WIFI) {
            GroupDivider()
            ChoiceRow(stringResource(R.string.carlink_settings_same_wifi),
                ui.unified.homeWifi.ssid.ifEmpty { stringResource(R.string.carlink_settings_not_set) },
                { openSheet(SheetKey.of(EssentialSheet.SAME_WIFI)) },
                description = stringResource(R.string.carlink_settings_same_wifi_detail), icon = R.drawable.carlink_ic_wifi)
        }
    }
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_settings_group_phones)) }) {
        LinkRow(stringResource(R.string.carlink_settings_bluetooth), actions::openBluetoothSettings,
            stringResource(R.string.carlink_settings_bluetooth_detail), R.drawable.carlink_ic_bluetooth_searching, external = true)
        GroupDivider()
        LinkRow(stringResource(R.string.carlink_settings_permissions), actions::openAppSettings,
            stringResource(R.string.carlink_settings_permissions_detail), R.drawable.carlink_ic_lock, external = true)
    }
}

/** Shown while the driver's disconnect keeps automatic starts paused. */
@Composable
private fun PausedCard(onResume: () -> Unit) {
    val colors = CarLinkTokens.colors
    GlassCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(rowPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (isCar) 14.dp else 10.dp).clip(CircleShape).background(colors.warning))
            Spacer(Modifier.width(if (isCar) 20.dp else 14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.carlink_settings_paused_title), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.carlink_settings_paused_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(CarLinkTokens.dimens.gutter))
            IonButton(stringResource(R.string.carlink_settings_resume), onResume, icon = R.drawable.carlink_ic_play_arrow_filled)
        }
    }
}

@Composable
private fun DisplayEssentials(ui: SettingsUi, openSheet: (String) -> Unit) {
    val u = ui.unified
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_settings_group_projection)) }) {
        ChoiceRow(stringResource(R.string.carlink_settings_picture), pictureLabel(u.picture), { openSheet(SheetKey.of(EssentialSheet.PICTURE)) },
            description = stringResource(R.string.carlink_settings_picture_detail), icon = R.drawable.carlink_ic_aspect_ratio)
        GroupDivider()
        ChoiceRow(stringResource(R.string.carlink_settings_interface_size), interfaceSizeLabel(u.interfaceSize),
            { openSheet(SheetKey.of(EssentialSheet.INTERFACE_SIZE)) },
            description = stringResource(R.string.carlink_settings_interface_size_detail), icon = R.drawable.carlink_ic_text_fields)
        GroupDivider()
        ChoiceRow(stringResource(R.string.carlink_settings_daynight), dayNightLabel(u.dayNight), { openSheet(SheetKey.of(EssentialSheet.DAY_NIGHT)) },
            description = stringResource(R.string.carlink_settings_daynight_detail), icon = dayNightIcon(u.dayNight))
        if (u.dayNight == DayNight.SCHEDULE) {
            GroupDivider()
            ChoiceRow(stringResource(R.string.carlink_settings_night_start), clockText(u.nightStartMinute),
                { openSheet(SheetKey.of(EssentialSheet.NIGHT_START)) }, icon = R.drawable.carlink_ic_dark_mode)
            GroupDivider()
            ChoiceRow(stringResource(R.string.carlink_settings_night_end), clockText(u.nightEndMinute),
                { openSheet(SheetKey.of(EssentialSheet.NIGHT_END)) }, icon = R.drawable.carlink_ic_light_mode)
        }
    }
}

@Composable
private fun SoundEssentials(ui: SettingsUi, openSheet: (String) -> Unit) {
    val bluetooth = ui.unified.soundThroughCarBluetooth
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_settings_group_sound)) }) {
        ChoiceRow(stringResource(R.string.carlink_settings_sound),
            stringResource(if (bluetooth) R.string.carlink_settings_sound_bluetooth else R.string.carlink_settings_sound_carlink),
            { openSheet(SheetKey.of(EssentialSheet.SOUND)) }, description = stringResource(R.string.carlink_settings_sound_detail),
            icon = if (bluetooth) R.drawable.carlink_ic_bluetooth else R.drawable.carlink_ic_volume_up)
    }
}

@Composable
private fun NavigationEssentials(ui: SettingsUi, actions: SettingsActions) {
    val u = ui.unified
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_settings_group_location)) }) {
        SwitchRow(stringResource(R.string.carlink_settings_location), u.sendCarLocation,
            { on -> actions.update { it.copy(sendCarLocation = on) } },
            description = stringResource(R.string.carlink_settings_location_detail), icon = R.drawable.carlink_ic_location_on)
        if (ui.isByd) {
            GroupDivider()
            SwitchRow(stringResource(R.string.carlink_settings_byd), u.bydDirections,
                { on -> actions.update { it.copy(bydDirections = on) } },
                description = stringResource(R.string.carlink_settings_byd_detail), icon = R.drawable.carlink_ic_navigation)
        }
    }
}

@Composable
private fun CarEssentials(ui: SettingsUi, openSheet: (String) -> Unit) {
    val right = ui.unified.rightHandDrive
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_settings_group_car)) }) {
        ChoiceRow(stringResource(R.string.carlink_settings_driving_side),
            stringResource(if (right) R.string.carlink_settings_drive_right else R.string.carlink_settings_drive_left),
            { openSheet(SheetKey.of(EssentialSheet.DRIVING_SIDE)) },
            description = stringResource(R.string.carlink_settings_driving_side_detail), icon = R.drawable.carlink_ic_directions_car)
    }
}

@Composable
private fun GeneralEssentials(ui: SettingsUi, actions: SettingsActions, openSheet: (String) -> Unit, versionTaps: Int,
                              onVersionTap: () -> Unit) {
    val d = ui.display
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_settings_group_carlink)) }) {
        ChoiceRow(stringResource(R.string.carlink_settings_appearance), themeLabel(d.themeMode),
            { openSheet(SheetKey.of(EssentialSheet.APPEARANCE)) },
            description = stringResource(R.string.carlink_settings_appearance_detail), icon = themeIcon(d.themeMode))
        GroupDivider()
        ChoiceRow(stringResource(R.string.carlink_settings_layout), layoutLabel(d.uiProfileMode),
            { openSheet(SheetKey.of(EssentialSheet.LAYOUT)) },
            description = stringResource(R.string.carlink_settings_layout_detail), icon = layoutIcon(d.uiProfileMode))
        GroupDivider()
        ChoiceRow(stringResource(R.string.carlink_settings_carlink_size),
            "${carLinkScaleLabel(d.interfaceScale)} · ${percentText(nearestScale(d.interfaceScale))}",
            { openSheet(SheetKey.of(EssentialSheet.CARLINK_SIZE)) },
            description = stringResource(R.string.carlink_settings_carlink_size_detail), icon = R.drawable.carlink_ic_text_fields)
        GroupDivider()
        SwitchRow(stringResource(R.string.carlink_settings_floating_switcher), ui.connection.floatingSwitcher,
            actions::setFloatingSwitcher, description = stringResource(R.string.carlink_settings_floating_switcher_detail),
            icon = R.drawable.carlink_ic_layers)
        if (ui.connection.floatingSwitcher && !ui.overlayAllowed) {
            GroupDivider()
            LinkRow(stringResource(R.string.carlink_settings_floating_switcher_permission), actions::openOverlayPermission,
                stringResource(R.string.carlink_settings_floating_switcher_permission_detail), R.drawable.carlink_ic_lock,
                external = true)
        }
    }
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_settings_group_help)) }) {
        LinkRow(stringResource(R.string.carlink_settings_diagnostics), { actions.navigate(Route.DIAGNOSTICS) },
            stringResource(R.string.carlink_settings_diagnostics_detail), R.drawable.carlink_ic_monitor_heart)
    }
    GlassGroup(header = { GroupHeader(stringResource(R.string.carlink_settings_group_about)) }) {
        val remaining = LAB_TAPS - versionTaps
        val versionHint = when {
            ui.unified.labUnlocked -> null
            versionTaps >= 2 -> pluralStringResource(R.plurals.carlink_settings_lab_taps, remaining, remaining)
            else -> null
        }
        SettingsRow(stringResource(R.string.carlink_settings_version), description = versionHint, icon = R.drawable.carlink_ic_info,
            onClick = onVersionTap, value = ui.versionName)
        if (ui.unified.labUnlocked) {
            GroupDivider()
            SwitchRow(stringResource(R.string.carlink_settings_lab_toggle), true, { on -> actions.update { it.copy(labUnlocked = on) } },
                description = stringResource(R.string.carlink_settings_lab_toggle_detail), icon = R.drawable.carlink_ic_science)
        }
        GroupDivider()
        LinkRow(stringResource(R.string.carlink_settings_about_open), { actions.navigate(Route.ABOUT) },
            stringResource(R.string.carlink_settings_about_summary), R.drawable.carlink_ic_gavel)
    }
}

// ---- Advanced and Lab ----------------------------------------------------------------------

/** The category's engine settings of one tier, one glass group per engine. */
@Composable
private fun EngineSection(category: SettingsCategory, tier: Tier, ui: SettingsUi, actions: SettingsActions, openSheet: (String) -> Unit) {
    val groups = engineRows(category, tier)
    if (groups.isEmpty()) return
    val colors = CarLinkTokens.colors
    val lab = tier == Tier.LAB
    val accent = if (lab) colors.warning else colors.onSurfaceTertiary
    Column(Modifier.padding(top = CarLinkTokens.dimens.gutter / 2, start = 8.dp, end = 8.dp)) {
        GroupHeader(stringResource(if (lab) R.string.carlink_settings_lab else R.string.carlink_settings_advanced), color = accent,
            icon = if (lab) R.drawable.carlink_ic_science_filled else R.drawable.carlink_ic_tune)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(if (lab) R.string.carlink_settings_lab_detail else R.string.carlink_settings_advanced_detail),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    groups.forEach { (engine, rows) ->
        GlassGroup(frame = if (lab) colors.warning else null, header = { EngineHeader(engine, if (lab) colors.warning else null) }) {
            rows.forEachIndexed { index, setting ->
                if (index > 0) GroupDivider()
                EngineSettingRow(setting, ui.advancedValues, actions, openSheet)
            }
        }
    }
}

@Composable
private fun EngineSettingRow(setting: AdvancedSetting, values: Map<String, String>?, actions: SettingsActions, openSheet: (String) -> Unit) {
    val value = values?.get(setting.id)
    val loaded = value != null
    val tags: (@Composable () -> Unit)? = if (setting.applies == Applies.NEXT_CONNECTION) ({ NextConnectionTag() }) else null
    when (setting.control) {
        is Control.Toggle -> SwitchRow(setting.title, value == "true", { on -> actions.setAdvanced(setting, on.toString()) },
            description = setting.description, enabled = loaded, tags = tags)
        is Control.Choice -> ChoiceRow(setting.title,
            advancedValueLabel(setting, value) ?: stringResource(R.string.carlink_settings_loading),
            { openSheet(SheetKey.of(setting)) }, description = setting.description, enabled = loaded, tags = tags)
    }
}

// ---- Icons ----------------------------------------------------------------------------------

internal fun methodIcon(method: ConnectionMethod): Int = when (method) {
    ConnectionMethod.AUTOMATIC -> R.drawable.carlink_ic_sync
    ConnectionMethod.SAME_WIFI -> R.drawable.carlink_ic_wifi
    ConnectionMethod.CAR_HOTSPOT -> R.drawable.carlink_ic_wifi_tethering
    ConnectionMethod.WIFI_DIRECT -> R.drawable.carlink_ic_p2p
}

internal fun dayNightIcon(mode: DayNight): Int = when (mode) {
    DayNight.AUTOMATIC -> R.drawable.carlink_ic_brightness_auto
    DayNight.DAY -> R.drawable.carlink_ic_light_mode
    DayNight.NIGHT -> R.drawable.carlink_ic_dark_mode
    DayNight.SCHEDULE -> R.drawable.carlink_ic_schedule
}

internal fun themeIcon(mode: com.carlink.app.ui.theme.ThemeMode): Int = when (mode) {
    com.carlink.app.ui.theme.ThemeMode.FOLLOW_SYSTEM -> R.drawable.carlink_ic_brightness_auto
    com.carlink.app.ui.theme.ThemeMode.DARK -> R.drawable.carlink_ic_dark_mode
    com.carlink.app.ui.theme.ThemeMode.LIGHT -> R.drawable.carlink_ic_light_mode
}

internal fun layoutIcon(mode: com.carlink.app.ui.theme.UiProfileMode): Int = when (mode) {
    com.carlink.app.ui.theme.UiProfileMode.AUTO -> R.drawable.carlink_ic_devices
    com.carlink.app.ui.theme.UiProfileMode.CAR -> R.drawable.carlink_ic_directions_car
    com.carlink.app.ui.theme.UiProfileMode.HANDHELD -> R.drawable.carlink_ic_tablet_android
}
