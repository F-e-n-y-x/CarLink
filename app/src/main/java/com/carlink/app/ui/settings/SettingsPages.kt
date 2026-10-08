package com.carlink.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.carlink.app.BuildConfig
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.detect.BluetoothAvailability
import com.carlink.app.ui.Route
import com.carlink.app.ui.components.ChoiceRow
import com.carlink.app.ui.components.NavigationRow
import com.carlink.app.ui.components.NoticeCard
import com.carlink.app.ui.components.QuickChoiceTile
import com.carlink.app.ui.components.QuickToggleTile
import com.carlink.app.ui.components.RowDivider
import com.carlink.app.ui.components.SecondaryButton
import com.carlink.app.ui.components.SectionCard
import com.carlink.app.ui.components.SectionHeader
import com.carlink.app.ui.components.ToggleRow
import com.carlink.app.ui.components.engineName
import com.carlink.app.ui.components.focusRingRow
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.UiProfile

/** What every page needs: state, intents and the in-Settings navigation callbacks. */
internal class SettingsPageScope(
    val state: SettingsUiState,
    val actions: SettingsActions,
    val navigate: (Route) -> Unit,
    val openChoice: (SettingsChoice) -> Unit,
    val openCategory: (SettingsCategory) -> Unit,
)

/** The body of one category, below its header. */
@Composable
internal fun SettingsPageScope.CategoryPage(category: SettingsCategory, showCategoryLinks: Boolean,
                                            linkFocus: Map<SettingsCategory, FocusRequester> = emptyMap()) {
    when (category) {
        SettingsCategory.OVERVIEW -> OverviewPage(showCategoryLinks, linkFocus)
        SettingsCategory.CONNECTION -> ConnectionPage()
        SettingsCategory.DISPLAY -> DisplayPage()
        SettingsCategory.AUDIO -> EngineLinksPage(R.string.carlink_settings_carplay_audio, R.string.carlink_settings_android_auto_audio)
        SettingsCategory.NAVIGATION ->
            EngineLinksPage(R.string.carlink_settings_carplay_navigation, R.string.carlink_settings_android_auto_navigation)
        SettingsCategory.VEHICLE -> EngineLinksPage(R.string.carlink_settings_carplay_vehicle, R.string.carlink_settings_android_auto_vehicle)
        SettingsCategory.DIAGNOSTICS -> SectionCard {
            NavigationRow(stringResource(R.string.carlink_settings_open_diagnostics), { navigate(Route.DIAGNOSTICS) },
                Modifier.focusRingRow(), supporting = stringResource(R.string.carlink_settings_open_diagnostics_detail),
                icon = R.drawable.carlink_ic_monitor_heart)
        }
        SettingsCategory.ABOUT -> SectionCard {
            NavigationRow(stringResource(R.string.carlink_settings_open_about), { navigate(Route.ABOUT) },
                Modifier.focusRingRow(),
                supporting = stringResource(R.string.carlink_settings_summary_about, BuildConfig.VERSION_NAME),
                icon = R.drawable.carlink_ic_info)
        }
    }
}

// ---- Overview ----------------------------------------------------------------------------

@Composable
private fun SettingsPageScope.OverviewPage(showCategoryLinks: Boolean, linkFocus: Map<SettingsCategory, FocusRequester>) {
    val dimens = CarLinkTokens.dimens
    Column(verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
        ReadinessCard()
        SectionHeader(stringResource(R.string.carlink_settings_quick_settings))
        QuickSettings()
        if (showCategoryLinks) {
            SectionHeader(stringResource(R.string.carlink_settings_all_settings))
            SectionCard {
                val links = SettingsCategory.entries.filter { it != SettingsCategory.OVERVIEW }
                links.forEachIndexed { index, category ->
                    if (index > 0) RowDivider()
                    val focus = linkFocus[category]
                    NavigationRow(
                        title = stringResource(category.title),
                        onClick = { openCategory(category) },
                        modifier = (if (focus != null) Modifier.focusRequester(focus) else Modifier).focusRingRow(),
                        supporting = categorySummary(category),
                        icon = category.icon,
                    )
                }
            }
        }
    }
}

/** Connection readiness (DESIGN_SPEC §6.3.4): what works now, or the first thing to fix. */
@Composable
private fun SettingsPageScope.ReadinessCard() {
    val colors = MaterialTheme.colorScheme
    val ext = CarLinkTokens.colors
    val home = state.home
    val connection = state.connection
    val action = @Composable { label: String, onClick: () -> Unit -> SecondaryButton(label, onClick) }
    when {
        home.link.stage == LinkStage.CONNECTED && home.link.engine != null -> NoticeCard(
            title = stringResource(R.string.carlink_settings_ready_live_title, engineName(home.link.engine)),
            body = home.link.phoneName?.let { stringResource(R.string.carlink_settings_ready_live_body, isolate(it)) }
                ?: stringResource(R.string.carlink_settings_ready_live_body_unnamed),
            icon = R.drawable.carlink_ic_check_circle_filled, iconTint = ext.success, liveRegion = true,
        )
        home.bluetooth == BluetoothAvailability.OFF -> NoticeCard(
            title = stringResource(R.string.carlink_settings_ready_bluetooth_off_title),
            body = stringResource(R.string.carlink_settings_ready_bluetooth_off_body),
            icon = R.drawable.carlink_ic_bluetooth_disabled, iconTint = ext.warning, liveRegion = true,
        ) { action(stringResource(R.string.carlink_settings_bluetooth_settings), actions::openBluetoothSettings) }
        home.bluetooth == BluetoothAvailability.NO_PERMISSION -> NoticeCard(
            title = stringResource(R.string.carlink_settings_ready_permission_title),
            body = stringResource(R.string.carlink_settings_ready_permission_body),
            icon = R.drawable.carlink_ic_warning_filled, iconTint = ext.warning, liveRegion = true,
        ) { action(stringResource(R.string.carlink_settings_app_permissions), actions::openAppSettings) }
        connection.autoConnectPaused -> PausedNotice()
        home.bluetooth == BluetoothAvailability.UNSUPPORTED -> NoticeCard(
            title = stringResource(R.string.carlink_settings_ready_no_bluetooth_title),
            body = stringResource(R.string.carlink_settings_ready_no_bluetooth_body),
            icon = R.drawable.carlink_ic_usb, iconTint = colors.onSurfaceVariant, liveRegion = true,
        )
        home.phones.isEmpty() -> NoticeCard(
            title = stringResource(R.string.carlink_settings_ready_no_phones_title),
            body = stringResource(R.string.carlink_settings_ready_no_phones_body),
            icon = R.drawable.carlink_ic_bluetooth, iconTint = colors.onSurfaceVariant, liveRegion = true,
        ) { action(stringResource(R.string.carlink_settings_bluetooth_settings), actions::openBluetoothSettings) }
        else -> {
            val phones = pluralStringResource(R.plurals.carlink_settings_ready_phones, home.phones.size, home.phones.size)
            val how = stringResource(
                when {
                    connection.autoConnectBluetooth && connection.autoConnectUsb -> R.string.carlink_settings_ready_auto_both
                    connection.autoConnectBluetooth -> R.string.carlink_settings_ready_auto_bluetooth
                    connection.autoConnectUsb -> R.string.carlink_settings_ready_auto_usb
                    else -> R.string.carlink_settings_ready_auto_off
                },
            )
            NoticeCard(
                title = stringResource(R.string.carlink_settings_ready_title),
                body = stringResource(R.string.carlink_settings_ready_body, phones, how),
                icon = R.drawable.carlink_ic_check_circle_filled, iconTint = ext.success, liveRegion = true,
            )
        }
    }
}

/** Shown while the driver's last disconnect keeps automatic starts off. */
@Composable
private fun SettingsPageScope.PausedNotice() {
    val ext = CarLinkTokens.colors
    NoticeCard(
        title = stringResource(R.string.carlink_settings_paused_title),
        body = stringResource(R.string.carlink_settings_paused_body),
        icon = R.drawable.carlink_ic_warning_filled,
        iconTint = ext.onWarningContainer,
        containerColor = ext.warningContainer,
        contentColor = ext.onWarningContainer,
        bodyColor = ext.onWarningContainer,
        liveRegion = true,
    ) {
        SecondaryButton(stringResource(R.string.carlink_settings_resume), actions::resumeAutoConnect,
            icon = R.drawable.carlink_ic_play_arrow)
    }
}

/** At most four quick settings (DESIGN_SPEC §4.4), 2×2 when there is room, else stacked. */
@Composable
private fun SettingsPageScope.QuickSettings() {
    val dimens = CarLinkTokens.dimens
    val display = state.display
    val tiles: List<@Composable (Modifier) -> Unit> = listOf(
        { m ->
            QuickChoiceTile(stringResource(R.string.carlink_settings_theme), themeLabel(display.themeMode),
                themeIcon(display.themeMode), { openChoice(SettingsChoice.THEME) }, m)
        },
        { m ->
            QuickToggleTile(stringResource(R.string.carlink_settings_quick_auto_connect),
                stringResource(R.string.carlink_settings_quick_auto_connect_detail), R.drawable.carlink_ic_bluetooth,
                state.connection.autoConnectBluetooth, actions::setAutoConnectBluetooth, m)
        },
        { m ->
            QuickChoiceTile(stringResource(R.string.carlink_settings_interface_size),
                interfaceSizeValue(display.interfaceScale), R.drawable.carlink_ic_text_fields,
                { openChoice(SettingsChoice.INTERFACE_SIZE) }, m)
        },
        { m ->
            QuickChoiceTile(stringResource(R.string.carlink_settings_layout), layoutValue(display.uiProfileMode),
                layoutIcon(display.uiProfileMode), { openChoice(SettingsChoice.LAYOUT) }, m)
        },
    )
    BoxWithConstraints {
        val twoColumns = maxWidth >= if (CarLinkTokens.profile == UiProfile.CAR) 800.dp else 480.dp
        Column(verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
            if (twoColumns) {
                tiles.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
                        pair.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            } else {
                tiles.forEach { tile -> tile(Modifier.fillMaxWidth()) }
            }
        }
    }
}

/** The single-pane list's one-line summary of each category's key values (DESIGN_SPEC §6.3.3). */
@Composable
private fun SettingsPageScope.categorySummary(category: SettingsCategory): String = when (category) {
    SettingsCategory.OVERVIEW -> stringResource(category.description)
    SettingsCategory.CONNECTION -> {
        val c = state.connection
        stringResource(
            when {
                c.autoConnectPaused && (c.autoConnectBluetooth || c.autoConnectUsb) -> R.string.carlink_settings_summary_auto_paused
                c.autoConnectBluetooth && c.autoConnectUsb -> R.string.carlink_settings_summary_auto_both
                c.autoConnectBluetooth -> R.string.carlink_settings_summary_auto_bluetooth
                c.autoConnectUsb -> R.string.carlink_settings_summary_auto_usb
                else -> R.string.carlink_settings_summary_auto_off
            },
        )
    }
    SettingsCategory.DISPLAY -> stringResource(R.string.carlink_settings_summary_display,
        themeLabel(state.display.themeMode), percent(nearestScale(state.display.interfaceScale)))
    SettingsCategory.AUDIO, SettingsCategory.NAVIGATION, SettingsCategory.VEHICLE ->
        stringResource(R.string.carlink_settings_summary_engines)
    SettingsCategory.DIAGNOSTICS -> stringResource(R.string.carlink_settings_summary_diagnostics)
    SettingsCategory.ABOUT -> stringResource(R.string.carlink_settings_summary_about, BuildConfig.VERSION_NAME)
}

// ---- Connection --------------------------------------------------------------------------

@Composable
private fun SettingsPageScope.ConnectionPage() {
    val dimens = CarLinkTokens.dimens
    val c = state.connection
    Column(verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
        if (c.autoConnectPaused) {
            Spacer(Modifier.height(4.dp))
            PausedNotice()
        }
        SectionHeader(stringResource(R.string.carlink_settings_section_all_phones))
        SectionCard {
            ToggleRow(stringResource(R.string.carlink_settings_auto_bluetooth), c.autoConnectBluetooth,
                actions::setAutoConnectBluetooth, Modifier.focusRingRow(),
                supporting = stringResource(R.string.carlink_settings_auto_bluetooth_detail))
            RowDivider()
            ToggleRow(stringResource(R.string.carlink_settings_auto_usb), c.autoConnectUsb,
                actions::setAutoConnectUsb, Modifier.focusRingRow(),
                supporting = stringResource(R.string.carlink_settings_auto_usb_detail))
            RowDivider()
            ToggleRow(stringResource(R.string.carlink_settings_start_on_boot), c.startOnBoot,
                actions::setStartOnBoot, Modifier.focusRingRow(),
                supporting = stringResource(R.string.carlink_settings_start_on_boot_detail))
        }
        SectionCard {
            NavigationRow(stringResource(R.string.carlink_settings_run_setup), { navigate(Route.ONBOARDING) },
                Modifier.focusRingRow(), supporting = stringResource(R.string.carlink_settings_run_setup_detail))
            RowDivider()
            NavigationRow(stringResource(R.string.carlink_settings_bluetooth_settings), actions::openBluetoothSettings,
                Modifier.focusRingRow(), supporting = stringResource(R.string.carlink_settings_bluetooth_settings_detail),
                external = true)
            RowDivider()
            NavigationRow(stringResource(R.string.carlink_settings_app_permissions), actions::openAppSettings,
                Modifier.focusRingRow(), supporting = stringResource(R.string.carlink_settings_app_permissions_detail),
                external = true)
        }
        EngineSections(R.string.carlink_settings_carplay_connection, R.string.carlink_settings_android_auto_connection)
    }
}

// ---- Display -----------------------------------------------------------------------------

@Composable
private fun SettingsPageScope.DisplayPage() {
    val dimens = CarLinkTokens.dimens
    val display = state.display
    Column(verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
        SectionHeader(stringResource(R.string.carlink_settings_section_all_phones))
        SectionCard {
            ChoiceRow(stringResource(R.string.carlink_settings_theme), themeLabel(display.themeMode),
                { openChoice(SettingsChoice.THEME) }, Modifier.focusRingRow())
            RowDivider()
            ChoiceRow(stringResource(R.string.carlink_settings_interface_size), interfaceSizeValue(display.interfaceScale),
                { openChoice(SettingsChoice.INTERFACE_SIZE) }, Modifier.focusRingRow())
            RowDivider()
            ChoiceRow(stringResource(R.string.carlink_settings_layout), layoutValue(display.uiProfileMode),
                { openChoice(SettingsChoice.LAYOUT) }, Modifier.focusRingRow())
        }
        EngineSections(R.string.carlink_settings_carplay_display, R.string.carlink_settings_android_auto_display)
    }
}

// ---- Audio, Navigation & HUD, Vehicle ------------------------------------------------------

/** Categories whose settings still live in the engines: a note, then one link per engine. */
@Composable
private fun SettingsPageScope.EngineLinksPage(@StringRes carPlay: Int, @StringRes androidAuto: Int) {
    val dimens = CarLinkTokens.dimens
    Column(verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
        Spacer(Modifier.height(4.dp))
        NoticeCard(
            title = stringResource(R.string.carlink_settings_engine_notice),
            body = null,
            icon = R.drawable.carlink_ic_info,
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EngineSections(carPlay, androidAuto)
    }
}

/** One section per engine, the current or last phone's engine first (DESIGN_SPEC §6.3.1). */
@Composable
private fun SettingsPageScope.EngineSections(@StringRes carPlay: Int, @StringRes androidAuto: Int) {
    state.engineOrder.forEach { engine ->
        val isCarPlay = engine == Engine.CARPLAY
        Column {
            SectionHeader(
                title = engineName(engine),
                subtitle = stringResource(if (isCarPlay) R.string.carlink_settings_applies_iphone
                else R.string.carlink_settings_applies_android),
                engine = engine,
                modifier = Modifier.padding(top = 8.dp),
            )
            SectionCard {
                NavigationRow(
                    title = stringResource(if (isCarPlay) carPlay else androidAuto),
                    onClick = { actions.openEngineSettings(engine) },
                    modifier = Modifier.focusRingRow(),
                    supporting = stringResource(if (isCarPlay) R.string.carlink_settings_opens_carplay
                    else R.string.carlink_settings_opens_android_auto),
                    external = true,
                )
            }
        }
    }
}

/** Unicode first-strong isolate around user data such as phone names (DESIGN_SPEC §7.3). */
internal fun isolate(text: String): String = "⁨$text⁩"
