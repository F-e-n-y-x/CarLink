package com.carlink.app.ui.settings

import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.ui.premium.GlassButton
import com.carlink.app.ui.premium.GlassCard
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.core.settings.WheelAction
import com.carlink.core.settings.WheelKeys
import kotlinx.coroutines.delay

/** How long Learn waits for a button. */
internal const val WHEEL_LEARN_SECONDS = 15

/**
 * Settings › Car › Steering-wheel buttons: one row per [WheelAction] with its learned key and a
 * Learn button, and Reset. Learn waits for the next key event that reaches this window (the
 * stereo's wheel keys arrive as key events, or as media keys, to the app in front).
 */
@Composable
internal fun WheelKeysSheet(
    keys: Map<WheelAction, Int>,
    onChange: (Map<WheelAction, Int>) -> Unit,
    onDismiss: () -> Unit,
    initialLearning: WheelAction? = null,
) {
    var learning by rememberSaveable { mutableStateOf(initialLearning) }
    var timedOut by rememberSaveable { mutableStateOf(false) }
    val gap = CarLinkTokens.dimens.targetGap
    SettingsSheet(onDismiss) {
        val action = learning
        if (action != null) {
            // Alone in the sheet, so Cancel stays in view on a 600dp-tall head unit.
            LearnPanel(action,
                onKey = { code -> onChange(WheelKeys.assign(keys, action, code)); learning = null },
                onTimeout = { learning = null; timedOut = true },
                onCancel = { learning = null })
            return@SettingsSheet
        }
        SheetTitle(stringResource(R.string.carlink_wheel_keys), stringResource(R.string.carlink_wheel_keys_sheet_body),
            nextConnection = true)
        if (timedOut) {
            Text(stringResource(R.string.carlink_wheel_key_timed_out), style = MaterialTheme.typography.bodyMedium,
                color = CarLinkTokens.colors.warning, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Spacer(Modifier.height(gap))
        }
        val learn: (WheelAction) -> Unit = { timedOut = false; learning = it }
        GlassGroup {
            WheelAction.entries.forEachIndexed { index, a ->
                if (index > 0) GroupDivider()
                SettingsRow(stringResource(a.label), value = keyLabel(keys[a]), icon = a.icon, onClick = { learn(a) },
                    role = Role.Button, description = if (a == WheelAction.VOICE) stringResource(R.string.carlink_wheel_action_voice_detail) else null) {
                    GlassButton(stringResource(R.string.carlink_wheel_key_learn), { learn(a) })
                }
            }
        }
        Spacer(Modifier.height(CarLinkTokens.dimens.gutter))
        GlassButton(stringResource(R.string.carlink_wheel_key_reset), { if (keys.isNotEmpty()) onChange(emptyMap()) },
            Modifier.alpha(if (keys.isEmpty()) 0.4f else 1f), icon = R.drawable.carlink_ic_restart_alt)
    }
}

/**
 * "Press a button on the steering wheel…" with a countdown and Cancel. Takes the focus so key
 * events reach it; a key counts on its release, and only when its press also came here (so the
 * Enter that pressed Learn is not learnt). Back cancels; volume, Home and Power pass on.
 */
@Composable
internal fun LearnPanel(action: WheelAction, onKey: (Int) -> Unit, onTimeout: () -> Unit, onCancel: () -> Unit) {
    val dimens = CarLinkTokens.dimens
    val scheme = MaterialTheme.colorScheme
    val car = isCar
    val focus = remember { FocusRequester() }
    var remaining by remember(action) { mutableIntStateOf(WHEEL_LEARN_SECONDS) }
    var pressed by remember(action) { mutableIntStateOf(KeyEvent.KEYCODE_UNKNOWN) }
    val timeout by rememberUpdatedState(onTimeout)
    LaunchedEffect(action) {
        runCatching { focus.requestFocus() }
        while (remaining > 0) { delay(1_000); remaining-- }
        timeout()
    }
    GlassCard(
        Modifier.fillMaxWidth().testTag(LEARN_PANEL_TAG).focusRequester(focus).focusable().onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            val code = native.keyCode
            val down = native.action == KeyEvent.ACTION_DOWN
            when {
                code == KeyEvent.KEYCODE_BACK -> { if (!down) onCancel(); true }
                !WheelKeys.canLearn(code) -> false
                down -> { if (native.repeatCount == 0) pressed = code; true }
                native.action == KeyEvent.ACTION_UP && code == pressed -> { onKey(code); true }
                else -> true
            }
        },
        contentPadding = PaddingValues(if (car) 40.dp else 24.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (car) 14.dp else 10.dp)) {
            Icon(painterResource(R.drawable.carlink_ic_search_hands_free), null, Modifier.size(if (car) 72.dp else 48.dp),
                tint = scheme.primary)
            Text(stringResource(R.string.carlink_wheel_key_waiting), style = MaterialTheme.typography.headlineMedium,
                color = scheme.onSurface, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text(stringResource(R.string.carlink_wheel_key_waiting_for, stringResource(action.label)),
                style = MaterialTheme.typography.titleMedium, color = scheme.primary, textAlign = TextAlign.Center)
            Text(pluralStringResource(R.plurals.carlink_wheel_key_seconds, remaining, remaining),
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Text(stringResource(R.string.carlink_wheel_key_waiting_note), style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(dimens.targetGap))
            GlassButton(stringResource(R.string.carlink_wheel_key_cancel), onCancel, icon = R.drawable.carlink_ic_close)
        }
    }
}

internal const val LEARN_PANEL_TAG = "wheel-learn"

@get:StringRes
internal val WheelAction.label: Int
    get() = when (this) {
        WheelAction.VOICE -> R.string.carlink_wheel_action_voice
        WheelAction.ANSWER -> R.string.carlink_wheel_action_answer
        WheelAction.HANG_UP -> R.string.carlink_wheel_action_hang_up
        WheelAction.PLAY_PAUSE -> R.string.carlink_wheel_action_play_pause
        WheelAction.NEXT -> R.string.carlink_wheel_action_next
        WheelAction.PREVIOUS -> R.string.carlink_wheel_action_previous
    }

@get:DrawableRes
private val WheelAction.icon: Int
    get() = when (this) {
        WheelAction.VOICE -> R.drawable.carlink_ic_mic
        WheelAction.ANSWER -> R.drawable.carlink_ic_call
        WheelAction.HANG_UP -> R.drawable.carlink_ic_call_end
        WheelAction.PLAY_PAUSE -> R.drawable.carlink_ic_play_pause
        WheelAction.NEXT -> R.drawable.carlink_ic_skip_next
        WheelAction.PREVIOUS -> R.drawable.carlink_ic_skip_previous
    }

/** "MEDIA_NEXT · 87", "Key 312" for a vendor code Android has no name for, or "Not set". */
@Composable
private fun keyLabel(code: Int?): String {
    if (code == null) return stringResource(R.string.carlink_settings_not_set)
    val name = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
    return if (name.all(Char::isDigit)) stringResource(R.string.carlink_wheel_key_unnamed, code)
    else stringResource(R.string.carlink_wheel_key_value, name, code)
}
