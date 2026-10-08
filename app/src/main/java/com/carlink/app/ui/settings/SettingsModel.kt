package com.carlink.app.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.settings.CarLinkPreferences
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.ConnectionPrefs
import com.carlink.app.ui.DisplayPrefs
import com.carlink.app.ui.HomeState
import com.carlink.app.ui.components.ChoiceOption
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfile
import com.carlink.app.ui.theme.UiProfileMode
import java.text.NumberFormat
import kotlin.math.abs

/**
 * Settings categories in rail order (DESIGN_SPEC §4.4). General and Advanced are not listed yet:
 * their settings still live in the engines' own screens.
 */
enum class SettingsCategory(
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int,
) {
    OVERVIEW(R.string.carlink_settings_category_overview, R.string.carlink_settings_description_overview,
        R.drawable.carlink_ic_dashboard, R.drawable.carlink_ic_dashboard_filled),
    CONNECTION(R.string.carlink_settings_category_connection, R.string.carlink_settings_description_connection,
        R.drawable.carlink_ic_link, R.drawable.carlink_ic_link_filled),
    DISPLAY(R.string.carlink_settings_category_display, R.string.carlink_settings_description_display,
        R.drawable.carlink_ic_display_settings, R.drawable.carlink_ic_display_settings_filled),
    AUDIO(R.string.carlink_settings_category_audio, R.string.carlink_settings_description_audio,
        R.drawable.carlink_ic_volume_up, R.drawable.carlink_ic_volume_up_filled),
    NAVIGATION(R.string.carlink_settings_category_navigation, R.string.carlink_settings_description_navigation,
        R.drawable.carlink_ic_navigation, R.drawable.carlink_ic_navigation_filled),
    VEHICLE(R.string.carlink_settings_category_vehicle, R.string.carlink_settings_description_vehicle,
        R.drawable.carlink_ic_directions_car, R.drawable.carlink_ic_directions_car_filled),
    DIAGNOSTICS(R.string.carlink_settings_category_diagnostics, R.string.carlink_settings_description_diagnostics,
        R.drawable.carlink_ic_monitor_heart, R.drawable.carlink_ic_monitor_heart_filled),
    ABOUT(R.string.carlink_settings_category_about, R.string.carlink_settings_description_about,
        R.drawable.carlink_ic_info, R.drawable.carlink_ic_info_filled),
}

/** The choices that open a sheet. */
enum class SettingsChoice { THEME, LAYOUT, INTERFACE_SIZE }

/** Everything the Settings screens render, read from [CarLinkViewModel]. */
@Immutable
data class SettingsUiState(
    val display: DisplayPrefs,
    val connection: ConnectionPrefs,
    val home: HomeState,
) {
    /** The engine of the current or most recent phone goes first (DESIGN_SPEC §6.3.1). */
    val engineOrder: List<Engine>
        get() {
            val first = home.link.engine ?: home.defaultPhone?.kind?.engine ?: Engine.CARPLAY
            return listOf(first) + Engine.entries.filter { it != first }
        }
}

/** The intents Settings may raise; implemented by the ViewModel, faked in previews. */
interface SettingsActions {
    fun setThemeMode(mode: ThemeMode) {}
    fun setUiProfileMode(mode: UiProfileMode) {}
    fun setInterfaceScale(scale: Float) {}
    fun setAutoConnectBluetooth(on: Boolean) {}
    fun setAutoConnectUsb(on: Boolean) {}
    fun setStartOnBoot(on: Boolean) {}
    fun resumeAutoConnect() {}
    fun openBluetoothSettings() {}
    fun openAppSettings() {}
    fun openEngineSettings(engine: Engine) {}
}

internal class ViewModelSettingsActions(private val vm: CarLinkViewModel) : SettingsActions {
    override fun setThemeMode(mode: ThemeMode) = vm.setThemeMode(mode)
    override fun setUiProfileMode(mode: UiProfileMode) = vm.setUiProfileMode(mode)
    override fun setInterfaceScale(scale: Float) = vm.setInterfaceScale(scale)
    override fun setAutoConnectBluetooth(on: Boolean) = vm.setAutoConnectBluetooth(on)
    override fun setAutoConnectUsb(on: Boolean) = vm.setAutoConnectUsb(on)
    override fun setStartOnBoot(on: Boolean) = vm.setStartOnBoot(on)
    override fun resumeAutoConnect() = vm.resumeAutoConnect()
    override fun openBluetoothSettings() = vm.openBluetoothSettings()
    override fun openAppSettings() = vm.openAppSettings()
    override fun openEngineSettings(engine: Engine) = when (engine) {
        Engine.CARPLAY -> vm.openCarPlaySettings()
        Engine.ANDROID_AUTO -> vm.openAndroidAutoSettings()
    }
}

// ---- Labels and options ------------------------------------------------------------------

@Composable
internal fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.FOLLOW_SYSTEM -> if (CarLinkTokens.profile == UiProfile.CAR) R.string.carlink_settings_theme_follow_car
        else R.string.carlink_settings_theme_follow_system
        ThemeMode.DARK -> R.string.carlink_settings_theme_dark
        ThemeMode.LIGHT -> R.string.carlink_settings_theme_light
    },
)

@DrawableRes
internal fun themeIcon(mode: ThemeMode): Int = when (mode) {
    ThemeMode.FOLLOW_SYSTEM -> R.drawable.carlink_ic_brightness_auto
    ThemeMode.DARK -> R.drawable.carlink_ic_dark_mode
    ThemeMode.LIGHT -> R.drawable.carlink_ic_light_mode
}

@Composable
internal fun themeOptions(): List<ChoiceOption<ThemeMode>> {
    val car = CarLinkTokens.profile == UiProfile.CAR
    return listOf(
        ChoiceOption(ThemeMode.FOLLOW_SYSTEM, themeLabel(ThemeMode.FOLLOW_SYSTEM),
            stringResource(if (car) R.string.carlink_settings_theme_follow_car_detail
            else R.string.carlink_settings_theme_follow_system_detail),
            themeIcon(ThemeMode.FOLLOW_SYSTEM)),
        ChoiceOption(ThemeMode.DARK, themeLabel(ThemeMode.DARK),
            stringResource(R.string.carlink_settings_theme_dark_detail), themeIcon(ThemeMode.DARK)),
        ChoiceOption(ThemeMode.LIGHT, themeLabel(ThemeMode.LIGHT),
            stringResource(R.string.carlink_settings_theme_light_detail), themeIcon(ThemeMode.LIGHT)),
    )
}

/** The Layout row's value; "Automatic" also says what was detected. */
@Composable
internal fun layoutValue(mode: UiProfileMode): String = stringResource(
    when (mode) {
        UiProfileMode.AUTO -> if (CarLinkTokens.profile == UiProfile.CAR) R.string.carlink_settings_layout_auto_value_car
        else R.string.carlink_settings_layout_auto_value_handheld
        UiProfileMode.CAR -> R.string.carlink_settings_layout_car
        UiProfileMode.HANDHELD -> R.string.carlink_settings_layout_handheld
    },
)

@DrawableRes
internal fun layoutIcon(mode: UiProfileMode): Int = when (mode) {
    UiProfileMode.AUTO -> R.drawable.carlink_ic_devices
    UiProfileMode.CAR -> R.drawable.carlink_ic_directions_car
    UiProfileMode.HANDHELD -> R.drawable.carlink_ic_smartphone
}

@Composable
internal fun layoutOptions(): List<ChoiceOption<UiProfileMode>> = listOf(
    ChoiceOption(UiProfileMode.AUTO, stringResource(R.string.carlink_settings_layout_auto),
        stringResource(R.string.carlink_settings_layout_auto_detail), layoutIcon(UiProfileMode.AUTO)),
    ChoiceOption(UiProfileMode.CAR, stringResource(R.string.carlink_settings_layout_car),
        stringResource(R.string.carlink_settings_layout_car_detail), layoutIcon(UiProfileMode.CAR)),
    ChoiceOption(UiProfileMode.HANDHELD, stringResource(R.string.carlink_settings_layout_handheld),
        stringResource(R.string.carlink_settings_layout_handheld_detail), layoutIcon(UiProfileMode.HANDHELD)),
)

private val SCALE_NAMES = listOf(
    R.string.carlink_settings_size_small,
    R.string.carlink_settings_size_default,
    R.string.carlink_settings_size_large,
    R.string.carlink_settings_size_extra_large,
)

/** The stored scale snapped to the nearest preset, so a float round-trip never loses the selection. */
internal fun nearestScale(scale: Float): Float = CarLinkPreferences.INTERFACE_SCALES.minBy { abs(it - scale) }

@Composable
internal fun percent(scale: Float): String {
    val locale = LocalConfiguration.current.locales[0]
    return NumberFormat.getPercentInstance(locale).format(scale.toDouble())
}

@Composable
private fun scaleName(scale: Float): String =
    stringResource(SCALE_NAMES.getOrElse(CarLinkPreferences.INTERFACE_SCALES.indexOf(nearestScale(scale))) {
        R.string.carlink_settings_size_default
    })

@Composable
internal fun interfaceSizeValue(scale: Float): String =
    stringResource(R.string.carlink_settings_size_value, scaleName(scale), percent(nearestScale(scale)))

@Composable
internal fun interfaceSizeOptions(): List<ChoiceOption<Float>> =
    CarLinkPreferences.INTERFACE_SCALES.map { scale -> ChoiceOption(scale, scaleName(scale), percent(scale)) }
