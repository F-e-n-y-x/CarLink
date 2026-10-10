package com.carlink.app.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.core.settings.Applies
import com.carlink.core.settings.CarBrand
import com.carlink.core.settings.VehicleProfile
import com.carlink.core.settings.ConnectionMethod
import com.carlink.core.settings.Control
import com.carlink.core.settings.DayNight
import com.carlink.core.settings.InterfaceSize
import com.carlink.core.settings.Picture
import com.carlink.core.settings.WifiDetails
import com.carlink.core.settings.isValid
import com.carlink.app.ui.premium.GlassButton
import com.carlink.app.ui.premium.IonButton
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfileMode
import kotlin.math.max
import kotlin.math.min

/** One large tile in a choice sheet. [preview] replaces the glyph with a live miniature. */
internal class Preset<T>(
    val value: T,
    val title: String,
    val caption: String? = null,
    @param:DrawableRes val icon: Int? = null,
    val enabled: Boolean = true,
    val preview: (@Composable () -> Unit)? = null,
)

/** Opens the sheet named by [key] (see [SheetKey]); nothing when the key is null or unknown. */
@Composable
internal fun SettingsSheetHost(key: String?, ui: SettingsUi, actions: SettingsActions, onDismiss: () -> Unit) {
    SheetKey.advanced(key)?.let { setting ->
        val control = setting.control as? Control.Choice ?: return
        ChoiceSheet(setting.title, setting.description, setting.applies == Applies.NEXT_CONNECTION,
            control.options.map { Preset(it.value, it.label) }, ui.advancedValues?.get(setting.id),
            onSelect = { actions.setAdvanced(setting, it); onDismiss() }, onDismiss = onDismiss)
        return
    }
    val u = ui.unified
    val select: ((com.carlink.core.settings.UnifiedSettings) -> com.carlink.core.settings.UnifiedSettings) -> Unit =
        { change -> actions.update(change); onDismiss() }
    when (SheetKey.essential(key)) {
        null -> Unit
        EssentialSheet.CONNECTION_METHOD -> ChoiceSheet(
            stringResource(R.string.carlink_settings_method), stringResource(R.string.carlink_settings_method_sheet_body), true,
            ConnectionMethod.entries.map { m ->
                Preset(m, connectionMethodLabel(m), stringResource(when (m) {
                    ConnectionMethod.AUTOMATIC -> R.string.carlink_settings_method_auto_detail
                    ConnectionMethod.SAME_WIFI -> R.string.carlink_settings_method_same_wifi_detail
                    ConnectionMethod.CAR_HOTSPOT -> R.string.carlink_settings_method_hotspot_detail
                    ConnectionMethod.WIFI_DIRECT -> R.string.carlink_settings_method_direct_detail
                }), methodIcon(m))
            },
            u.connectionMethod, onSelect = { m -> select { it.copy(connectionMethod = m) } }, onDismiss = onDismiss,
        )
        EssentialSheet.CAR_HOTSPOT -> WifiSheet(
            stringResource(R.string.carlink_settings_hotspot), stringResource(R.string.carlink_settings_hotspot_sheet_body),
            u.carHotspot, suggestion = null, onSave = { w -> select { it.copy(carHotspot = w) } }, onDismiss = onDismiss,
        )
        EssentialSheet.SAME_WIFI -> WifiSheet(
            stringResource(R.string.carlink_settings_same_wifi), stringResource(R.string.carlink_settings_same_wifi_sheet_body),
            u.homeWifi, suggestion = ui.joinedWifi, onSave = { w -> select { it.copy(homeWifi = w) } }, onDismiss = onDismiss,
        )
        EssentialSheet.PICTURE -> ChoiceSheet(
            stringResource(R.string.carlink_settings_picture), stringResource(R.string.carlink_settings_picture_detail), true,
            listOf(
                Preset(Picture.AUTOMATIC, pictureLabel(Picture.AUTOMATIC), stringResource(R.string.carlink_settings_picture_auto_detail),
                    R.drawable.carlink_ic_brightness_auto),
                Preset(Picture.BEST, pictureLabel(Picture.BEST), stringResource(R.string.carlink_settings_picture_best_detail),
                    preview = { PicturePreview(4) }),
                Preset(Picture.BALANCED, pictureLabel(Picture.BALANCED), stringResource(R.string.carlink_settings_picture_balanced_detail),
                    preview = { PicturePreview(3) }),
                Preset(Picture.LIGHT, pictureLabel(Picture.LIGHT), stringResource(R.string.carlink_settings_picture_light_detail),
                    preview = { PicturePreview(2) }),
            ) + customPreset(u.picture == Picture.CUSTOM, Picture.CUSTOM),
            u.picture, onSelect = { p -> select { it.copy(picture = p) } }, onDismiss = onDismiss,
        )
        EssentialSheet.INTERFACE_SIZE -> ChoiceSheet(
            stringResource(R.string.carlink_settings_interface_size), stringResource(R.string.carlink_settings_interface_size_detail), true,
            listOf(
                Preset(InterfaceSize.SMALL, interfaceSizeLabel(InterfaceSize.SMALL), stringResource(R.string.carlink_settings_size_small_detail),
                    preview = { ScalePreview(0.8f) }),
                Preset(InterfaceSize.MEDIUM, interfaceSizeLabel(InterfaceSize.MEDIUM), stringResource(R.string.carlink_settings_size_medium_detail),
                    preview = { ScalePreview(1f) }),
                Preset(InterfaceSize.LARGE, interfaceSizeLabel(InterfaceSize.LARGE), stringResource(R.string.carlink_settings_size_large_detail),
                    preview = { ScalePreview(1.25f) }),
            ) + customPreset(u.interfaceSize == InterfaceSize.CUSTOM, InterfaceSize.CUSTOM),
            u.interfaceSize, onSelect = { s -> select { it.copy(interfaceSize = s) } }, onDismiss = onDismiss,
        )
        EssentialSheet.DAY_NIGHT -> ChoiceSheet(
            stringResource(R.string.carlink_settings_daynight), stringResource(R.string.carlink_settings_daynight_detail), false,
            DayNight.entries.map { m ->
                Preset(m, dayNightLabel(m), stringResource(when (m) {
                    DayNight.AUTOMATIC -> R.string.carlink_settings_daynight_auto_detail
                    DayNight.DAY -> R.string.carlink_settings_daynight_day_detail
                    DayNight.NIGHT -> R.string.carlink_settings_daynight_night_detail
                    DayNight.SCHEDULE -> R.string.carlink_settings_daynight_schedule_detail
                }), dayNightIcon(m))
            },
            u.dayNight, onSelect = { m -> select { it.copy(dayNight = m) } }, onDismiss = onDismiss,
        )
        EssentialSheet.NIGHT_START -> TimeSheet(stringResource(R.string.carlink_settings_night_start), u.nightStartMinute,
            presets = listOf(18, 19, 20, 21).map { it * 60 }, onChange = { m -> actions.update { it.copy(nightStartMinute = m) } },
            onDismiss = onDismiss)
        EssentialSheet.NIGHT_END -> TimeSheet(stringResource(R.string.carlink_settings_night_end), u.nightEndMinute,
            presets = listOf(5, 6, 7, 8).map { it * 60 }, onChange = { m -> actions.update { it.copy(nightEndMinute = m) } },
            onDismiss = onDismiss)
        EssentialSheet.DRIVING_SIDE -> ChoiceSheet(
            stringResource(R.string.carlink_settings_driving_side), stringResource(R.string.carlink_settings_driving_side_detail), true,
            listOf(
                Preset(false, stringResource(R.string.carlink_settings_drive_left), stringResource(R.string.carlink_settings_drive_left_detail),
                    preview = { DrivingSidePreview(right = false) }),
                Preset(true, stringResource(R.string.carlink_settings_drive_right), stringResource(R.string.carlink_settings_drive_right_detail),
                    preview = { DrivingSidePreview(right = true) }),
            ),
            u.rightHandDrive, onSelect = { r -> select { it.copy(rightHandDrive = r) } }, onDismiss = onDismiss,
        )
        EssentialSheet.SOUND -> ChoiceSheet(
            stringResource(R.string.carlink_settings_sound), stringResource(R.string.carlink_settings_sound_detail), true,
            listOf(
                Preset(false, stringResource(R.string.carlink_settings_sound_carlink), stringResource(R.string.carlink_settings_sound_carlink_detail),
                    R.drawable.carlink_ic_volume_up),
                Preset(true, stringResource(R.string.carlink_settings_sound_bluetooth), stringResource(R.string.carlink_settings_sound_bluetooth_detail),
                    R.drawable.carlink_ic_bluetooth),
            ),
            u.soundThroughCarBluetooth, onSelect = { b -> select { it.copy(soundThroughCarBluetooth = b) } }, onDismiss = onDismiss,
        )
        EssentialSheet.APPEARANCE -> ChoiceSheet(
            stringResource(R.string.carlink_settings_appearance), stringResource(R.string.carlink_settings_appearance_detail), false,
            ThemeMode.entries.map { m ->
                Preset(m, themeLabel(m), stringResource(when (m) {
                    ThemeMode.FOLLOW_SYSTEM -> R.string.carlink_settings_appearance_follow_detail
                    ThemeMode.DARK -> R.string.carlink_settings_appearance_dark_detail
                    ThemeMode.LIGHT -> R.string.carlink_settings_appearance_light_detail
                }), preview = { AppearancePreview(m) })
            },
            ui.display.themeMode, onSelect = { actions.setThemeMode(it); onDismiss() }, onDismiss = onDismiss,
        )
        EssentialSheet.LAYOUT -> ChoiceSheet(
            stringResource(R.string.carlink_settings_layout), stringResource(R.string.carlink_settings_layout_detail), false,
            UiProfileMode.entries.map { m ->
                Preset(m, layoutLabel(m), stringResource(when (m) {
                    UiProfileMode.AUTO -> R.string.carlink_settings_layout_auto_detail
                    UiProfileMode.CAR -> R.string.carlink_settings_layout_car_detail
                    UiProfileMode.HANDHELD -> R.string.carlink_settings_layout_handheld_detail
                }), layoutIcon(m))
            },
            ui.display.uiProfileMode, onSelect = { actions.setUiProfileMode(it); onDismiss() }, onDismiss = onDismiss,
        )
        EssentialSheet.CARLINK_SIZE -> ChoiceSheet(
            stringResource(R.string.carlink_settings_carlink_size), stringResource(R.string.carlink_settings_carlink_size_detail), false,
            carLinkScales.map { s -> Preset(s, carLinkScaleLabel(s), percentText(s), preview = { ScalePreview(s, carLink = true) }) },
            nearestScale(ui.display.interfaceScale), onSelect = { actions.setInterfaceScale(it); onDismiss() }, onDismiss = onDismiss,
        )
        EssentialSheet.RENDERING -> ChoiceSheet(
            stringResource(R.string.carlink_settings_rendering), stringResource(R.string.carlink_settings_rendering_detail), false,
            com.carlink.app.ui.theme.RenderingMode.entries.map { m ->
                Preset(m, renderingLabel(m), stringResource(when (m) {
                    com.carlink.app.ui.theme.RenderingMode.AUTO -> if (ui.display.autoLite) R.string.carlink_settings_rendering_auto_detail_lite
                        else R.string.carlink_settings_rendering_auto_detail_full
                    com.carlink.app.ui.theme.RenderingMode.FULL -> R.string.carlink_settings_rendering_full_detail
                    com.carlink.app.ui.theme.RenderingMode.LITE -> R.string.carlink_settings_rendering_lite_detail
                }), when (m) {
                    com.carlink.app.ui.theme.RenderingMode.AUTO -> R.drawable.carlink_ic_devices
                    com.carlink.app.ui.theme.RenderingMode.FULL -> R.drawable.carlink_ic_dashboard_filled
                    com.carlink.app.ui.theme.RenderingMode.LITE -> R.drawable.carlink_ic_dashboard
                })
            },
            ui.display.renderingMode, onSelect = { actions.setRenderingMode(it); onDismiss() }, onDismiss = onDismiss,
        )
        EssentialSheet.CAR_BRAND -> SettingsSheet(onDismiss) {
            SheetTitle(stringResource(R.string.carlink_settings_car_brand), stringResource(R.string.carlink_settings_car_brand_sheet_body),
                nextConnection = true)
            CarBrandPicker(ui.carBrand, ui.detectedBrand, onSelect = { actions.setCarBrand(it); onDismiss() })
        }
    }
}

/**
 * The car brand as a grid of large text tiles (no logos: they are trademarks): the makes popular
 * in India by sales, then luxury makes, then "Other / not listed". Below it, what the choice does
 * (only BYD changes anything) and what this screen was detected as. Settings › Car › Car brand and
 * the setup's car step. Both hosts scroll, so the grid is a plain column of rows, not a lazy grid.
 */
@Composable
internal fun CarBrandPicker(selected: CarBrand?, detected: CarBrand?, onSelect: (CarBrand) -> Unit, note: Boolean = true) {
    val dimens = CarLinkTokens.dimens
    val current = brandInEffect(selected, detected)
    val (luxury, popular) = CarBrand.entries.filter { it != CarBrand.OTHER }.partition { it.luxury }
    Column(Modifier.selectableGroup()) {
        com.carlink.app.ui.premium.Eyebrow(stringResource(R.string.carlink_settings_car_popular))
        Spacer(Modifier.height(dimens.targetGap))
        BrandGrid(popular, current, onSelect)
        Spacer(Modifier.height(dimens.gutter))
        com.carlink.app.ui.premium.Eyebrow(stringResource(R.string.carlink_settings_car_luxury))
        Spacer(Modifier.height(dimens.targetGap))
        BrandGrid(luxury, current, onSelect)
        Spacer(Modifier.height(dimens.targetGap * 2))
        BrandGrid(listOf(CarBrand.OTHER), current, onSelect)
    }
    if (note) {
        Spacer(Modifier.height(dimens.gutter))
        CarBrandNote(selected, detected)
    }
}

/** Never chosen: what is in effect is the detected BYD, or a generic car ("Other"). */
private fun brandInEffect(selected: CarBrand?, detected: CarBrand?) = selected ?: detected ?: CarBrand.OTHER

/**
 * What the car brand does, in plain words (only BYD changes anything), and what this screen was
 * detected as. Under the grid in the sheet; beside it, under the step's words, in the setup.
 */
@Composable
internal fun CarBrandNote(selected: CarBrand?, detected: CarBrand?, showChoice: Boolean = false) {
    val dimens = CarLinkTokens.dimens
    val scheme = MaterialTheme.colorScheme
    val byd = brandInEffect(selected, detected).profile == VehicleProfile.BYD_DILINK
    if (showChoice) {
        // The setup's grid may scroll the lit tile out of view; its name stays here.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.carlink_ic_check_circle_filled), null,
                Modifier.size(dimens.iconTertiary + 6.dp), tint = scheme.primary)
            Spacer(Modifier.width(if (isCar) 14.dp else 10.dp))
            Text(carBrandLabel(selected ?: detected), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
        }
        Spacer(Modifier.height(dimens.targetGap * 1.5f))
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(painterResource(if (byd) R.drawable.carlink_ic_info_filled else R.drawable.carlink_ic_info), null,
            Modifier.padding(top = 2.dp).size(dimens.iconTertiary + 4.dp), tint = if (byd) scheme.primary else scheme.onSurfaceVariant)
        Spacer(Modifier.width(if (isCar) 14.dp else 10.dp))
        Text(stringResource(if (byd) R.string.carlink_settings_car_explain_byd else R.string.carlink_settings_car_explain_other),
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(dimens.targetGap * 1.5f))
    Tag(detectedCarLabel(detected), scheme.onSurfaceVariant, R.drawable.carlink_ic_search)
}

/** Rows of equal brand tiles; 2 to 5 columns by width, so a ⅓ split still gets two. */
@Composable
private fun BrandGrid(brands: List<CarBrand>, current: CarBrand, onSelect: (CarBrand) -> Unit) {
    val gap = CarLinkTokens.dimens.targetGap
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minTile = if (isCar) 250.dp else 150.dp
        val columns = ((maxWidth + gap) / (minTile + gap)).toInt().coerceIn(2, 5)
        // Two forced columns in a ⅓ split are narrow: the car's smallest title size (24sp) keeps names whole.
        val narrow = (maxWidth - gap * (columns - 1)) / columns < minTile
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            brands.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { brand ->
                        BrandTile(brand, brand == current, narrow, { onSelect(brand) }, Modifier.weight(1f).fillMaxHeight())
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** One make as text on glass, at least 56dp (76dp in a car) tall; the chosen one is lit and ticked. */
@Composable
private fun BrandTile(brand: CarBrand, selected: Boolean, narrow: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val car = isCar
    SelectableGlass(selected, onClick, modifier.heightIn(min = if (car) 76.dp else 56.dp),
        shape = RoundedCornerShape(if (car) 24.dp else 18.dp), role = Role.RadioButton) {
        Row(Modifier.fillMaxSize().padding(horizontal = if (car) 18.dp else 14.dp, vertical = if (car) 12.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // "Mercedes-Benz" may wrap after its hyphen, never inside a word.
                Text(carBrandLabel(brand).replace("-", "-\u200B"),
                    style = if (narrow) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    color = if (selected) scheme.primary else scheme.onSurface)
                if (brand.profile == VehicleProfile.BYD_DILINK) {
                    Text(stringResource(R.string.carlink_settings_car_byd_extras), style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant)
                }
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(painterResource(R.drawable.carlink_ic_check_circle_filled), null,
                    Modifier.size(CarLinkTokens.dimens.iconTertiary + 6.dp), tint = scheme.primary)
            }
        }
    }
}

@Composable
private fun <T> customPreset(show: Boolean, value: T): List<Preset<T>> =
    if (show) listOf(Preset(value, stringResource(R.string.carlink_settings_custom), stringResource(R.string.carlink_settings_custom_detail),
        R.drawable.carlink_ic_tune, enabled = false)) else emptyList()

// ---- Sheet frame ----------------------------------------------------------------------------

/** A bottom sheet on graphite glass, wide enough on a head unit for a row of large tiles. */
@Composable
internal fun SettingsSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    com.carlink.app.ui.premium.GlassSheet(onDismiss, content)

@Composable
private fun SheetTitle(title: String, body: String?, nextConnection: Boolean) {
    com.carlink.app.ui.premium.SheetHeader(title, body)
    if (nextConnection) {
        Spacer(Modifier.height(14.dp))
        NextConnectionTag()
    }
    Spacer(Modifier.height(CarLinkTokens.dimens.gutter * 1.25f))
}

/** Large preset tiles, never a radio list of numbers (§8.3). Selecting applies at once. */
@Composable
internal fun <T> ChoiceSheet(
    title: String, body: String?, nextConnection: Boolean, options: List<Preset<T>>, selected: T?,
    onSelect: (T) -> Unit, onDismiss: () -> Unit,
) {
    SettingsSheet(onDismiss) {
        SheetTitle(title, body, nextConnection)
        PresetGrid(options, selected, onSelect)
    }
}

@Composable
internal fun <T> PresetGrid(options: List<Preset<T>>, selected: T?, onSelect: (T) -> Unit) {
    val gap = CarLinkTokens.dimens.targetGap
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minTile = if (isCar) 250.dp else 150.dp
        val fit = max(1, ((maxWidth + gap) / (minTile + gap)).toInt())
        val columns = min(min(fit, options.size), 4).let { c -> if (options.size == 4 && c == 3) 2 else c }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            options.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { option ->
                        PresetTile(option, option.value == selected, { if (option.enabled) onSelect(option.value) },
                            Modifier.weight(1f).fillMaxHeight())
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun <T> PresetTile(preset: Preset<T>, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val pad = if (isCar) 24.dp else 16.dp
    SelectableGlass(selected, onClick, modifier.alpha(if (preset.enabled) 1f else 0.6f), role = Role.RadioButton) {
        Column(Modifier.fillMaxWidth().padding(pad)) {
            when {
                preset.preview != null -> Box(Modifier.fillMaxWidth()) { preset.preview.invoke() }
                preset.icon != null -> RowGlyph(preset.icon, tint = if (selected) scheme.primary else scheme.onSurface)
            }
            if (preset.preview != null || preset.icon != null) Spacer(Modifier.height(if (isCar) 18.dp else 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(preset.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                if (selected) {
                    Icon(painterResource(R.drawable.carlink_ic_check_circle_filled), null,
                        Modifier.size(CarLinkTokens.dimens.iconTertiary + 6.dp), tint = scheme.primary)
                }
            }
            if (preset.caption != null) {
                Spacer(Modifier.height(4.dp))
                Text(preset.caption, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

// ---- Wi-Fi details ----------------------------------------------------------------------------

/** Name and password of a Wi-Fi network, checked with [WifiDetails.isValid] before saving. */
@Composable
private fun WifiSheet(title: String, body: String, initial: WifiDetails, suggestion: String?, onSave: (WifiDetails) -> Unit,
                      onDismiss: () -> Unit) {
    var ssid by rememberSaveable { mutableStateOf(initial.ssid) }
    var password by rememberSaveable { mutableStateOf(initial.password) }
    var reveal by rememberSaveable { mutableStateOf(false) }
    val details = WifiDetails(ssid, password)
    val nameError = ssid.toByteArray(Charsets.UTF_8).size > 32
    val passwordError = password.isNotEmpty() && password.length !in 8..63
    val gap = CarLinkTokens.dimens.targetGap
    SettingsSheet(onDismiss) {
        SheetTitle(title, body, nextConnection = true)
        if (suggestion != null && suggestion != ssid) {
            GlassButton(stringResource(R.string.carlink_settings_wifi_use_current, suggestion), { ssid = suggestion },
                icon = R.drawable.carlink_ic_wifi)
            Spacer(Modifier.height(gap * 1.5f))
        }
        WifiField(ssid, { ssid = it }, stringResource(R.string.carlink_settings_wifi_name), R.drawable.carlink_ic_wifi,
            error = if (nameError) stringResource(R.string.carlink_settings_wifi_name_error) else null)
        Spacer(Modifier.height(gap))
        WifiField(password, { password = it }, stringResource(R.string.carlink_settings_wifi_password), R.drawable.carlink_ic_lock,
            error = if (passwordError) stringResource(R.string.carlink_settings_wifi_password_error) else null,
            password = true, reveal = reveal, onReveal = { reveal = !reveal })
        Spacer(Modifier.height(CarLinkTokens.dimens.gutter * 1.5f))
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            IonButton(stringResource(R.string.carlink_settings_wifi_save), { if (details.isValid()) onSave(details) },
                Modifier.alpha(if (details.isValid()) 1f else 0.4f), icon = R.drawable.carlink_ic_check)
            if (initial.ssid.isNotEmpty()) {
                GlassButton(stringResource(R.string.carlink_settings_wifi_forget), { onSave(WifiDetails()) }, icon = R.drawable.carlink_ic_delete)
            }
        }
    }
}

@Composable
private fun WifiField(value: String, onChange: (String) -> Unit, label: String, @DrawableRes icon: Int, error: String?,
                      password: Boolean = false, reveal: Boolean = false, onReveal: () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) },
        textStyle = MaterialTheme.typography.bodyLarge,
        leadingIcon = { Icon(painterResource(icon), null, Modifier.size(CarLinkTokens.dimens.iconTertiary + 4.dp)) },
        trailingIcon = if (password) ({
            Text(stringResource(if (reveal) R.string.carlink_settings_wifi_hide else R.string.carlink_settings_wifi_show),
                style = MaterialTheme.typography.labelMedium, color = scheme.primary,
                modifier = Modifier.padding(end = 12.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onReveal).padding(12.dp))
        }) else null,
        visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.glassFill, unfocusedContainerColor = colors.glassFill,
            focusedBorderColor = scheme.primary, unfocusedBorderColor = colors.glassStrokeTop,
            focusedLabelColor = scheme.primary, cursorColor = scheme.primary,
        ),
    )
}

// ---- Time ------------------------------------------------------------------------------------

/** Picks a time from large presets, then fine-tunes it in 15-minute steps. Applies live. */
@Composable
private fun TimeSheet(title: String, minute: Int, presets: List<Int>, onChange: (Int) -> Unit, onDismiss: () -> Unit) {
    val dimens = CarLinkTokens.dimens
    SettingsSheet(onDismiss) {
        SheetTitle(title, null, nextConnection = false)
        Text(clockText(minute), style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(dimens.gutter))
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
            GlassButton(stringResource(R.string.carlink_settings_time_earlier), { onChange(wrap(minute - 15)) }, icon = R.drawable.carlink_ic_expand_more)
            GlassButton(stringResource(R.string.carlink_settings_time_later), { onChange(wrap(minute + 15)) }, icon = R.drawable.carlink_ic_expand_less)
        }
        Spacer(Modifier.height(dimens.gutter * 1.5f))
        PresetGrid(presets.map { Preset(it, clockText(it)) }, minute, onChange)
        Spacer(Modifier.height(dimens.gutter * 1.5f))
        IonButton(stringResource(R.string.carlink_settings_done), onDismiss, icon = R.drawable.carlink_ic_check)
    }
}

private fun wrap(minute: Int) = ((minute % 1440) + 1440) % 1440

// ---- Live previews ----------------------------------------------------------------------------

/** Fixed "projection" colors: the miniature is a dark car screen in both themes. */
private val MiniScreen = Color(0xFF0E141B)
private val MiniApps = listOf(Color(0xFF5BD6F0), Color(0xFF7EE2A8), Color(0xFFFFB547), Color(0xFF9DB8FF), Color(0xFFFF7A70), Color(0xFFEEF2F6))

/** A miniature CarPlay/Android Auto (or CarLink) screen whose icons grow with [scale]. */
@Composable
internal fun ScalePreview(scale: Float, carLink: Boolean = false) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        val r = CornerRadius(size.minDimension * 0.1f)
        drawRoundRect(MiniScreen, cornerRadius = r)
        drawRoundRect(Color.White.copy(alpha = 0.14f), cornerRadius = r, style = Stroke(1.dp.toPx()))
        val pad = size.height * 0.1f
        if (carLink) {
            // CarLink: a headline and two glass cards.
            val line = size.height * 0.09f * scale
            drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(pad, pad), Size(size.width * 0.38f * scale, line), CornerRadius(line / 2))
            drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(pad, pad + line * 1.7f), Size(size.width * 0.5f * scale, line * 0.6f),
                CornerRadius(line / 2))
            val cardTop = pad + line * 3.2f
            val cardH = (size.height - cardTop - pad).coerceAtLeast(line)
            val cardW = (size.width - pad * 3) / 2
            repeat(2) { i ->
                drawRoundRect(Color.White.copy(alpha = 0.09f), Offset(pad + i * (cardW + pad), cardTop), Size(cardW, cardH), CornerRadius(cardH * 0.2f))
            }
            drawRoundRect(accent, Offset(pad, size.height - pad - line * 1.4f), Size(size.width * 0.22f * scale, line * 1.4f), CornerRadius(line))
            return@Canvas
        }
        // Dock on the driver side, then a grid of app icons.
        val dock = size.width * 0.13f
        drawRoundRect(Color.White.copy(alpha = 0.07f), Offset(0f, 0f), Size(dock, size.height), r)
        val icon = size.height * 0.2f * scale
        val gap = icon * 0.38f
        repeat(3) { i ->
            drawCircle(Color.White.copy(alpha = 0.55f), icon * 0.26f, Offset(dock / 2, pad + icon * 0.4f + i * (icon * 0.9f)))
        }
        val left = dock + pad
        val cols = ((size.width - left - pad + gap) / (icon + gap)).toInt().coerceAtLeast(1)
        val rows = ((size.height - pad * 2 + gap) / (icon + gap)).toInt().coerceAtLeast(1)
        var n = 0
        for (row in 0 until rows) for (col in 0 until cols) {
            drawRoundRect(MiniApps[n++ % MiniApps.size].copy(alpha = 0.85f), Offset(left + col * (icon + gap), pad + row * (icon + gap)),
                Size(icon, icon), CornerRadius(icon * 0.26f))
        }
    }
}

/** Sharpness shown as finer or coarser pixels over the same shape. */
@Composable
private fun PicturePreview(level: Int) {
    val accent = Color(0xFF5BD6F0)
    Canvas(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        val r = CornerRadius(size.minDimension * 0.1f)
        drawRoundRect(MiniScreen, cornerRadius = r)
        drawRoundRect(Color.White.copy(alpha = 0.14f), cornerRadius = r, style = Stroke(1.dp.toPx()))
        // A route line drawn as a staircase: more steps = sharper.
        val steps = 4 shl level
        val cell = size.width * 0.8f / steps
        val stroke = size.height * 0.07f
        val path = Path()
        val x0 = size.width * 0.1f
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val y = size.height * (0.78f - 0.56f * (t * t * (3 - 2 * t)))
            val qy = (y / cell).toInt() * cell
            if (i == 0) path.moveTo(x0, qy) else path.lineTo(x0 + i * cell, qy)
        }
        drawPath(path, accent, style = Stroke(stroke))
        drawCircle(Color(0xFF7EE2A8), stroke * 1.2f, Offset(size.width * 0.9f, size.height * 0.22f))
    }
}

/** The dashboard seen from the driver's seat: the wheel and the dock on the chosen side. */
@Composable
private fun DrivingSidePreview(right: Boolean) {
    val line = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        val w = size.width
        val h = size.height
        // Screen in the middle of the dashboard.
        val screenW = w * 0.5f
        val screenH = h * 0.42f
        val sx = (w - screenW) / 2
        val sy = h * 0.1f
        drawRoundRect(MiniScreen, Offset(sx, sy), Size(screenW, screenH), CornerRadius(screenH * 0.14f))
        val dockW = screenW * 0.16f
        drawRoundRect(accent.copy(alpha = 0.85f), Offset(if (right) sx + screenW - dockW else sx, sy), Size(dockW, screenH),
            CornerRadius(screenH * 0.14f))
        // Dashboard line.
        drawLine(line.copy(alpha = 0.25f), Offset(0f, h * 0.62f), Offset(w, h * 0.62f), strokeWidth = 2.dp.toPx())
        // Steering wheel.
        val cx = if (right) w * 0.8f else w * 0.2f
        // Only the top of the wheel shows above the preview's lower edge, so it never covers the caption.
        val radius = h * 0.3f
        clipRect {
            drawCircle(line.copy(alpha = 0.75f), radius, Offset(cx, h * 0.98f), style = Stroke(h * 0.06f))
            drawCircle(line.copy(alpha = 0.75f), radius * 0.22f, Offset(cx, h * 0.98f))
        }
    }
}

/** CarLink's colors in miniature. */
@Composable
private fun AppearancePreview(mode: ThemeMode) {
    val dark = Color(0xFF0C1016)
    val light = Color(0xFFE8EBEF)
    Canvas(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        val r = CornerRadius(size.minDimension * 0.1f)
        fun panel(bg: Color, fg: Color) {
            drawRoundRect(bg, cornerRadius = r)
            val pad = size.height * 0.14f
            val line = size.height * 0.1f
            drawRoundRect(fg.copy(alpha = 0.85f), Offset(pad, pad), Size(size.width * 0.42f, line), CornerRadius(line / 2))
            drawRoundRect(fg.copy(alpha = 0.10f), Offset(pad, pad + line * 2), Size(size.width - pad * 2, size.height - pad * 2 - line * 2),
                CornerRadius(line))
        }
        when (mode) {
            ThemeMode.DARK -> panel(dark, Color.White)
            ThemeMode.LIGHT -> panel(light, Color(0xFF0D1117))
            ThemeMode.FOLLOW_SYSTEM -> {
                panel(light, Color(0xFF0D1117))
                val clip = Path().apply {
                    moveTo(size.width * 0.62f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height); lineTo(size.width * 0.38f, size.height); close()
                }
                clipPath(clip) { panel(dark, Color.White) }
            }
        }
        drawRoundRect(Color(0x33FFFFFF), cornerRadius = r, style = Stroke(1.dp.toPx()))
    }
}
