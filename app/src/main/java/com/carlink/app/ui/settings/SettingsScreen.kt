package com.carlink.app.ui.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.carlink.app.R
import com.carlink.app.diagnostics.AppInfo
import com.carlink.core.settings.AdvancedSetting
import com.carlink.core.settings.AdvancedSettings
import com.carlink.core.settings.Control
import com.carlink.core.settings.EngineAccess
import com.carlink.core.settings.InterfaceSize
import com.carlink.core.settings.Overrides
import com.carlink.core.settings.Picture
import com.carlink.core.settings.SettingsCategory
import com.carlink.core.settings.UnifiedSettings
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.Route
import com.carlink.app.ui.premium.Atmosphere
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.CarMotion
import com.carlink.app.ui.theme.LocalReduceMotion
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfileMode
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CarLink's Settings, entirely native: one set of Essentials for both engines, every engine
 * option under "Show advanced", and a hidden Lab (design/REBUILD_PLAN.md, RESEARCH_PREMIUM_UX §8).
 * The engines' own settings screens are never opened.
 */
@Composable
fun SettingsScreen(vm: CarLinkViewModel, onBack: () -> Unit, navigate: (Route) -> Unit, startCategory: SettingsCategory? = null) {
    val unified by vm.unified.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val display by vm.display.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    val vehicle by vm.vehicle.collectAsStateWithLifecycle()
    val stereoBluetooth by vm.stereoBluetooth.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val access = remember { EngineAccess(context.applicationContext) }
    val engineValues = remember { mutableStateOf<Map<String, String>?>(null) }

    // Engine values are only needed once Advanced or Lab rows are visible. Android Auto's come
    // over IPC from ":aa", so read everything on a background dispatcher. Re-read after a
    // Picture or Interface size change: the coordinator rewrites those engine values then.
    val wantsEngineValues = unified.showAdvanced || unified.labUnlocked
    LaunchedEffect(wantsEngineValues, unified.picture, unified.interfaceSize) {
        if (!wantsEngineValues) return@LaunchedEffect
        if (engineValues.value != null) delay(ENGINE_REREAD_DELAY_MS)
        engineValues.value = withContext(Dispatchers.IO) { readEngineValues(access) }
    }

    // Re-read on every resume: the driver grants "Display over other apps" in Android's settings.
    var overlayAllowed by remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        overlayAllowed = Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(context)
        onPauseOrDispose { }
    }

    val goTo = navigate
    val actions = remember(vm) {
        object : SettingsActions {
            override fun update(change: (UnifiedSettings) -> UnifiedSettings) = vm.updateSettings(change)
            override fun setAutoConnectBluetooth(on: Boolean) = vm.setAutoConnectBluetooth(on)
            override fun setAutoConnectUsb(on: Boolean) = vm.setAutoConnectUsb(on)
            override fun setStartOnBoot(on: Boolean) = vm.setStartOnBoot(on)
            override fun setFloatingSwitcher(on: Boolean) = vm.setFloatingSwitcher(on)
            override fun setWirelessOnExternalBluetooth(on: Boolean) = vm.setWirelessOnExternalBluetooth(on)
            override fun setRootMode(on: Boolean) = vm.setRootMode(on)
            override fun openOverlayPermission() = vm.fix(com.carlink.core.core.LinkProblem.OVERLAY)
            override fun resumeAutoConnect() = vm.resumeAutoConnect()
            override fun setThemeMode(mode: ThemeMode) = vm.setThemeMode(mode)
            override fun setUiProfileMode(mode: UiProfileMode) = vm.setUiProfileMode(mode)
            override fun setInterfaceScale(scale: Float) = vm.setInterfaceScale(scale)
            override fun setRenderingMode(mode: com.carlink.app.ui.theme.RenderingMode) = vm.setRenderingMode(mode)
            override fun openBluetoothSettings() = vm.openBluetoothSettings()
            override fun openAppSettings() = vm.openAppSettings()
            override fun setCarBrand(brand: com.carlink.core.settings.CarBrand) = vm.setCarBrand(brand)
            override fun navigate(route: Route) = goTo(route)
            override suspend fun readCarHotspot() = vm.readCarHotspot()
            override suspend fun readSavedWifiPassword(ssid: String) = vm.readSavedWifiPassword(ssid)
            override fun saveCarHotspot(details: com.carlink.core.settings.WifiDetails) = vm.saveCarHotspot(details)

            override fun setAdvanced(setting: AdvancedSetting, value: String) {
                // An exact per-engine value replaces the unified preset: mark it Custom first so
                // the settings applier stops writing the preset over it.
                when (setting.overrides) {
                    Overrides.PICTURE -> vm.updateSettings { it.copy(picture = Picture.CUSTOM) }
                    Overrides.INTERFACE_SIZE -> vm.updateSettings { it.copy(interfaceSize = InterfaceSize.CUSTOM) }
                    null -> Unit
                }
                engineValues.value = engineValues.value.orEmpty() + (setting.id to value)
                // The ViewModel's scope, so leaving Settings never cancels a pending write.
                vm.viewModelScope.launch(Dispatchers.IO) { runCatching { writeEngineValue(access, setting, value) } }
            }
        }
    }

    val ui = SettingsUi(
        unified = unified, connection = connection, display = display,
        joinedWifi = network?.joinedSsid,
        // The BYD-only rows follow the car brand the driver chose (or the detected one).
        isByd = vehicle.profile == com.carlink.core.settings.VehicleProfile.BYD_DILINK,
        versionName = AppInfo.versionName,
        advancedValues = engineValues.value,
        overlayAllowed = overlayAllowed,
        carBrand = vehicle.brand,
        detectedBrand = vehicle.detected,
        stereoBluetoothExternal = stereoBluetooth?.classification?.blocksWireless == true,
        stereoBluetoothFyt = stereoBluetooth?.classification == com.carlink.core.detect.StereoBluetooth.FYT_MODULE,
    )
    SettingsContent(ui, actions, onBack, startCategory)
}

private const val ENGINE_REREAD_DELAY_MS = 500L

/** Every Advanced and Lab value as the row encodes it. Blocking (IPC to ":aa"). */
internal fun readEngineValues(access: EngineAccess): Map<String, String> {
    runCatching { access.loadAndroidAuto() }
    return AdvancedSettings.all.mapNotNull { setting ->
        runCatching {
            setting.id to when (val control = setting.control) {
                is Control.Toggle -> control.read(access).toString()
                is Control.Choice -> control.read(access)
            }
        }.getOrNull()
    }.toMap()
}

/** Blocking (IPC to ":aa" for Android Auto settings). */
internal fun writeEngineValue(access: EngineAccess, setting: AdvancedSetting, value: String) {
    when (val control = setting.control) {
        is Control.Toggle -> control.write(access, value.toBoolean())
        is Control.Choice -> control.write(access, value)
    }
}


/**
 * Stateless Settings, used by the screen, previews and the screenshot gallery.
 *
 * Wide windows (≥720dp: head unit, tablet, phone landscape) show a rail of category tiles and
 * the selected category beside it. Narrow windows show a grid of categories, then drill in;
 * Back returns to the grid first.
 */
@Composable
fun SettingsContent(
    ui: SettingsUi,
    actions: SettingsActions,
    onBack: () -> Unit,
    startCategory: SettingsCategory? = null,
    initialSheet: String? = null,
) {
    var selected by rememberSaveable(startCategory) { mutableStateOf(startCategory) }
    var sheet by rememberSaveable { mutableStateOf(initialSheet) }
    var versionTaps by remember { mutableIntStateOf(0) }
    val onVersionTap: () -> Unit = {
        if (!ui.unified.labUnlocked) {
            versionTaps++
            if (versionTaps >= LAB_TAPS) {
                versionTaps = 0
                actions.update { it.copy(labUnlocked = true) }
            }
        }
    }
    val page: @Composable (SettingsCategory, Modifier) -> Unit = { category, modifier ->
        CategoryPage(category, ui, actions, openSheet = { sheet = it }, versionTaps = versionTaps, onVersionTap = onVersionTap,
            modifier = modifier)
    }
    val setAdvanced: (Boolean) -> Unit = { on -> actions.update { it.copy(showAdvanced = on) } }

    Atmosphere(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = maxWidth >= 720.dp
            BackHandler(enabled = !wide && selected != null) { selected = null }
            if (wide) {
                WideSettings(selected ?: SettingsCategory.CONNECTION, ui, onSelect = { selected = it }, onBack = onBack,
                    onAbout = { actions.navigate(Route.ABOUT) }, setAdvanced = setAdvanced, page = page, maxWidth = maxWidth)
            } else {
                NarrowSettings(selected, ui, onSelect = { selected = it }, onBack = onBack,
                    onAbout = { actions.navigate(Route.ABOUT) }, setAdvanced = setAdvanced, page = page)
            }
        }
    }
    SettingsSheetHost(sheet, ui, actions, onDismiss = { sheet = null })
}

@Composable
private fun WideSettings(
    selected: SettingsCategory, ui: SettingsUi, onSelect: (SettingsCategory) -> Unit, onBack: () -> Unit, onAbout: () -> Unit,
    setAdvanced: (Boolean) -> Unit, page: @Composable (SettingsCategory, Modifier) -> Unit, maxWidth: Dp,
) {
    val dimens = CarLinkTokens.dimens
    val margin = dimens.screenMargin
    val railWidth = (if (isCar) 380.dp else 260.dp).coerceAtMost(maxWidth * 0.32f)
    Column(Modifier.fillMaxSize()) {
        Header(stringResource(R.string.carlink_settings_title), onBack, margin) {
            AdvancedPill(ui.unified.showAdvanced, setAdvanced)
        }
        Row(Modifier.fillMaxSize().padding(start = margin, end = margin)) {
            Column(
                Modifier.width(railWidth).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = dimens.gutter),
                verticalArrangement = Arrangement.spacedBy(if (isCar) 12.dp else 8.dp),
            ) {
                categoryOrder.forEach { category ->
                    RailTile(stringResource(category.title), if (category == selected) category.selectedIcon else category.icon,
                        category == selected, { onSelect(category) })
                }
                Spacer(Modifier.height(if (isCar) 8.dp else 4.dp))
                RailTile(stringResource(R.string.carlink_settings_about), R.drawable.carlink_ic_info, false, onAbout,
                    trailingIcon = R.drawable.carlink_ic_chevron_right)
            }
            Spacer(Modifier.width(dimens.gutter * 1.5f))
            CategoryCrossfade(selected, Modifier.weight(1f).fillMaxHeight()) { category ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = dimens.gutter * 2)) {
                    Column(Modifier.widthIn(max = 1040.dp)) {
                        PageTitle(category)
                        Spacer(Modifier.height(dimens.gutter))
                        page(category, Modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun NarrowSettings(
    selected: SettingsCategory?, ui: SettingsUi, onSelect: (SettingsCategory?) -> Unit, onBack: () -> Unit, onAbout: () -> Unit,
    setAdvanced: (Boolean) -> Unit, page: @Composable (SettingsCategory, Modifier) -> Unit,
) {
    val dimens = CarLinkTokens.dimens
    val margin = dimens.compactMargin
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(selected, transitionSpec = {
        val duration = if (reduceMotion) 0 else CarMotion.LONG
        val forward = targetState != null
        (slideInHorizontally(tween(duration, easing = CarMotion.EmphasizedDecelerate)) { (if (forward) 1 else -1) * it / 6 } +
            fadeIn(tween(duration))) togetherWith fadeOut(tween(duration / 2))
    }, label = "settings-drill") { category ->
        Column(Modifier.fillMaxSize()) {
            if (category == null) {
                Header(stringResource(R.string.carlink_settings_title), onBack, margin)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = margin)
                    .padding(top = 8.dp, bottom = dimens.gutter * 2), verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
                    GlassGroup {
                        SwitchRow(stringResource(R.string.carlink_settings_show_advanced), ui.unified.showAdvanced, setAdvanced,
                            description = stringResource(R.string.carlink_settings_show_advanced_detail), icon = R.drawable.carlink_ic_tune)
                    }
                    Spacer(Modifier.height(dimens.targetGap))
                    (categoryOrder.map { it as SettingsCategory? } + null).chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
                            row.forEach { item ->
                                val tileModifier = Modifier.weight(1f).fillMaxHeight()
                                if (item != null) {
                                    GridTile(stringResource(item.title), stringResource(item.summary), item.icon, { onSelect(item) }, tileModifier)
                                } else {
                                    GridTile(stringResource(R.string.carlink_settings_about), stringResource(R.string.carlink_settings_about_summary),
                                        R.drawable.carlink_ic_info, onAbout, tileModifier)
                                }
                            }
                        }
                    }
                }
            } else {
                Header(stringResource(category.title), { onSelect(null) }, margin)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = margin)
                    .padding(top = 8.dp, bottom = dimens.gutter * 2)) {
                    Text(stringResource(category.summary), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                    Spacer(Modifier.height(dimens.gutter))
                    page(category, Modifier)
                }
            }
        }
    }
}

@Composable
private fun CategoryCrossfade(category: SettingsCategory, modifier: Modifier, content: @Composable (SettingsCategory) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(category, modifier, transitionSpec = {
        val duration = if (reduceMotion) 0 else CarMotion.MEDIUM
        fadeIn(tween(duration, delayMillis = duration / 3)) togetherWith fadeOut(tween(duration / 2))
    }, label = "settings-category") { content(it) }
}

@Composable
private fun Header(title: String, onBack: () -> Unit, margin: Dp, trailing: (@Composable () -> Unit)? = null) {
    val dimens = CarLinkTokens.dimens
    Row(
        Modifier.fillMaxWidth().heightIn(min = dimens.topBar + if (isCar) 16.dp else 8.dp).padding(horizontal = margin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundGlassButton(R.drawable.carlink_ic_arrow_back, stringResource(R.string.carlink_settings_back), onBack)
        Spacer(Modifier.width(if (isCar) 24.dp else 16.dp))
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) {
            Spacer(Modifier.width(dimens.gutter))
            trailing()
        }
    }
}

@Composable
private fun PageTitle(category: SettingsCategory) {
    Box(Modifier.padding(horizontal = 8.dp)) {
        Column {
            Text(stringResource(category.title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(6.dp))
            Text(stringResource(category.summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
