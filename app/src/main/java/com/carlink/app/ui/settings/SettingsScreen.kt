package com.carlink.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.Route
import com.carlink.app.ui.components.CarTopBar
import com.carlink.app.ui.components.ChoiceSheet
import com.carlink.app.ui.components.PageHeader
import com.carlink.app.ui.components.RailItem
import com.carlink.app.ui.components.SettingsRail
import com.carlink.app.ui.components.StatusChip
import com.carlink.app.ui.components.engineName
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.CarMotion
import com.carlink.app.ui.theme.LocalReduceMotion
import com.carlink.app.ui.theme.WidthClass

/**
 * CarLink's Settings hub (DESIGN_SPEC §6.3): CarLink's own settings plus links into each
 * engine's settings. Two panes (rail + content) when the window allows, otherwise a category
 * list (level 0) and a category page (level 1).
 */
@Composable
fun SettingsScreen(vm: CarLinkViewModel, onBack: () -> Unit, navigate: (Route) -> Unit) {
    val display by vm.display.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val home by vm.home.collectAsStateWithLifecycle()
    val actions = remember(vm) { ViewModelSettingsActions(vm) }
    SettingsContent(SettingsUiState(display, connection, home), actions, onBack, navigate)
}

/** The stateless screen; [initialCategory] and [initialChoice] exist for previews and tests. */
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    navigate: (Route) -> Unit,
    initialCategory: SettingsCategory = SettingsCategory.OVERVIEW,
    initialChoice: SettingsChoice? = null,
) {
    val layout = CarLinkTokens.layout
    val twoPane = layout.isTwoPane
    var selected by rememberSaveable { mutableStateOf(initialCategory) }
    var choice by rememberSaveable { mutableStateOf(initialChoice) }
    // The sheet keeps its last content while it animates out.
    var shownChoice by remember { mutableStateOf(initialChoice) }
    LaunchedEffect(choice) { if (choice != null) shownChoice = choice }
    var returnFocusTo by remember { mutableStateOf<SettingsCategory?>(null) }
    val linkFocus = remember { SettingsCategory.entries.associateWith { FocusRequester() } }

    val atRoot = twoPane || selected == SettingsCategory.OVERVIEW
    val goBack: () -> Unit = {
        if (atRoot) {
            onBack()
        } else {
            returnFocusTo = selected
            selected = SettingsCategory.OVERVIEW
        }
    }
    // System back: level 1 → level 0 → leave Settings. An open side sheet handles back first.
    BackHandler(onBack = goBack)

    val scope = SettingsPageScope(
        state = state,
        actions = actions,
        navigate = navigate,
        openChoice = { choice = it },
        openCategory = { selected = it },
    )
    val liveEngine = state.home.link.takeIf { it.stage == LinkStage.CONNECTED }?.engine
    val compact = layout.width == WidthClass.COMPACT

    // A Surface root so text without an explicit color (top bar, section headers) gets onBackground.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            CarTopBar(
                title = stringResource(if (atRoot) R.string.carlink_settings_title else selected.title),
                onBack = goBack,
                chips = { if (liveEngine != null && !compact) LiveChip(liveEngine) },
            )
            if (twoPane) {
                TwoPane(scope, selected, onSelect = { selected = it })
            } else {
                SinglePane(scope, selected, liveEngine.takeIf { compact }, linkFocus)
            }
        }
        SettingsChoiceSheet(open = choice, shown = shownChoice, state = state, actions = actions,
            onDismiss = { choice = null })
    } }

    // Back from a category returns D-pad/rotary focus to the row that opened it.
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(selected, twoPane) {
        val target = returnFocusTo ?: return@LaunchedEffect
        returnFocusTo = null
        if (!twoPane && selected == SettingsCategory.OVERVIEW && inputMode.inputMode == InputMode.Keyboard) {
            withFrameNanos { }
            runCatching { linkFocus.getValue(target).requestFocus() }
        }
    }
}

/** Rail + content pane (DESIGN_SPEC §6.3.1). */
@Composable
private fun TwoPane(scope: SettingsPageScope, selected: SettingsCategory, onSelect: (SettingsCategory) -> Unit) {
    val dimens = CarLinkTokens.dimens
    val layout = CarLinkTokens.layout
    val reduceMotion = LocalReduceMotion.current
    val slide = with(LocalDensity.current) { 16.dp.roundToPx() }
    Row(Modifier.fillMaxSize()) {
        // The rail narrows on an expanded window (car ⅔ split) so the content keeps room.
        SettingsRail(Modifier.width(min(dimens.railWidth, layout.widthDp * 0.36f))) {
            SettingsCategory.entries.forEach { category ->
                RailItem(
                    label = stringResource(category.title),
                    icon = category.icon,
                    selectedIcon = category.selectedIcon,
                    selected = category == selected,
                    onClick = { onSelect(category) },
                )
            }
        }
        AnimatedContent(
            targetState = selected,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            transitionSpec = {
                val duration = if (reduceMotion) 0 else CarMotion.MEDIUM
                (fadeIn(tween(duration, easing = CarMotion.EmphasizedDecelerate)) +
                    slideInVertically(tween(duration, easing = CarMotion.EmphasizedDecelerate)) { slide }) togetherWith
                    fadeOut(tween(duration / 2, easing = CarMotion.EmphasizedAccelerate))
            },
            label = "settingsPane",
        ) { category ->
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = dimens.cardPadding)
                    .padding(top = dimens.gutter, bottom = dimens.gutter * 2),
            ) {
                Column(Modifier.widthIn(max = dimens.contentMaxWidth)) {
                    PageHeader(stringResource(category.title), stringResource(category.description))
                    Spacer(Modifier.height(dimens.targetGap))
                    scope.CategoryPage(category, showCategoryLinks = false)
                }
            }
        }
    }
}

/** Level 0 (Overview + category list) and level 1 (one category), shared-axis X between them. */
@Composable
private fun SinglePane(
    scope: SettingsPageScope,
    selected: SettingsCategory,
    liveEngine: Engine?,
    linkFocus: Map<SettingsCategory, FocusRequester>,
) {
    val dimens = CarLinkTokens.dimens
    val layout = CarLinkTokens.layout
    val reduceMotion = LocalReduceMotion.current
    val travel = with(LocalDensity.current) { 48.dp.roundToPx() }
    val margin = if (layout.width == WidthClass.COMPACT) dimens.compactMargin else dimens.screenMargin
    AnimatedContent(
        targetState = selected,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val duration = if (reduceMotion) 0 else CarMotion.LONG
            val direction = if (targetState != SettingsCategory.OVERVIEW) 1 else -1
            (slideInHorizontally(tween(duration, easing = CarMotion.EmphasizedDecelerate)) { direction * travel } +
                fadeIn(tween(duration))) togetherWith
                (slideOutHorizontally(tween(duration, easing = CarMotion.EmphasizedAccelerate)) { -direction * travel } +
                    fadeOut(tween(duration / 2)))
        },
        label = "settingsLevel",
    ) { category ->
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = margin)
                .padding(top = 4.dp, bottom = dimens.gutter * 2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = dimens.contentMaxWidth).fillMaxWidth()) {
                if (liveEngine != null) {
                    LiveChip(liveEngine)
                    Spacer(Modifier.height(dimens.targetGap))
                }
                if (category != SettingsCategory.OVERVIEW) {
                    PageHeader(title = null, description = stringResource(category.description))
                    Spacer(Modifier.height(dimens.targetGap))
                }
                scope.CategoryPage(category, showCategoryLinks = true, linkFocus = linkFocus)
            }
        }
    }
}

/** "CarPlay connected": changes here may need a reconnect (DESIGN_SPEC §6.3.1). Not tappable. */
@Composable
private fun LiveChip(engine: Engine) {
    StatusChip(
        text = stringResource(R.string.carlink_settings_live_chip, engineName(engine)),
        icon = R.drawable.carlink_ic_check_circle_filled,
        tint = CarLinkTokens.colors.success,
    )
}

@Composable
private fun SettingsChoiceSheet(
    open: SettingsChoice?,
    shown: SettingsChoice?,
    state: SettingsUiState,
    actions: SettingsActions,
    onDismiss: () -> Unit,
) {
    val visible = open != null
    when (shown) {
        SettingsChoice.THEME -> ChoiceSheet(
            visible = visible,
            title = stringResource(R.string.carlink_settings_theme),
            options = themeOptions(),
            selected = state.display.themeMode,
            onSelect = actions::setThemeMode,
            onDismiss = onDismiss,
        )
        SettingsChoice.LAYOUT -> ChoiceSheet(
            visible = visible,
            title = stringResource(R.string.carlink_settings_layout),
            options = layoutOptions(),
            selected = state.display.uiProfileMode,
            onSelect = actions::setUiProfileMode,
            onDismiss = onDismiss,
            description = stringResource(R.string.carlink_settings_layout_description),
        )
        SettingsChoice.INTERFACE_SIZE -> ChoiceSheet(
            visible = visible,
            title = stringResource(R.string.carlink_settings_interface_size),
            options = interfaceSizeOptions(),
            selected = nearestScale(state.display.interfaceScale),
            onSelect = actions::setInterfaceScale,
            onDismiss = onDismiss,
            description = stringResource(R.string.carlink_settings_interface_size_description),
        )
        null -> Unit
    }
}
