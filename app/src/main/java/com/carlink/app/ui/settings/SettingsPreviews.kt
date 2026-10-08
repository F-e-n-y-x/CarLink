package com.carlink.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.PhoneKind
import com.carlink.app.core.Transport
import com.carlink.app.detect.BluetoothAvailability
import com.carlink.app.detect.PairedPhone
import com.carlink.app.ui.ConnectionPrefs
import com.carlink.app.ui.DisplayPrefs
import com.carlink.app.ui.HomeState
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfile
import com.carlink.app.ui.theme.UiProfileMode

private val previewPhones = listOf(
    PairedPhone("00:11:22:33:44:55", "Sara's iPhone", PhoneKind.IPHONE, connected = true),
    PairedPhone("66:77:88:99:AA:BB", "Pixel 9", PhoneKind.ANDROID, connected = false),
)

/** Fake state shared by the previews and the screenshot gallery. */
internal fun settingsPreviewState(
    live: Boolean = false,
    paused: Boolean = false,
    bluetooth: BluetoothAvailability = BluetoothAvailability.READY,
) = SettingsUiState(
    display = DisplayPrefs(ThemeMode.FOLLOW_SYSTEM, UiProfileMode.AUTO, 1f),
    connection = ConnectionPrefs(autoConnectUsb = true, autoConnectBluetooth = true, startOnBoot = false,
        autoConnectPaused = paused),
    home = HomeState(
        link = if (live) {
            LinkState(LinkStage.CONNECTED, Engine.CARPLAY, Transport.WIRELESS, "Sara's iPhone")
        } else {
            LinkState.Idle
        },
        phones = if (bluetooth == BluetoothAvailability.READY) previewPhones else emptyList(),
        bluetooth = bluetooth,
        defaultPhone = previewPhones.first(),
        connectedSince = null,
    ),
)

/** Intents that do nothing, for previews and the screenshot gallery. */
internal object SettingsPreviewActions : SettingsActions

@Composable
private fun Preview(
    profile: UiProfile,
    state: SettingsUiState = settingsPreviewState(),
    category: SettingsCategory = SettingsCategory.OVERVIEW,
    choice: SettingsChoice? = null,
    theme: ThemeMode = ThemeMode.DARK,
) {
    CarLinkTheme(profile = profile, themeMode = theme) {
        SettingsContent(state, SettingsPreviewActions, onBack = {}, navigate = {}, initialCategory = category, initialChoice = choice)
    }
}

@Preview(name = "Car landscape · Overview", device = "spec:width=1440dp,height=810dp,dpi=240")
@Composable
private fun CarOverviewPreview() = Preview(UiProfile.CAR, settingsPreviewState(live = true))

@Preview(name = "Car landscape · Display + side sheet", device = "spec:width=1440dp,height=810dp,dpi=240")
@Composable
private fun CarDisplaySheetPreview() = Preview(UiProfile.CAR, category = SettingsCategory.DISPLAY, choice = SettingsChoice.THEME)

@Preview(name = "Car landscape · Connection, light", device = "spec:width=1440dp,height=810dp,dpi=240")
@Composable
private fun CarConnectionLightPreview() =
    Preview(UiProfile.CAR, settingsPreviewState(paused = true), SettingsCategory.CONNECTION, theme = ThemeMode.LIGHT)

@Preview(name = "Car ⅓ split · Overview", device = "spec:width=480dp,height=810dp,dpi=240")
@Composable
private fun CarSplitPreview() = Preview(UiProfile.CAR, settingsPreviewState(live = true))

@Preview(name = "Phone portrait · Overview", device = "spec:width=400dp,height=860dp,dpi=420")
@Composable
private fun PhoneOverviewPreview() = Preview(UiProfile.HANDHELD, settingsPreviewState(bluetooth = BluetoothAvailability.OFF))

@Preview(name = "Phone portrait · Audio", device = "spec:width=400dp,height=860dp,dpi=420")
@Composable
private fun PhoneAudioPreview() = Preview(UiProfile.HANDHELD, category = SettingsCategory.AUDIO)

@Preview(name = "Phone landscape · Display", device = "spec:width=860dp,height=400dp,dpi=420")
@Composable
private fun PhoneLandscapePreview() = Preview(UiProfile.HANDHELD, category = SettingsCategory.DISPLAY)

@Preview(name = "Tablet portrait · Overview", device = "spec:width=800dp,height=1280dp,dpi=240")
@Composable
private fun TabletOverviewPreview() = Preview(UiProfile.HANDHELD, settingsPreviewState(paused = true), theme = ThemeMode.LIGHT)

@Preview(name = "Tablet landscape · Connection", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun TabletLandscapePreview() = Preview(UiProfile.HANDHELD, category = SettingsCategory.CONNECTION)
