package com.carlink.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.carlink.app.settings.AdvancedSettings
import com.carlink.app.settings.ConnectionMethod
import com.carlink.app.settings.Control
import com.carlink.app.settings.DayNight
import com.carlink.app.settings.Picture
import com.carlink.app.settings.SettingsCategory
import com.carlink.app.settings.UnifiedSettings
import com.carlink.app.settings.WifiDetails
import com.carlink.app.ui.ConnectionPrefs
import com.carlink.app.ui.DisplayPrefs
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfile
import com.carlink.app.ui.theme.UiProfileMode

/** Fake engine values: plausible defaults for every Advanced and Lab row. */
internal val previewEngineValues: Map<String, String> = AdvancedSettings.all.associate { setting ->
    setting.id to when (val control = setting.control) {
        is Control.Toggle -> (setting.id in setOf("cp_hevc", "cp_focus", "cp_echo", "aa_sink", "aa_stretch")).toString()
        is Control.Choice -> control.options.getOrElse(if (control.options.size > 2) 2 else 0) { control.options.first() }.value
    }
}

/** Settings state for previews and the screenshot gallery. */
fun settingsPreviewUi(
    showAdvanced: Boolean = false,
    lab: Boolean = false,
    paused: Boolean = false,
    schedule: Boolean = false,
    customPicture: Boolean = false,
    byd: Boolean = true,
    loading: Boolean = false,
): SettingsUi = SettingsUi(
    unified = UnifiedSettings(
        connectionMethod = ConnectionMethod.AUTOMATIC,
        carHotspot = WifiDetails("BYD-Seal-4F2A", "drive2024go"),
        homeWifi = WifiDetails(),
        picture = if (customPicture) Picture.CUSTOM else Picture.AUTOMATIC,
        dayNight = if (schedule) DayNight.SCHEDULE else DayNight.AUTOMATIC,
        showAdvanced = showAdvanced,
        labUnlocked = lab,
    ),
    connection = ConnectionPrefs(autoConnectUsb = true, autoConnectBluetooth = true, startOnBoot = false, autoConnectPaused = paused),
    display = DisplayPrefs(ThemeMode.FOLLOW_SYSTEM, UiProfileMode.AUTO, 1f),
    joinedWifi = "Garage Wi-Fi",
    isByd = byd,
    vehicle = if (byd) com.carlink.app.settings.VehicleProfile.BYD_DILINK else com.carlink.app.settings.VehicleProfile.GENERIC,
    detectedVehicle = com.carlink.app.settings.VehicleProfile.GENERIC,
    versionName = "1.0.0",
    advancedValues = if (loading) null else previewEngineValues,
)

/** Does nothing; previews and the gallery only render. */
object SettingsPreviewActions : SettingsActions

@Preview(name = "Car landscape", widthDp = 1440, heightDp = 810)
@Composable
private fun SettingsCarPreview() {
    CarLinkTheme(profile = UiProfile.CAR, themeMode = ThemeMode.DARK) {
        SettingsContent(settingsPreviewUi(), SettingsPreviewActions, onBack = {}, startCategory = SettingsCategory.CONNECTION)
    }
}

@Preview(name = "Car landscape, advanced", widthDp = 1440, heightDp = 810)
@Composable
private fun SettingsAdvancedPreview() {
    CarLinkTheme(profile = UiProfile.CAR, themeMode = ThemeMode.DARK) {
        SettingsContent(settingsPreviewUi(showAdvanced = true, lab = true), SettingsPreviewActions, onBack = {},
            startCategory = SettingsCategory.DISPLAY)
    }
}

@Preview(name = "Phone portrait", widthDp = 400, heightDp = 860)
@Composable
private fun SettingsPhonePreview() {
    CarLinkTheme(profile = UiProfile.HANDHELD, themeMode = ThemeMode.DARK) {
        SettingsContent(settingsPreviewUi(), SettingsPreviewActions, onBack = {})
    }
}
