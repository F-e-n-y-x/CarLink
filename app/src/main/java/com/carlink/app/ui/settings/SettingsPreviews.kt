package com.carlink.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.carlink.app.ui.ConnectionPrefs
import com.carlink.app.ui.DisplayPrefs
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfile
import com.carlink.app.ui.theme.UiProfileMode
import com.carlink.core.settings.AdvancedSettings
import com.carlink.core.settings.ConnectionMethod
import com.carlink.core.settings.Control
import com.carlink.core.settings.DayNight
import com.carlink.core.settings.Picture
import com.carlink.core.settings.SettingsCategory
import com.carlink.core.settings.UnifiedSettings
import com.carlink.core.settings.WifiDetails

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
    /** The chosen car brand; by default BYD when [byd], else none chosen. */
    carBrand: com.carlink.core.settings.CarBrand? = null,
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
    carBrand = carBrand ?: if (byd) com.carlink.core.settings.CarBrand.BYD else null,
    detectedBrand = null,
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
