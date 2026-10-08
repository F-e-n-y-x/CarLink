package com.carlink.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.carlink.app.R
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.CarMotion
import com.carlink.app.ui.theme.LocalReduceMotion
import com.carlink.app.ui.theme.UiProfile
import com.carlink.app.ui.theme.WidthClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings building blocks (DESIGN_SPEC §6.3, §6.7): rail items, quick-setting tiles, notice
 * cards and the choice sheet. Every size comes from CarLinkTokens.dimens.
 */

/** How long a choice sheet stays open after a pick, so the driver sees the selection land. */
private const val CHOICE_CLOSE_DELAY_MS = 250L

/**
 * The D-pad/rotary focus ring (DESIGN_SPEC §7.2): a `primary` stroke drawn only while the
 * element after this modifier has focus. Touch never focuses clickables, so it stays hidden.
 */
fun Modifier.focusRing(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val width = CarLinkTokens.dimens.focusRing
    onFocusChanged { focused = it.hasFocus }
        .then(if (focused) Modifier.border(width, color, shape) else Modifier)
}

/** [focusRing] for a setting row (radius 20, as rows and cards are). */
fun Modifier.focusRingRow(): Modifier = focusRing(RoundedCornerShape(20.dp))

@Composable
private fun isLightTheme(): Boolean {
    val bg = MaterialTheme.colorScheme.background
    return 0.2126f * bg.red + 0.7152f * bg.green + 0.0722f * bg.blue > 0.5f
}

/** Cards on `background` get a 1dp outlineVariant border in light mode only (DESIGN_SPEC §5.5). */
@Composable
private fun cardBorder(): BorderStroke? =
    if (isLightTheme()) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null

/**
 * A navigation-rail entry: the selected item sits in a `primaryContainer` pill (radius 20,
 * inset 12dp) with the filled glyph (DESIGN_SPEC §6.3.1).
 */
@Composable
fun RailItem(
    label: String,
    @DrawableRes icon: Int,
    @DrawableRes selectedIcon: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = CarLinkTokens.dimens
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val duration = if (LocalReduceMotion.current) 0 else CarMotion.SHORT
    val container by animateColorAsState(
        if (selected) colors.primaryContainer else Color.Transparent, tween(duration, easing = CarMotion.Standard),
        label = "railItem",
    )
    val content = if (selected) colors.onPrimaryContainer else colors.onSurface
    Row(
        modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .heightIn(min = dimens.railItem - 8.dp)
            .focusRing(shape)
            .clip(shape)
            .background(container)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = dimens.gutter - 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CarIcon(if (selected) selectedIcon else icon, null,
            tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
        Spacer(Modifier.width(dimens.gutter * 0.75f))
        Text(label, style = MaterialTheme.typography.titleMedium, color = content, maxLines = 2)
    }
}

/** A navigation rail on `surfaceContainerLow`; it scrolls when the window is short. */
@Composable
fun SettingsRail(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxHeight()
            .clip(RoundedCornerShape(topEnd = 28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp)
            .selectableGroup(),
    ) { content() }
}

/** Page title (headlineLarge) and a one-sentence description (DESIGN_SPEC §6.3.1). */
@Composable
fun PageHeader(title: String?, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
        }
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TileLayout(
    title: String,
    value: String,
    @DrawableRes icon: Int,
    interaction: Modifier,
    modifier: Modifier,
    trailing: @Composable () -> Unit,
) {
    val dimens = CarLinkTokens.dimens
    val shape = MaterialTheme.shapes.medium
    val border = cardBorder()
    Row(
        modifier.heightIn(min = dimens.rowTwoLine)
            .focusRing(shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .then(interaction)
            .padding(horizontal = dimens.gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CarIcon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(dimens.gutter * 0.75f))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(dimens.targetGap))
        trailing()
    }
}

/** Overview quick-setting tile that opens a choice (title, current value, chevron). */
@Composable
fun QuickChoiceTile(title: String, value: String, @DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TileLayout(title, value, icon, Modifier.clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) {},
        modifier) {
        CarIcon(R.drawable.carlink_ic_chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Overview quick-setting tile that is a switch; the whole tile toggles (Role.Switch). */
@Composable
fun QuickToggleTile(
    title: String,
    value: String,
    @DrawableRes icon: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = stringResource(R.string.carlink_state_on)
    val off = stringResource(R.string.carlink_state_off)
    val interaction = Modifier
        .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
        .semantics(mergeDescendants = true) { stateDescription = if (checked) on else off }
    TileLayout(title, value, icon, interaction, modifier) {
        Switch(checked = checked, onCheckedChange = null,
            modifier = if (CarLinkTokens.profile == UiProfile.CAR) Modifier.padding(horizontal = 8.dp) else Modifier)
    }
}

/**
 * A status card with one way out: icon, title, body and at most one action (DESIGN_SPEC §3.4).
 * The action sits at the end on wide panes and under the text on narrow ones.
 */
@Composable
fun NoticeCard(
    title: String,
    body: String?,
    @DrawableRes icon: Int,
    iconTint: Color,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    bodyColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    liveRegion: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val dimens = CarLinkTokens.dimens
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        border = if (containerColor == MaterialTheme.colorScheme.surfaceContainer) cardBorder() else null,
    ) {
        BoxWithConstraints {
            val wide = maxWidth >= if (CarLinkTokens.profile == UiProfile.CAR) 720.dp else 520.dp
            val text = @Composable { textModifier: Modifier ->
                Row(textModifier.semantics(mergeDescendants = true) {
                    if (liveRegion) this.liveRegion = LiveRegionMode.Polite
                }) {
                    CarIcon(icon, null, tint = iconTint)
                    Spacer(Modifier.width(dimens.gutter * 0.75f))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = contentColor)
                        if (body != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(body, style = MaterialTheme.typography.bodyMedium, color = bodyColor)
                        }
                    }
                }
            }
            if (wide || action == null) {
                Row(Modifier.padding(dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
                    text(Modifier.weight(1f))
                    if (action != null) {
                        Spacer(Modifier.width(dimens.gutter))
                        action()
                    }
                }
            } else {
                Column(Modifier.padding(dimens.gutter)) {
                    text(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(dimens.targetGap))
                    Box(Modifier.padding(start = dimens.iconSecondary + dimens.gutter * 0.75f)) { action() }
                }
            }
        }
    }
}

/** One option of a choice sheet. */
data class ChoiceOption<T>(
    val value: T,
    val title: String,
    val supporting: String? = null,
    @param:DrawableRes val icon: Int? = null,
)

/** A radio row; the selected one sits on `primaryContainer` (DESIGN_SPEC §5.1). */
@Composable
fun ChoiceOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    @DrawableRes icon: Int? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    SettingRow(
        title,
        modifier.focusRing(shape).clip(shape)
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .semantics { this.selected = selected },
        supporting, icon, onClick = onClick, role = Role.RadioButton,
    ) {
        CarIcon(if (selected) R.drawable.carlink_ic_radio_button_checked else R.drawable.carlink_ic_radio_button_unchecked,
            null, tint = if (selected) colors.primary else colors.onSurfaceVariant)
    }
}

/**
 * A single-choice picker (DESIGN_SPEC §6.3.1): a side sheet from the end edge on expanded and
 * wider windows, a bottom sheet on compact and medium ones. A pick applies immediately and the
 * sheet closes 250 ms later. Keep it composed while hidden so it can animate out.
 */
@Composable
fun <T> ChoiceSheet(
    visible: Boolean,
    title: String,
    options: List<ChoiceOption<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    description: String? = null,
) {
    if (CarLinkTokens.layout.width >= WidthClass.EXPANDED) {
        SideChoiceSheet(visible, title, description, options, selected, onSelect, onDismiss)
    } else if (visible) {
        BottomChoiceSheet(title, description, options, selected, onSelect, onDismiss)
    }
}

@Composable
private fun <T> SideChoiceSheet(
    visible: Boolean,
    title: String,
    description: String?,
    options: List<ChoiceOption<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val duration = if (LocalReduceMotion.current) 0 else CarMotion.MEDIUM
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    val closeLabel = stringResource(R.string.carlink_settings_close)
    BackHandler(enabled = visible, onBack = onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sheetWidth = min(CarLinkTokens.dimens.sideSheetWidth, maxWidth * 0.9f)
        AnimatedVisibility(visible, enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration))) {
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                    .focusProperties { canFocus = false }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = closeLabel,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(duration, easing = CarMotion.EmphasizedDecelerate)) { if (rtl) -it else it },
            exit = slideOutHorizontally(tween(duration, easing = CarMotion.EmphasizedAccelerate)) { if (rtl) -it else it },
        ) {
            Surface(
                modifier = Modifier.fillMaxHeight().width(sheetWidth),
                shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                ChoiceSheetBody(title, description, options, selected, showClose = true, onDismiss = onDismiss) { value ->
                    if (value != selected) onSelect(value)
                    scope.launch {
                        delay(CHOICE_CLOSE_DELAY_MS)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> BottomChoiceSheet(
    title: String,
    description: String?,
    options: List<ChoiceOption<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val hide: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
    ) {
        ChoiceSheetBody(title, description, options, selected, showClose = false, onDismiss = hide) { value ->
            if (value != selected) onSelect(value)
            scope.launch {
                delay(CHOICE_CLOSE_DELAY_MS)
                hide()
            }
        }
    }
}

@Composable
private fun <T> ChoiceSheetBody(
    title: String,
    description: String?,
    options: List<ChoiceOption<T>>,
    selected: T?,
    showClose: Boolean,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    val dimens = CarLinkTokens.dimens
    val selectedFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    Column(
        Modifier.fillMaxWidth()
            .semantics { paneTitle = title }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.gutter * 0.5f)
            .padding(top = if (showClose) dimens.gutter else 0.dp, bottom = dimens.gutter),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = dimens.gutter * 0.5f), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp).semantics { heading() })
            if (showClose) {
                IconAction(R.drawable.carlink_ic_close, stringResource(R.string.carlink_settings_close), onDismiss)
            }
        }
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = dimens.gutter * 0.5f, end = dimens.gutter * 0.5f, bottom = 8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { option ->
                val isSelected = option.value == selected
                ChoiceOptionRow(
                    title = option.title,
                    selected = isSelected,
                    onClick = { onPick(option.value) },
                    supporting = option.supporting,
                    icon = option.icon,
                    modifier = if (isSelected) Modifier.focusRequester(selectedFocus) else Modifier,
                )
            }
        }
    }
    // D-pad and rotary users land on the current value, as a radio group expects.
    LaunchedEffect(Unit) {
        if (inputMode.inputMode == InputMode.Keyboard && options.any { it.value == selected }) {
            runCatching { selectedFocus.requestFocus() }
        }
    }
}
